package org.bluesky.training.report;

import org.bluesky.training.TrainingPlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P17：报告/脚本/消息（V13 表 + 纯逻辑，详细设计 2.2 §5.5/§9.2/§9.6）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class ReportsScriptsMessagesTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String newGroup() {
        String groupId = "group-rep-" + UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_group (id, name, state, simulation_time_seconds) "
                + "VALUES (?, ?, 'RUNNING', 600)", groupId, "报告组");
        return groupId;
    }

    @Test
    void givenStateTransitionsThenSequenceIsUniqueAndTerminalHasStableReason() {
        String groupId = newGroup();
        String instructionId = "ins-rep-" + UUID.randomUUID();

        transactionTemplate.execute(status -> {
            jdbc.update("INSERT INTO command_report (id, exercise_group_id, instruction_id, "
                            + "transition_sequence, to_status, reason_code, description, "
                            + "simulation_time_seconds) VALUES (?, ?, ?, 1, 'VALIDATED', NULL, "
                            + "'校验通过', 600)", UUID.randomUUID().toString(), groupId, instructionId);
            jdbc.update("INSERT INTO command_report (id, exercise_group_id, instruction_id, "
                            + "transition_sequence, to_status, reason_code, description, "
                            + "simulation_time_seconds) VALUES (?, ?, ?, 2, 'CANCELLED', "
                            + "'QUEUE_CLEARED_BY_REPLACE', ?, 610)",
                    UUID.randomUUID().toString(), groupId, instructionId,
                    ReportQueryService.terminalDescription("QUEUE_CLEARED_BY_REPLACE"));
            return null;
        });

        // 同 instructionId + transitionSequence 唯一
        assertThrows(DataIntegrityViolationException.class, () -> transactionTemplate.execute(
                status -> jdbc.update("INSERT INTO command_report (id, exercise_group_id, "
                        + "instruction_id, transition_sequence, to_status) VALUES (?, ?, ?, 1, "
                        + "'VALIDATED')", UUID.randomUUID().toString(), groupId, instructionId)));

        // 终态稳定说明
        assertEquals("同冲突键队列被实时替代清除",
                ReportQueryService.terminalDescription("QUEUE_CLEARED_BY_REPLACE"));
        assertEquals("指令完成", ReportQueryService.terminalDescription(null));
    }

    @Test
    void givenDuplicateAdapterSourceEventAfterReconnectThenOneReportExists() {
        String groupId = newGroup();
        String aircraftId = "ac-rep-" + UUID.randomUUID();
        String sourceEventId = "engine-1:evt-" + UUID.randomUUID();

        transactionTemplate.execute(status -> {
            jdbc.update("INSERT INTO flight_report (id, exercise_group_id, aircraft_id, "
                            + "event_type, source_event_id, detail, simulation_time_seconds) "
                            + "VALUES (?, ?, ?, 'WAYPOINT_PASSED', ?, 'P47', 600)",
                    UUID.randomUUID().toString(), groupId, aircraftId, sourceEventId);
            return null;
        });
        // 重连后同一 sourceEventId 再插入 → 唯一约束拒绝（去重）
        assertThrows(DataIntegrityViolationException.class, () -> transactionTemplate.execute(
                status -> jdbc.update("INSERT INTO flight_report (id, exercise_group_id, "
                        + "aircraft_id, event_type, source_event_id, detail, "
                        + "simulation_time_seconds) VALUES (?, ?, ?, 'WAYPOINT_PASSED', ?, "
                        + "'P47', 600)", UUID.randomUUID().toString(), groupId, aircraftId,
                        sourceEventId)));

        // 事件类型白名单
        ReportQueryService.requireKnownFlightEventType("LANDED");
        assertThrows(org.bluesky.training.common.V2DomainException.class,
                () -> ReportQueryService.requireKnownFlightEventType("UFO_SEEN"));
        // 非法枚举写库被 CHECK 拒绝
        assertThrows(DataIntegrityViolationException.class, () -> transactionTemplate.execute(
                status -> jdbc.update("INSERT INTO flight_report (id, exercise_group_id, "
                        + "aircraft_id, event_type, source_event_id) VALUES (?, ?, ?, "
                        + "'CRASHED', 'src-x')", UUID.randomUUID().toString(), groupId,
                        aircraftId)));
    }

    @Test
    void givenSortedReportsWhenPagedByCursorThenNoDuplicatesNoGaps() {
        // 纯逻辑：仿真时间+ID 倒序稳定游标
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 5; i >= 1; i--) { // 已倒序
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", "rep-" + i);
            row.put("simulationTimeSeconds", (double) (i * 100));
            rows.add(row);
        }
        List<Map<String, Object>> page1 = ReportQueryService.applyCursor(rows, null, 2);
        assertEquals("rep-5", page1.get(0).get("id"));
        assertEquals("rep-4", page1.get(1).get("id"));

        String cursor = ReportQueryService.rowKey(page1.get(1));
        List<Map<String, Object>> page2 = ReportQueryService.applyCursor(rows, cursor, 2);
        assertEquals("rep-3", page2.get(0).get("id"));
        assertEquals("rep-2", page2.get(1).get("id"));

        List<Map<String, Object>> page3 = ReportQueryService.applyCursor(rows,
                ReportQueryService.rowKey(page2.get(1)), 2);
        assertEquals(1, page3.size());
        assertEquals("rep-1", page3.get(0).get("id"));
        // 同仿真时间按 ID 定序（详细设计 9.2）
        Map<String, Object> tie1 = new LinkedHashMap<>();
        tie1.put("id", "b");
        tie1.put("simulationTimeSeconds", 500.0);
        Map<String, Object> tie2 = new LinkedHashMap<>();
        tie2.put("id", "a");
        tie2.put("simulationTimeSeconds", 500.0);
        List<Map<String, Object>> ties = new ArrayList<>();
        ties.add(tie1);
        ties.add(tie2);
        assertEquals("b", ReportQueryService.applyCursor(ties, null, 1).get(0).get("id"));
    }

    @Test
    void givenScriptsWhenAcknowledgedTwiceThenOriginalTimeReturned() {
        String groupId = newGroup();
        String scriptId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO exercise_script_item (id, exercise_group_id, "
                + "target_terminal_ids, trigger_simulation_time_seconds, severity, ack_required, "
                + "content) VALUES (?, ?, 'PP-01', 900, 'CRITICAL', 1, '右发失效检查单')",
                scriptId, groupId);

        // 第一次确认
        transactionTemplate.execute(status -> {
            jdbc.update("UPDATE exercise_script_item SET status = 'ACKNOWLEDGED', "
                    + "acknowledged_at = CURRENT_TIMESTAMP(3), acknowledged_by = 'PP-01', "
                    + "revision = revision + 1 WHERE id = ?", scriptId);
            return null;
        });
        Timestamp first = jdbc.queryForObject(
                "SELECT acknowledged_at FROM exercise_script_item WHERE id = ?",
                Timestamp.class, scriptId);

        // 重复确认 → 幂等返回原时间（详细设计 9.6）
        Map<String, Object> replayOutcome = ReportQueryService.acknowledgeOutcome(true, first,
                "PP-01");
        assertEquals(Boolean.TRUE, replayOutcome.get("replayed"));
        assertEquals(first, replayOutcome.get("acknowledgedAt"));
        // 未知 severity/状态被 CHECK 拒绝
        assertThrows(DataIntegrityViolationException.class, () -> transactionTemplate.execute(
                status -> jdbc.update("INSERT INTO exercise_script_item (id, exercise_group_id, "
                        + "target_terminal_ids, trigger_simulation_time_seconds, severity, "
                        + "content) VALUES (?, ?, 'PP-01', 900, 'FATAL', 'x')",
                        UUID.randomUUID().toString(), groupId)));
    }

    @Test
    void givenMessagesWhenSoftDeletedThenOnlyTargetTerminalAffected() {
        String groupId = newGroup();
        String messageId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO terminal_message (id, exercise_group_id, target_terminal_id, "
                        + "sender_source, body) VALUES (?, ?, 'PP-01', 'ORCHESTRATOR', "
                        + "'训练将在 5 分钟后结束')", messageId, groupId);
        String otherId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO terminal_message (id, exercise_group_id, target_terminal_id, "
                        + "sender_source, body) VALUES (?, ?, 'PP-02', 'ORCHESTRATOR', "
                        + "'同组另一席消息')", otherId, groupId);

        // 终端级软删除：只影响 PP-01
        transactionTemplate.execute(status -> {
            jdbc.update("UPDATE terminal_message SET status = 'DELETED', deleted_at = "
                    + "CURRENT_TIMESTAMP(3), revision = revision + 1 WHERE id = ? AND "
                    + "target_terminal_id = 'PP-01'", messageId);
            return null;
        });
        assertEquals("DELETED", jdbc.queryForObject(
                "SELECT status FROM terminal_message WHERE id = ?", String.class, messageId));
        assertEquals("UNREAD", jdbc.queryForObject(
                "SELECT status FROM terminal_message WHERE id = ?", String.class, otherId));
        // 审计行保留（详细设计 8.8：删除只影响目标终端显示）
        assertEquals(2L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM terminal_message WHERE exercise_group_id = ?",
                Long.class, groupId));
        assertNotEquals(null, jdbc.queryForObject(
                "SELECT deleted_at FROM terminal_message WHERE id = ?",
                Timestamp.class, messageId));
        assertTrue(jdbc.queryForObject(
                "SELECT deleted_at FROM terminal_message WHERE id = ?",
                Timestamp.class, otherId) == null);
    }
}

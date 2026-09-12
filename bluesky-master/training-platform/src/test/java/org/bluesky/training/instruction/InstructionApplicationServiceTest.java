package org.bluesky.training.instruction;

import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.common.CallerContext;
import org.bluesky.training.common.V2DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P09：指令提交/取消/查询与队列语义（详细设计 2.2 §5.4/§6.2）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class InstructionApplicationServiceTest {

    @Autowired
    private InstructionApplicationService applicationService;

    @Autowired
    private GuidanceTargetService guidanceTargetService;

    @Autowired
    private InstructionQueueService queueService;

    @Autowired
    private InstructionDispatchService dispatchService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String terminalId;
    private String aircraftId;
    private String groupId;

    private void newRunningAircraft(String groupState) {
        groupId = "group-ins-" + UUID.randomUUID();
        terminalId = "IN-S-" + System.nanoTime();
        jdbc.update("INSERT INTO exercise_group (id, name, state, simulation_time_seconds) "
                + "VALUES (?, ?, ?, 600)", groupId, "指令组", groupState);
        jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, "
                + "exercise_group_id) VALUES (?, '机长席', 'PSEUDO_PILOT', ?)", terminalId, groupId);
        aircraftId = "ac-ins-" + UUID.randomUUID();
        String callsign = "IN" + System.nanoTime() % 100000;
        jdbc.update("INSERT INTO exercise_aircraft (id, exercise_group_id, assigned_terminal_id, "
                        + "callsign, aircraft_type, wake_category, origin, destination, "
                        + "heading_degrees, altitude_feet, speed_knots, lifecycle, "
                        + "active_callsign_key) VALUES (?, ?, ?, ?, 'A320', 'M', 'ZGGG', 'ZBAA', "
                        + "20, 8000, 250, 'ACTIVE', ?)",
                aircraftId, groupId, terminalId, callsign, callsign);
        jdbc.update("INSERT INTO flight_plan (id, aircraft_id, plan_version, origin, destination, "
                        + "route_text) VALUES (?, ?, 1, 'ZGGG', 'ZBAA', 'ZGGG ZBAA')",
                UUID.randomUUID().toString(), aircraftId);
        jdbc.update("INSERT INTO aircraft_assignment (id, aircraft_id, terminal_id, current_key) "
                + "VALUES (?, ?, ?, ?)", UUID.randomUUID().toString(), aircraftId,
                terminalId, aircraftId);
    }

    private CallerContext caller() {
        return CallerContext.terminal(terminalId, groupId, "digest");
    }

    private Map<String, Object> command(String type, String scheduling) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("scheduling", scheduling);
        Map<String,Object> parameters=new LinkedHashMap<>();
        if("HDG".equals(type))parameters.put("magneticHeadingDeg",90);
        if("ALT".equals(type))parameters.put("altitudeFtMsl",9000);
        if("SPD".equals(type))parameters.put("indicatedAirspeedKt",240);
        payload.put("parameters",parameters);
        payload.put("aircraftRevision", 1);
        return payload;
    }

    /** 工作台输入框提交形式：仅 text（CANCEL/取消 文本命令同样走此通道）。 */
    private Map<String, Object> textCommand(String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", text);
        payload.put("aircraftRevision", 1);
        return payload;
    }

    @Test
    void givenRunningGroupWhenAdaptersCommandSubmittedThenDispatching() {
        newRunningAircraft("RUNNING");

        Map<String, Object> instruction = transactionTemplate.execute(status ->
                applicationService.submit(caller(), aircraftId, command("HDG", "REPLACE")));

        assertEquals("DISPATCHING", instruction.get("status"));
        assertEquals("LATERAL", instruction.get("control_channel"));
        assertEquals("LATERAL:" + aircraftId, instruction.get("conflict_key"));
        assertTrue(((List<?>) instruction.get("blockingReasons")).isEmpty());
    }

    @Test
    void givenPausedGroupWhenSubmittedThenBlockedWithTrainingPaused() {
        newRunningAircraft("PAUSED");

        Map<String, Object> instruction = transactionTemplate.execute(status ->
                applicationService.submit(caller(), aircraftId, command("ALT", "REPLACE")));

        assertEquals("BLOCKED", instruction.get("status"));
        assertEquals(java.util.Arrays.asList("TRAINING_PAUSED"),
                instruction.get("blockingReasons"), "暂停接收并加 TRAINING_PAUSED 阻塞");
    }

    @Test
    void givenAdapterlessCommandWhenSubmittedThenBusinessFieldsAppliedAndCompleted() {
        // 评审 P0-8：纯业务字段指令同事务写回业务字段并直达 COMPLETED
        newRunningAircraft("RUNNING");
        Map<String, Object> command = command("SQK", "REPLACE");
        ((Map<String, Object>) command.get("parameters")).put("squawk", "7600");

        Map<String, Object> instruction = transactionTemplate.execute(status ->
                applicationService.submit(caller(), aircraftId, command));

        assertEquals("COMPLETED", instruction.get("status"),
                "纯业务字段指令同事务完成（详细设计 5.4）");
        assertEquals("7600", jdbc.queryForObject(
                "SELECT current_squawk FROM exercise_aircraft WHERE id = ?",
                String.class, aircraftId), "SQK 必须写回 current_squawk");
    }

    @Test
    void givenAfterCompletionChainWhenPredecessorCompletesThenSuccessorDispatches() {
        newRunningAircraft("RUNNING");
        // 前置提交并经 Adapter 确认进入 EXECUTING（REPLACE 语义在确认时清空等待队列，
        // 因此后继必须在前置确认之后提交，详细设计 6.2/6.3.2）
        Map<String, Object> first = transactionTemplate.execute(status ->
                applicationService.submit(caller(), aircraftId, command("HDG", "REPLACE")));
        transactionTemplate.execute(status -> {
            dispatchService.onAdapterApplied(String.valueOf(first.get("id")));
            return null;
        });
        assertEquals("EXECUTING", jdbc.queryForObject(
                "SELECT status FROM aircraft_instruction WHERE id = ?", String.class,
                first.get("id")));

        // 前置在途（EXECUTING）时提交后继 → 排队阻塞
        Map<String, Object> second = transactionTemplate.execute(status ->
                applicationService.submit(caller(), aircraftId, command("HDG", "AFTER_COMPLETION")));
        assertEquals("BLOCKED", second.get("status"));
        assertEquals(java.util.Arrays.asList("PREDECESSOR_ACTIVE"),
                second.get("blockingReasons"));
        assertEquals(first.get("id"), second.get("predecessor_id"));

        // 前置完成后释放后继：必须进入 DISPATCHING 并真实派发（评审 P0-9/E13）
        jdbc.update("UPDATE aircraft_instruction SET status = 'COMPLETED' WHERE id = ?",
                first.get("id"));
        transactionTemplate.execute(status -> {
            dispatchService.finalizeTerminal(String.valueOf(first.get("id")), "COMPLETED");
            return null;
        });
        assertEquals("DISPATCHING", jdbc.queryForObject(
                "SELECT status FROM aircraft_instruction WHERE id = ?", String.class,
                second.get("id")), "前置 COMPLETED 后后继必须进入派发");
        assertEquals(2L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'INSTRUCTION_APPLY' "
                        + "AND exercise_group_id = ?", Long.class, groupId),
                "后继派发必须产生第二条 INSTRUCTION_APPLY");
    }

    @Test
    void givenSameIdempotencyKeyWhenResubmittedThenOriginalReturned() {
        newRunningAircraft("RUNNING");
        Map<String, Object> command = command("SPD", "REPLACE");
        command.put("idempotencyKey", "stable-key-1");

        Map<String, Object> first = transactionTemplate.execute(status ->
                applicationService.submit(caller(), aircraftId, command));
        Map<String, Object> replay = transactionTemplate.execute(status ->
                applicationService.submit(caller(), aircraftId, command));

        assertEquals(first.get("id"), replay.get("id"), "同幂等键返回原指令");
        assertEquals(Boolean.TRUE, replay.get("replayed"));
        assertEquals(1L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM aircraft_instruction WHERE exercise_aircraft_id = ? "
                        + "AND instruction_type = 'SPD'", Long.class, aircraftId));
    }

    @Test
    void givenSameIdempotencyKeyWhenBodyDiffersThenRejectedWith409() {
        newRunningAircraft("RUNNING");
        Map<String, Object> command = command("SPD", "REPLACE");
        command.put("idempotencyKey", "stable-key-2");
        transactionTemplate.execute(status ->
                applicationService.submit(caller(), aircraftId, command));

        // 同键不同体（仅调度方式不同也是不同请求，详细设计 9.1）
        Map<String, Object> changed = command("SPD", "AFTER_COMPLETION");
        changed.put("idempotencyKey", "stable-key-2");
        V2DomainException failure = assertThrows(V2DomainException.class, () ->
                transactionTemplate.execute(status ->
                        applicationService.submit(caller(), aircraftId, changed)));

        assertEquals("IDEMPOTENCY_KEY_REUSED", failure.code());
        assertEquals(409, failure.httpStatus());
    }

    @Test
    void givenBlockedInstructionWhenCancelledThenCancelledDirectly() {
        newRunningAircraft("PAUSED");
        Map<String, Object> instruction = transactionTemplate.execute(status ->
                applicationService.submit(caller(), aircraftId, command("HDG", "REPLACE")));

        Map<String, Object> cancelled = transactionTemplate.execute(status ->
                applicationService.cancel(caller(), String.valueOf(instruction.get("id"))));

        assertEquals("CANCELLED", cancelled.get("status"));
    }

    @Test
    void givenExecutingInstructionWhenCancelTextSubmittedThenLatestActiveCancelled() {
        // 工作台文本命令 cancel/取消：不创建新指令，取消该机最新活动指令并返回被取消行
        newRunningAircraft("RUNNING");
        Map<String, Object> first = transactionTemplate.execute(status ->
                applicationService.submit(caller(), aircraftId, command("HDG", "REPLACE")));
        transactionTemplate.execute(status -> {
            dispatchService.onAdapterApplied(String.valueOf(first.get("id")));
            return null;
        });

        Map<String, Object> cancelled = transactionTemplate.execute(status ->
                applicationService.submit(caller(), aircraftId, textCommand("cancel")));

        assertEquals(first.get("id"), cancelled.get("id"), "取消目标必须是最新活动指令");
        assertEquals("EXECUTING", cancelled.get("status"));
        assertEquals(true,cancelled.get("cancelPending"));
        dispatchService.onAdapterCancelled(String.valueOf(first.get("id")));
        assertEquals("CANCELLED",applicationService.get(String.valueOf(first.get("id"))).get("status"));
    }

    @Test
    void givenNoActiveInstructionWhenCancelTextSubmittedThen409() {
        newRunningAircraft("RUNNING");

        V2DomainException failure = assertThrows(V2DomainException.class, () ->
                transactionTemplate.execute(status ->
                        applicationService.submit(caller(), aircraftId, textCommand("取消"))));

        assertEquals("TRAINING_STATE_INVALID", failure.code());
        assertEquals(409, failure.httpStatus());
    }

    @Test
    void givenDispatchingInstructionWhenCancelTextSubmittedThenWaitForNativeAck() {
        newRunningAircraft("RUNNING");
        Map<String,Object> command = transactionTemplate.execute(status ->
                applicationService.submit(caller(), aircraftId, command("HDG", "REPLACE")));
        Map<String,Object> cancelling = transactionTemplate.execute(status ->
                applicationService.submit(caller(), aircraftId, textCommand("CANCEL")));
        assertEquals("DISPATCHING", cancelling.get("status"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE exercise_group_id=? AND event_type='INSTRUCTION_CANCEL'", Integer.class,groupId));
        assertEquals("FAILED",jdbc.queryForObject("SELECT status FROM outbox_event WHERE idempotency_key=?",String.class,"instruction:"+command.get("id")));
    }

    @Test
    void givenForeignTerminalOrBadModeWhenSubmittedThenRejected() {
        newRunningAircraft("RUNNING");
        CallerContext foreign = CallerContext.terminal("PP-X", groupId, "digest");

        V2DomainException notOwner = assertThrows(V2DomainException.class,
                () -> transactionTemplate.execute(status ->
                        applicationService.submit(foreign, aircraftId, command("HDG", "REPLACE"))));
        assertEquals("AIRCRAFT_NOT_ASSIGNED", notOwner.code());

        V2DomainException badMode = assertThrows(V2DomainException.class,
                () -> transactionTemplate.execute(status ->
                        applicationService.submit(caller(), aircraftId,
                                command("HDG", "APPEND"))));
        assertEquals("INVALID_INSTRUCTION", badMode.code(),
                "不提供第三种调度语义（APPEND 已删除）");

        V2DomainException route = assertThrows(V2DomainException.class,
                () -> transactionTemplate.execute(status ->
                        applicationService.submit(caller(), aircraftId,
                                command("FRE", "REPLACE"))));
        assertEquals("INVALID_INSTRUCTION", route.code(), "FRE 路由到移交，不创建指令");
    }

    @Test
    void givenSpecialCommandWithoutPublishedProfileWhenSubmittedThenFeatureNotConfigured() {
        // §7.8 规则 2：无 PUBLISHED profile 时 422，不得建指令、不得下发 Adapter
        newRunningAircraft("RUNNING");
        Map<String, Object> textId = new LinkedHashMap<>();
        textId.put("text", "ID");
        textId.put("aircraftRevision", 1);

        V2DomainException rejected = assertThrows(V2DomainException.class,
                () -> transactionTemplate.execute(status ->
                        applicationService.submit(caller(), aircraftId, textId)));
        assertEquals("FEATURE_PROFILE_NOT_CONFIGURED", rejected.code());
        assertEquals(422, rejected.httpStatus());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM aircraft_instruction WHERE exercise_aircraft_id = ?",
                Integer.class, aircraftId), "门控失败不得创建指令");
        assertEquals(0, jdbc.queryForObject(
                "SELECT transponder_ident_active FROM exercise_aircraft WHERE id = ?",
                Integer.class, aircraftId), "ID 绝不回退为 IDENT");

        V2DomainException missingMode = assertThrows(V2DomainException.class,
                () -> transactionTemplate.execute(status ->
                        applicationService.submit(caller(), aircraftId,
                                command("DECOMP", "REPLACE"))));
        assertEquals("INVALID_INSTRUCTION", missingMode.code(),
                "DECOMP 必须显式携带 N/S/CLR");
    }

    @Test
    void givenPublishedProfileWhenSpecialCommandSubmittedThenDispatching() {
        // §7.8 规则 4：配置完整时按 profile 声明通道调度
        newRunningAircraft("RUNNING");
        String profileId = UUID.randomUUID().toString();
        Map<String, Object> instruction;
        try {
            jdbc.update("INSERT INTO special_operation_profile (id, operation_type, mode_code, "
                            + "status, affected_channels, adapter_action, duration_seconds, "
                            + "recovery_policy, override_policy, parameters_schema_version) "
                            + "VALUES (?, 'ID', 'DEFAULT', 'PUBLISHED', 'VERTICAL', "
                            + "'SPECIAL_MARK_APPLY', 300, 'RESTORE_PREVIOUS_GUIDANCE', "
                            + "'ALL_OR_NOTHING', 'special-profile/1')",
                    profileId);
            Map<String, Object> textId = new LinkedHashMap<>();
            textId.put("text", "ID");
            textId.put("aircraftRevision", 1);

            V2DomainException unsupported=assertThrows(V2DomainException.class,()->applicationService.submit(caller(),aircraftId,textId));
            assertEquals("FEATURE_NOT_SUPPORTED",unsupported.code());
            instruction=java.util.Collections.emptyMap();
        } finally {
            jdbc.update("DELETE FROM special_operation_profile WHERE id = ?", profileId);
        }

        assertTrue(instruction.isEmpty(),"没有引擎能力时不得创建指令");
    }

    @Test
    void givenGuidanceTargetsWhenInstructionCompletedThenTargetStaysActiveUntilSuperseded() {
        newRunningAircraft("RUNNING");
        Map<String, Object> instruction = transactionTemplate.execute(status ->
                applicationService.submit(caller(), aircraftId, command("HDG", "REPLACE")));
        String instructionId = String.valueOf(instruction.get("id"));

        // 派发链路自动创建 PENDING_APPLY 目标；Adapter 确认后激活（评审 P0-6）
        List<Map<String, Object>> pending = guidanceTargetService.targetsOf(instructionId);
        assertEquals(1, pending.size(), "派发必须建立引导目标");
        String targetId = String.valueOf(pending.get(0).get("id"));
        transactionTemplate.execute(status -> {
            dispatchService.onAdapterApplied(instructionId);
            return null;
        });
        jdbc.update("UPDATE aircraft_instruction SET status = 'COMPLETED' WHERE id = ?",
                instructionId);

        // 指令完成，目标保持 ACTIVE（详细设计 5.4）
        assertEquals("ACTIVE", guidanceTargetService.activeTarget(aircraftId, "LATERAL")
                .get("state"));

        // 新指令覆盖：旧目标 SUPERSEDED，已完成指令不被倒改
        transactionTemplate.execute(status -> {
            guidanceTargetService.activate(
                    guidanceTargetService.createPending("new-ins", aircraftId, "LATERAL", "{}"),
                    aircraftId, "LATERAL");
            return null;
        });
        assertEquals("ACTIVE", guidanceTargetService.activeTarget(aircraftId, "LATERAL")
                .get("state"));
        List<Map<String, Object>> targets = guidanceTargetService.targetsOf(instructionId);
        assertEquals("SUPERSEDED", targets.get(0).get("state"));
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT status FROM aircraft_instruction WHERE id = ?", String.class,
                instructionId));
    }
}

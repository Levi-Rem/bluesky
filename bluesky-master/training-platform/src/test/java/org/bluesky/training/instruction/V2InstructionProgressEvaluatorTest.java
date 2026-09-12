package org.bluesky.training.instruction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.common.CallerContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P09：v2 收敛评估器（详细设计 2.2 §6.3.3/6.3.4）。
 * 状态帧驱动 EXECUTING→COMPLETED：稳定窗收敛、预算耗尽 TARGET_NOT_REACHED、
 * 命令/飞行报告写入、复合子项按父项回执判定。
 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class V2InstructionProgressEvaluatorTest {

    @Autowired
    private InstructionApplicationService applicationService;

    @Autowired
    private V2InstructionProgressEvaluator evaluator;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final ObjectMapper mapper = new ObjectMapper();

    private String terminalId;
    private String aircraftId;
    private String groupId;
    private String callsign;

    private void newRunningAircraft() {
        groupId = "group-eval-" + UUID.randomUUID();
        terminalId = "EV-" + System.nanoTime();
        jdbc.update("INSERT INTO exercise_group (id, name, state, simulation_time_seconds) "
                + "VALUES (?, ?, 'RUNNING', 0)", groupId, "评估组");
        jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, "
                + "exercise_group_id) VALUES (?, '机长席', 'PSEUDO_PILOT', ?)", terminalId, groupId);
        aircraftId = "ac-eval-" + UUID.randomUUID();
        callsign = "EV" + System.nanoTime() % 100000;
        jdbc.update("INSERT INTO exercise_aircraft (id, exercise_group_id, assigned_terminal_id, "
                        + "callsign, aircraft_type, wake_category, origin, destination, "
                        + "heading_degrees, altitude_feet, speed_knots, lifecycle, "
                        + "active_callsign_key) VALUES (?, ?, ?, ?, 'A320', 'M', 'ZGGG', 'ZBAA', "
                        + "20, 8000, 250, 'ACTIVE', ?)",
                aircraftId, groupId, terminalId, callsign, callsign);
        jdbc.update("INSERT INTO aircraft_assignment (id, aircraft_id, terminal_id, current_key) "
                + "VALUES (?, ?, ?, ?)", UUID.randomUUID().toString(), aircraftId,
                terminalId, aircraftId);
    }

    private CallerContext caller() {
        return CallerContext.terminal(terminalId, groupId, "digest");
    }

    private Map<String, Object> submitHdg(String heading) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", "HDG " + heading);
        payload.put("aircraftRevision", 1);
        return transactionTemplate.execute(status ->
                applicationService.submit(caller(), aircraftId, payload));
    }

    private String statusOf(String instructionId) {
        return jdbc.queryForObject("SELECT status FROM aircraft_instruction WHERE id = ?",
                String.class, instructionId);
    }

    @Test
    void ilsCaptureDoesNotCompleteBeforeTouchdownAndFiveSecondLowSpeedWindow() throws Exception {
        newRunningAircraft();
        String id=String.valueOf(submitHdg("090").get("id"));
        jdbc.update("UPDATE aircraft_instruction SET instruction_type='ILS',control_channel='VERTICAL',status='EXECUTING',parsed_payload='{}' WHERE id=?",id);
        String capture="{\"speedKnots\":160,\"receipts\":{\"ils\":{\"commandId\":\""+id+"\",\"lateralCaptured\":true,\"verticalCaptured\":true,\"landed\":false}}}";
        evaluator.evaluate(aircraftId,mapper.readTree(capture),100);
        evaluator.evaluate(aircraftId,mapper.readTree(capture),106);
        assertEquals("EXECUTING",statusOf(id));
        String landed=capture.replace("160","0").replace("\"landed\":false","\"landed\":true");
        evaluator.evaluate(aircraftId,mapper.readTree(landed),110);
        evaluator.evaluate(aircraftId,mapper.readTree(landed),114);
        assertEquals("EXECUTING",statusOf(id));
        evaluator.evaluate(aircraftId,mapper.readTree(landed),115);
        assertEquals("COMPLETED",statusOf(id));
    }

    @Test
    void givenConvergedHeadingWhenEvaluatedThenCompletedWithReports() throws Exception {
        newRunningAircraft();
        Map<String, Object> instruction = submitHdg("090");
        String instructionId = String.valueOf(instruction.get("id"));
        // 引导确认后进入 EXECUTING
        transactionTemplate.execute(status -> {
            new InstructionDispatchProbe(jdbc).forceExecuting(instructionId);
            return null;
        });

        String frame = "{\"headingDegrees\":90.0,\"altitudeFeet\":8000,\"speedKnots\":250,"
                + "\"mach\":0.6,\"verticalSpeedFeetPerMinute\":0}";
        evaluator.evaluate(aircraftId, mapper.readTree(frame), 100.0);
        // 稳定窗 3s：首次进入容差不立即完成
        assertEquals("EXECUTING", statusOf(instructionId), "稳定窗内不得提前完成");
        evaluator.evaluate(aircraftId, mapper.readTree(frame), 103.5);

        assertEquals("COMPLETED", statusOf(instructionId), "稳定窗满足后收敛完成");
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT to_status FROM command_report WHERE instruction_id = ?",
                String.class, instructionId), "命令报告必须记录终态");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flight_report WHERE aircraft_id = ? "
                        + "AND event_type = 'TARGET_REACHED'", Integer.class, aircraftId),
                "收敛完成必须关联 TARGET_REACHED 飞行报告");
    }

    @Test
    void givenBudgetExhaustedWhenEvaluatedThenTargetNotReached() throws Exception {
        newRunningAircraft();
        Map<String, Object> instruction = submitHdg("270");
        String instructionId = String.valueOf(instruction.get("id"));
        transactionTemplate.execute(status -> {
            new InstructionDispatchProbe(jdbc).forceExecuting(instructionId);
            return null;
        });

        // 航向始终不收敛；HDG 预算 180s
        String frame = "{\"headingDegrees\":20.0,\"altitudeFeet\":8000,\"speedKnots\":250}";
        evaluator.evaluate(aircraftId, mapper.readTree(frame), 50.0);
        assertEquals("EXECUTING", statusOf(instructionId));
        evaluator.evaluate(aircraftId, mapper.readTree(frame), 50.0 + 181.0);

        assertEquals("FAILED", statusOf(instructionId), "预算耗尽必须终结");
        assertEquals("TARGET_NOT_REACHED", jdbc.queryForObject(
                "SELECT reason_code FROM command_report WHERE instruction_id = ?",
                String.class, instructionId));
    }

    @Test
    void givenTakeoffChildWhenParentReceiptArrivesThenChildCompletes() throws Exception {
        newRunningAircraft();
        Map<String, Object> payload = new LinkedHashMap<>();
        org.bluesky.training.testsupport.NavigationFixture.installRunways(jdbc,groupId);
        payload.put("text", "TAKEOFF 02L LEVEL 9000");
        payload.put("aircraftRevision", 1);
        Map<String, Object> parent = transactionTemplate.execute(status ->
                applicationService.submit(caller(), aircraftId, payload));
        String parentId = String.valueOf(parent.get("id"));
        transactionTemplate.execute(status -> {
            new InstructionDispatchProbe(jdbc).forceExecutingFamily(parentId);
            return null;
        });

        String frame = "{\"headingDegrees\":21.0,\"altitudeFeet\":8900,\"speedKnots\":250,"
                + "\"receipts\":{\"takeoff\":{\"commandId\":\"" + parentId + "\","
                + "\"airborne\":true,\"targetHeadingDeg\":21.0,\"targetSpeedKt\":250.0}}}";
        evaluator.evaluate(aircraftId, mapper.readTree(frame), 100.0);
        evaluator.evaluate(aircraftId, mapper.readTree(frame), 111.0);

        // 子项按父项回执收敛（评审 P0-12）；全部子项完成后父项聚合 COMPLETED
        assertEquals("COMPLETED", statusOf(parentId),
                "三通道子项收敛后父项必须聚合完成");
        assertNotNull(jdbc.queryForObject(
                "SELECT to_status FROM command_report WHERE instruction_id = ?",
                String.class, parentId));
    }

    /** 测试助手：跳过桥/Outbox 直接把指令置为 EXECUTING（模拟 Adapter 已确认）。 */
    private static final class InstructionDispatchProbe {
        private final JdbcTemplate jdbc;

        InstructionDispatchProbe(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        void forceExecuting(String instructionId) {
            jdbc.update("UPDATE aircraft_instruction SET status = 'EXECUTING' WHERE id = ?",
                    instructionId);
        }

        void forceExecutingFamily(String parentId) {
            forceExecuting(parentId);
            jdbc.update("UPDATE aircraft_instruction SET status = 'EXECUTING' "
                    + "WHERE id IN (SELECT child_id FROM composite_instruction_child "
                    + "WHERE parent_id = ?)", parentId);
        }
    }
}

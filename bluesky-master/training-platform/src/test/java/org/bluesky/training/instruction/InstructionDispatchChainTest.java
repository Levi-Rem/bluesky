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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第三批指令执行链路行为测试（评审 P0-6～P0-12；详细设计 6.1/6.2/6.3/7.6）。
 * 派发占位与 Outbox、REPLACE 语义、业务字段写回、阻塞解除、文本解析入口、
 * 航段冲突键、复合指令聚合。
 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class InstructionDispatchChainTest {
    @Autowired private org.bluesky.training.aircraft.FlightPlanService flightPlans;

    @Test void acknowledgedPlanChangesPreserveMetadataAndPriorConstraints() {
        newRunningAircraft();
        flightPlans.createVersion(aircraftId,"ZGGG","ZBAA","3421","C",12000,250,java.util.Arrays.asList("P47","ZBAA"));
        flightPlans.applyInstructionVersion(aircraftId,"P_LEVEL",mapOf("legPoint","P47","altitudeFtMsl",9000));
        flightPlans.applyInstructionVersion(aircraftId,"P_TIME",mapOf("legPoint","P47","targetTimeSeconds",1200));
        List<Map<String,Object>> versions=flightPlans.listVersions(aircraftId);
        assertEquals(4,versions.size());
        Map<String,Object> latest=versions.get(0);
        assertEquals("ZGGG",latest.get("origin"));assertEquals("ZBAA",latest.get("destination"));
        assertEquals("3421",latest.get("plannedSquawk"));assertEquals("C",latest.get("ssrMode"));
        Map<String,Object> leg=((List<Map<String,Object>>)latest.get("legs")).get(0);
        assertEquals(9000,((Number)leg.get("altitudeConstraintFt")).intValue());
        assertEquals(1200,((Number)leg.get("targetTimeSeconds")).intValue());
        Map<String,Object> oldLeg=((List<Map<String,Object>>)versions.get(2).get("legs")).get(0);
        assertEquals(null,oldLeg.get("altitudeConstraintFt"));
    }

    @Autowired
    private InstructionApplicationService applicationService;

    @Autowired
    private InstructionDispatchService dispatchService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String terminalId;
    private String aircraftId;
    private String groupId;

    private void newRunningAircraft() {
        newRunningAircraft("RUNNING");
    }

    private void newRunningAircraft(String groupState) {
        groupId = "group-disp-" + UUID.randomUUID();
        terminalId = "DP-" + System.nanoTime();
        jdbc.update("INSERT INTO exercise_group (id, name, state, simulation_time_seconds) "
                + "VALUES (?, ?, ?, 600)", groupId, "派发链路组", groupState);
        jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, "
                + "exercise_group_id) VALUES (?, '机长席', 'PSEUDO_PILOT', ?)", terminalId, groupId);
        aircraftId = "ac-disp-" + UUID.randomUUID();
        String callsign = "DP" + System.nanoTime() % 100000;
        jdbc.update("INSERT INTO exercise_aircraft (id, exercise_group_id, assigned_terminal_id, "
                        + "callsign, aircraft_type, wake_category, origin, destination, "
                        + "heading_degrees, altitude_feet, speed_knots, lifecycle, "
                        + "active_callsign_key, planned_squawk, current_squawk, ssr_mode) "
                        + "VALUES (?, ?, ?, ?, 'A320', 'M', 'ZGGG', 'ZBAA', 20, 8000, 250, "
                        + "'ACTIVE', ?, '3421', '3421', 'C')",
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

    private Map<String, Object> submit(String type, Map<String, Object> parameters,
                                       String scheduling) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("scheduling", scheduling);
        payload.put("parameters", parameters == null
                ? new LinkedHashMap<String, Object>() : parameters);
        return transactionTemplate.execute(status ->
                applicationService.submit(caller(), aircraftId, payload));
    }

    private String statusOf(String instructionId) {
        return jdbc.queryForObject("SELECT status FROM aircraft_instruction WHERE id = ?",
                String.class, instructionId);
    }

    // ---------------------------------------------------------------- P0-6

    @Test
    void givenAdapterRequiredCommandWhenSubmittedThenSlotGuidanceAndOutboxCreated() {
        newRunningAircraft();

        Map<String, Object> instruction = submit("HDG",
                mapOf("magneticHeadingDeg", 90), "REPLACE");
        String instructionId = String.valueOf(instruction.get("id"));

        assertEquals("DISPATCHING", instruction.get("status"));
        assertEquals(instructionId, jdbc.queryForObject(
                "SELECT instruction_id FROM dispatch_slot WHERE aircraft_id = ? "
                        + "AND conflict_key = ?", String.class, aircraftId,
                "LATERAL:" + aircraftId), "派发必须占住在途占位");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM guidance_target WHERE instruction_id = ? "
                        + "AND state = 'PENDING_APPLY'", Integer.class, instructionId),
                "派发必须建立 PENDING_APPLY 引导目标");
        Map<String, Object> outbox = jdbc.queryForMap(
                "SELECT idempotency_key, engine_instance_id, request_id FROM outbox_event "
                        + "WHERE event_type = 'INSTRUCTION_APPLY' AND exercise_group_id = ? "
                        + "ORDER BY created_at DESC", groupId);
        assertEquals("instruction:" + instructionId, outbox.get("idempotency_key"),
                "Outbox 幂等键 = instructionId（详细设计 6.3.5）");
        assertNotNull(outbox.get("request_id"), "Outbox 必须 routeTo 请求标识");
    }

    @Test
    void givenAdapterRejectsWhenHandledThenFailedAndSlotReleased() {
        newRunningAircraft();
        Map<String, Object> instruction = submit("HDG", mapOf("magneticHeadingDeg", 90), "REPLACE");
        String instructionId = String.valueOf(instruction.get("id"));

        transactionTemplate.execute(status -> {
            dispatchService.onAdapterRejected(instructionId, "ENGINE_REJECTED", "性能超限");
            return null;
        });

        assertEquals("FAILED", statusOf(instructionId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dispatch_slot WHERE aircraft_id = ?", Integer.class,
                aircraftId), "拒绝后必须释放占位");
        assertEquals("FAILED", jdbc.queryForObject(
                "SELECT state FROM guidance_target WHERE instruction_id = ?", String.class,
                instructionId), "引导目标随失败终结");
    }

    @Test
    void givenAckTimeoutWhenWatchdogFiresThenTimedOut() {
        newRunningAircraft();
        Map<String, Object> instruction = submit("HDG", mapOf("magneticHeadingDeg", 90), "REPLACE");
        String instructionId = String.valueOf(instruction.get("id"));

        transactionTemplate.execute(status -> {
            dispatchService.timeout(instructionId);
            return null;
        });

        assertEquals("TIMED_OUT", statusOf(instructionId));
        assertEquals("ADAPTER_ACK_TIMEOUT", jdbc.queryForObject(
                "SELECT failure_code FROM aircraft_instruction WHERE id = ?", String.class,
                instructionId), "技术确认窗口耗尽的终结原因");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM dispatch_slot WHERE aircraft_id = ?", Integer.class,
                aircraftId), "超时后必须释放占位");
    }

    // ---------------------------------------------------------------- P0-7

    @Test
    void givenReplacedLateralTargetWhenNewOneAppliesThenOldReplacedAndQueueCleared() {
        newRunningAircraft();
        Map<String, Object> first = submit("HDG", mapOf("magneticHeadingDeg", 90), "REPLACE");
        String firstId = String.valueOf(first.get("id"));
        transactionTemplate.execute(status -> {
            dispatchService.onAdapterApplied(firstId);
            return null;
        });
        assertEquals("EXECUTING", statusOf(firstId));

        // 等待队列中的 AFTER_COMPLETION 后继（REPLACE 生效时整体清除，详细设计 6.2）
        Map<String, Object> waiting = submit("HDG", mapOf("magneticHeadingDeg", 120),
                "AFTER_COMPLETION");
        assertEquals("BLOCKED", statusOf(String.valueOf(waiting.get("id"))));

        // 新 REPLACE 指令确认后：旧 EXECUTING → REPLACED，等待队列 → CANCELLED
        Map<String, Object> second = submit("HDG", mapOf("magneticHeadingDeg", 180), "REPLACE");
        String secondId = String.valueOf(second.get("id"));
        transactionTemplate.execute(status -> {
            dispatchService.onAdapterApplied(secondId);
            return null;
        });

        assertEquals("REPLACED", statusOf(firstId), "同一航空器只有一个活动横向目标（6.1）");
        assertEquals("CANCELLED", statusOf(String.valueOf(waiting.get("id"))));
        assertEquals("QUEUE_CLEARED_BY_REPLACE", jdbc.queryForObject(
                "SELECT failure_code FROM aircraft_instruction WHERE id = ?", String.class,
                waiting.get("id")));
        assertEquals("EXECUTING", statusOf(secondId));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT state FROM guidance_target WHERE instruction_id = ?", String.class,
                secondId), "新目标激活");
        assertEquals("SUPERSEDED", jdbc.queryForObject(
                "SELECT state FROM guidance_target WHERE instruction_id = ?", String.class,
                firstId), "旧目标被替代（指令 COMPLETED 语义不受影响）");
    }

    // ---------------------------------------------------------------- P0-8

    @Test
    void givenDuplicateSquawkWhenAppliedThenWarningReturned() {
        newRunningAircraft();
        // 同组另一架活动航空器已用 7600
        String otherId = "ac-other-" + UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_aircraft (id, exercise_group_id, assigned_terminal_id, "
                        + "callsign, aircraft_type, wake_category, origin, destination, "
                        + "heading_degrees, altitude_feet, speed_knots, lifecycle, "
                        + "active_callsign_key, current_squawk, ssr_mode) "
                        + "VALUES (?, ?, ?, 'DPOT1', 'A320', 'M', 'ZGGG', 'ZBAA', 20, 8000, 250, "
                        + "'ACTIVE', 'DPOT1', '7600', 'C')",
                otherId, groupId, terminalId);

        Map<String, Object> instruction = submit("SQK", mapOf("squawk", "7600"), "REPLACE");

        assertEquals("COMPLETED", instruction.get("status"), "业务字段指令同事务完成");
        assertTrue(((List<?>) instruction.get("warnings")).contains("DUPLICATE_SQUAWK"),
                "重复应答机编码必须产生警告（详细设计 7.6）");
        assertEquals("7600", jdbc.queryForObject(
                "SELECT current_squawk FROM exercise_aircraft WHERE id = ?",
                String.class, aircraftId));
    }

    @Test
    void givenIdentWhenAppliedThenActivatedWithSimulationExpiry() {
        newRunningAircraft();

        Map<String, Object> instruction = submit("IDENT", mapOf("durationSeconds", 20),
                "REPLACE");

        assertEquals("COMPLETED", instruction.get("status"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT transponder_ident_active FROM exercise_aircraft WHERE id = ?",
                Integer.class, aircraftId), "IDENT 必须激活识别标记");
        assertEquals(620.0, jdbc.queryForObject(
                "SELECT transponder_ident_expires_at FROM exercise_aircraft WHERE id = ?",
                java.math.BigDecimal.class, aircraftId).doubleValue(),
                "到期时刻 = 组仿真时间(600) + 持续时间(20)，由仿真时钟清除");
    }

    @Test
    void givenNormalRestoreWhenPlannedSquawkMissingThenRolledBack() {
        newRunningAircraft();
        jdbc.update("UPDATE exercise_aircraft SET planned_squawk = NULL WHERE id = ?",
                aircraftId);

        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> submit("NML", null, "REPLACE"));
        assertEquals("INVALID_INSTRUCTION", failure.code());
        assertEquals(0L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM aircraft_instruction WHERE exercise_aircraft_id = ? "
                        + "AND instruction_type = 'NML'", Long.class, aircraftId),
                "NML 校验失败必须整体回滚（指令不留残行）");
    }

    // ---------------------------------------------------------------- P0-9

    @Test
    void givenTrainingPausedBlockersWhenGroupResumesThenAllDispatched() {
        newRunningAircraft("PAUSED");
        Map<String, Object> blocked = submit("HDG", mapOf("magneticHeadingDeg", 90), "REPLACE");
        assertEquals("BLOCKED", statusOf(String.valueOf(blocked.get("id"))));

        transactionTemplate.execute(status -> {
            dispatchService.releaseTrainingPausedForGroup(groupId);
            return null;
        });

        assertEquals("DISPATCHING", statusOf(String.valueOf(blocked.get("id"))),
                "训练恢复后 TRAINING_PAUSED 阻塞必须解除并进入派发（详细设计 4.2.6）");
        assertEquals(1L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'INSTRUCTION_APPLY' "
                        + "AND exercise_group_id = ?", Long.class, groupId));
    }

    // ---------------------------------------------------------------- P0-10

    @Test
    void givenTextCommandWhenSubmittedThenParsedAndDispatched() {
        newRunningAircraft();

        Map<String, Object> byText = transactionTemplate.execute(status ->
                applicationService.submit(caller(), aircraftId,
                        textPayload("HDG 090")));
        assertEquals("HDG", byText.get("instruction_type"), "文本指令必须经解析器目录");
        assertEquals("DISPATCHING", byText.get("status"));

        // 厂商别名 LVL → ALT（详细设计 7.10）
        Map<String, Object> aliased = transactionTemplate.execute(status ->
                applicationService.submit(caller(), aircraftId,
                        textPayload("LVL FL200")));
        assertEquals("ALT", aliased.get("instruction_type"));

        // FRE/未知命令拦截（路由不进飞行控制通道）
        V2DomainException handover = assertThrows(V2DomainException.class,
                () -> transactionTemplate.execute(status ->
                        applicationService.submit(caller(), aircraftId, textPayload("FRE 118.1"))));
        assertEquals("INVALID_INSTRUCTION", handover.code());
        V2DomainException unknown = assertThrows(V2DomainException.class,
                () -> transactionTemplate.execute(status ->
                        applicationService.submit(caller(), aircraftId, textPayload("XYZ 1"))));
        assertEquals("INVALID_INSTRUCTION", unknown.code());
    }

    // ---------------------------------------------------------------- P0-11

    @Test
    void givenPlanConstraintWhenSubmittedThenPerLegConflictKeyAndAdapterDispatch() {
        newRunningAircraft();
        String legId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO flight_plan_leg (id, flight_plan_id, sequence_number, "
                        + "point_code) SELECT ?, id, 1, 'LMN' FROM flight_plan "
                        + "WHERE aircraft_id = ? AND plan_version = 1",
                legId, aircraftId);

        Map<String, Object> instruction = submit("P_LEVEL",
                mapOf("legPoint", "LMN", "altitudeFtMsl", 9000,
                        "conflictKeyTemplate", "BUSINESS_FIELD:LEG:{legId}:LEVEL"),
                "REPLACE");

        assertEquals("BUSINESS_FIELD:LEG:" + legId + ":LEVEL", instruction.get("conflict_key"),
                "航段约束冲突键按 legId 独立（评审 P0-11）");
        assertEquals("DISPATCHING", instruction.get("status"),
                "P_LEVEL 必须经 Adapter 派发（写计划新版本），不是纯业务字段");

        // 非当前计划航路点：明确拒绝
        V2DomainException unknownLeg = assertThrows(V2DomainException.class,
                () -> submit("P_LEVEL", mapOf("legPoint", "NOWHERE", "altitudeFtMsl", 9000,
                        "conflictKeyTemplate", "BUSINESS_FIELD:LEG:{legId}:LEVEL"), "REPLACE"));
        assertEquals("REFERENCE_NOT_FOUND", unknownLeg.code());
    }

    // ---------------------------------------------------------------- P0-12

    @Test
    void givenCompositeCommandWhenSubmittedThenChildrenAndSinglePayloadAggregate() {
        newRunningAircraft();

        org.bluesky.training.testsupport.NavigationFixture.installRunways(jdbc,groupId);
        Map<String, Object> parent = submit("TAKEOFF",
                mapOf("runway", "02R", "targetAltitudeFtMsl", 12000), "REPLACE");
        String parentId = String.valueOf(parent.get("id"));
        assertEquals("DISPATCHING", parent.get("status"));

        // 每个受影响通道一条子指令，各占冲突键（详细设计 6.1 SPECIAL 行）
        List<Map<String, Object>> children = jdbc.queryForList(
                "SELECT c.channel AS channel, i.id AS childId, i.status AS status, "
                        + "i.conflict_key AS conflictKey FROM composite_instruction_child c "
                        + "JOIN aircraft_instruction i ON i.id = c.child_id "
                        + "WHERE c.parent_id = ?", parentId);
        assertEquals(3, children.size(), "TAKEOFF 影响 LATERAL/VERTICAL/SPEED 三通道");
        for (Map<String, Object> child : children) {
            assertEquals("DISPATCHING", child.get("status"), "子项随父项进入派发");
            assertEquals(String.valueOf(child.get("channel")) + ":" + aircraftId,
                    child.get("conflictKey"), "子项按通道独立占冲突键");
        }
        // 父项单次载荷携带完整 affectedChannels（不按子项重复下发）
        assertEquals(1L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'INSTRUCTION_APPLY' "
                        + "AND exercise_group_id = ?", Long.class, groupId));

        // 父项确认 → 子项 EXECUTING；子项全部完成 → 父项聚合 COMPLETED（详细设计 5.4）
        transactionTemplate.execute(status -> {
            dispatchService.onAdapterApplied(parentId);
            return null;
        });
        for (Map<String, Object> child : children) {
            assertEquals("EXECUTING", statusOf(String.valueOf(child.get("childId"))));
        }
        for (Map<String, Object> child : children) {
            jdbc.update("UPDATE aircraft_instruction SET status = 'COMPLETED' WHERE id = ?",
                    child.get("childId"));
            transactionTemplate.execute(status -> {
                dispatchService.finalizeTerminal(String.valueOf(child.get("childId")),
                        "COMPLETED");
                return null;
            });
        }
        assertEquals("COMPLETED", statusOf(parentId), "全部必需子项完成后父项聚合完成");
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> textPayload(String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", text);
        payload.put("scheduling", "REPLACE");
        return payload;
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }
}

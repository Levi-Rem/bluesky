package org.bluesky.training.assignment;

import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.aircraft.AircraftApplicationService;
import org.bluesky.training.common.CallerContext;
import org.bluesky.training.common.V2DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P08：按频率原子移交（详细设计 2.2 §5.3）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class HandoverServiceTest {

    @Autowired
    private HandoverService handoverService;

    @Autowired
    private AircraftApplicationService applicationService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String sourceTerminalId;
    private String targetTerminalId;

    private String newGroupWithAircraft(String state) {
        String groupId = "group-ho-" + UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_group (id, name, state, simulation_time_seconds) "
                + "VALUES (?, ?, ?, 600)", groupId, "移交组", state);
        sourceTerminalId = "HO-S-" + System.nanoTime();
        targetTerminalId = "HO-T-" + System.nanoTime();
        jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, exercise_group_id, "
                        + "frequency) VALUES (?, '源席', 'PSEUDO_PILOT', ?, 118.100)",
                sourceTerminalId, groupId);
        jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, exercise_group_id, "
                        + "frequency) VALUES (?, '目标席', 'PSEUDO_PILOT', ?, 121.500)",
                targetTerminalId, groupId);
        return groupId;
    }

    /** 创建 ACTIVE 航空器：直接落库（含计划与当前分配），返回其 id。 */
    private String activeAircraft(String groupId) {
        String aircraftId = "ac-ho-" + UUID.randomUUID();
        String callsign = "HO" + System.nanoTime() % 100000;
        jdbc.update("INSERT INTO exercise_aircraft (id, exercise_group_id, assigned_terminal_id, "
                        + "callsign, aircraft_type, wake_category, origin, destination, "
                        + "heading_degrees, altitude_feet, speed_knots, lifecycle, "
                        + "active_callsign_key) VALUES (?, ?, ?, ?, 'A320', 'M', 'ZGGG', 'ZBAA', "
                        + "20, 8000, 250, 'ACTIVE', ?)",
                aircraftId, groupId, sourceTerminalId, callsign, callsign);
        jdbc.update("INSERT INTO flight_plan (id, aircraft_id, plan_version, origin, destination, "
                        + "route_text) VALUES (?, ?, 1, 'ZGGG', 'ZBAA', 'ZGGG ZBAA')",
                UUID.randomUUID().toString(), aircraftId);
        jdbc.update("INSERT INTO aircraft_assignment (id, aircraft_id, terminal_id, current_key) "
                + "VALUES (?, ?, ?, ?)", UUID.randomUUID().toString(), aircraftId,
                sourceTerminalId, aircraftId);
        return aircraftId;
    }

    private long revisionOf(String aircraftId) {
        return jdbc.queryForObject("SELECT revision FROM exercise_aircraft WHERE id = ?",
                Long.class, aircraftId);
    }

    private CallerContext source(String groupId) {
        return CallerContext.terminal(sourceTerminalId, groupId, "digest");
    }

    @Test
    void givenAircraftCreatedThenExactlyOneCurrentAssignmentExists() {
        String groupId = newGroupWithAircraft("RUNNING");

        Map<String, Object> created = transactionTemplate.execute(status ->
                applicationService.createPlan(source(groupId), groupId,
                        minimalRequest("ASN" + System.nanoTime() % 100000)));

        Integer current = jdbc.queryForObject(
                "SELECT COUNT(*) FROM aircraft_assignment WHERE aircraft_id = ? "
                        + "AND ended_at IS NULL", Integer.class, created.get("id"));
        assertEquals(1, current.intValue(), "创建事务必须恰好写一条当前分配");
    }

    @Test
    void givenCompletedHandoverThenSourceReadOnlyTargetWritableAndExistingInstructionsRemain() {
        String groupId = newGroupWithAircraft("RUNNING");
        String aircraftId = activeAircraft(groupId);
        // 两条既有指令（一条活动、一条排队）：移交不得取消或改变它们
        jdbc.update("INSERT INTO aircraft_instruction (id, exercise_aircraft_id, raw_text, "
                        + "instruction_type, insertion_mode, status, sequence_number) "
                        + "VALUES ('ins-a', ?, 'HDG 090', 'HDG', 'REPLACE', 'EXECUTING', 1)",
                aircraftId);
        jdbc.update("INSERT INTO aircraft_instruction (id, exercise_aircraft_id, raw_text, "
                        + "instruction_type, insertion_mode, status, sequence_number) "
                        + "VALUES ('ins-q', ?, 'ALT 9000', 'ALT', 'AFTER_COMPLETION', 'BLOCKED', 2)",
                aircraftId);
        long revisionBefore = revisionOf(aircraftId);

        Map<String, Object> response = transactionTemplate.execute(status ->
                handoverService.handoverByFrequency(source(groupId), aircraftId,
                        revisionBefore, 121.500));

        assertEquals(targetTerminalId, response.get("targetTerminalId"));
        assertEquals(revisionBefore + 1, ((Number) response.get("aircraftRevision")).longValue());
        assertEquals(1, ((List<?>) response.get("activeInstructions")).size());
        assertEquals(1, ((List<?>) response.get("waitingInstructions")).size());

        // 源席立即只读、目标席立即控制
        assertEquals(targetTerminalId, jdbc.queryForObject(
                "SELECT terminal_id FROM aircraft_assignment WHERE aircraft_id = ? "
                        + "AND ended_at IS NULL", String.class, aircraftId));
        // 指令原样保留
        assertEquals("EXECUTING", jdbc.queryForObject(
                "SELECT status FROM aircraft_instruction WHERE id = 'ins-a'", String.class));
        assertEquals("BLOCKED", jdbc.queryForObject(
                "SELECT status FROM aircraft_instruction WHERE id = 'ins-q'", String.class));
        // 移交历史写入
        assertEquals(1L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM aircraft_handover WHERE aircraft_id = ?", Long.class,
                aircraftId));
        // 事件扇出：组内两个启用终端各得一条投递
        assertEquals(2L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM terminal_event_delivery d JOIN business_event b "
                        + "ON b.id = d.business_event_id WHERE b.event_type = "
                        + "'aircraft.handover.completed' AND d.business_event_id IN "
                        + "(SELECT id FROM business_event WHERE exercise_group_id = ?)",
                Long.class, groupId));
    }

    @Test
    void givenTwoConcurrentHandoversThenOnlyOneCommits() throws Exception {
        String groupId = newGroupWithAircraft("RUNNING");
        // 第三个竞争目标
        String thirdTerminalId = "HO-U-" + System.nanoTime();
        jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, "
                        + "exercise_group_id, frequency) VALUES (?, '第三席', 'PSEUDO_PILOT', ?, 125.200)",
                thirdTerminalId, groupId);
        String aircraftId = activeAircraft(groupId);
        long revision = revisionOf(aircraftId);

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        List<Future<String>> outcomes = new java.util.ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (double frequency : new double[]{121.500, 125.200}) {
                outcomes.add(pool.submit((Callable<String>) () -> {
                    start.await();
                    try {
                        transactionTemplate.execute(status -> handoverService.handoverByFrequency(
                                source(groupId), aircraftId, revision, frequency));
                        successes.incrementAndGet();
                        return "OK";
                    } catch (V2DomainException failure) {
                        return failure.code();
                    }
                }));
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, successes.get(), "并发移交只有一个事务成功");
        assertEquals(1L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM aircraft_assignment WHERE aircraft_id = ? "
                        + "AND ended_at IS NULL", Long.class, aircraftId));
        String loserCode = outcomes.stream().map(f -> unsafeGet(f))
                .filter(code -> !"OK".equals(code)).findFirst().orElse("NONE");
        assertEquals("AIRCRAFT_ASSIGNMENT_CHANGED", loserCode,
                "并发败方必须稳定返回 409 分配冲突，而不是因提交时序泄漏为 403");
    }

    @Test
    void givenDisabledOrOtherGroupTargetThenRejected() {
        String groupId = newGroupWithAircraft("RUNNING");
        String aircraftId = activeAircraft(groupId);
        long revision = revisionOf(aircraftId);

        // 停用目标
        jdbc.update("UPDATE workstation_terminal SET enabled = 0 WHERE id = ?", targetTerminalId);
        V2DomainException disabled = assertThrows(V2DomainException.class,
                () -> transactionTemplate.execute(status -> handoverService.handoverByFrequency(
                        source(groupId), aircraftId, revision, 121.500)));
        assertEquals(404, disabled.httpStatus());
        assertEquals("TERMINAL_NOT_FOUND", disabled.code());

        // 其他组频率不存在
        jdbc.update("UPDATE workstation_terminal SET enabled = 1 WHERE id = ?", targetTerminalId);
        V2DomainException otherGroup = assertThrows(V2DomainException.class,
                () -> transactionTemplate.execute(status -> handoverService.handoverByFrequency(
                        source(groupId), aircraftId, revision, 999.000)));
        assertEquals("TERMINAL_NOT_FOUND", otherGroup.code());
    }

    @Test
    void givenStaleRevisionOrForeignSourceThenRejected() {
        String groupId = newGroupWithAircraft("RUNNING");
        String aircraftId = activeAircraft(groupId);

        V2DomainException stale = assertThrows(V2DomainException.class,
                () -> transactionTemplate.execute(status -> handoverService.handoverByFrequency(
                        source(groupId), aircraftId, 999L, 121.500)));
        assertEquals("AIRCRAFT_ASSIGNMENT_CHANGED", stale.code());

        // 非责任席发起
        CallerContext foreign = CallerContext.terminal(targetTerminalId, groupId, "digest");
        V2DomainException notOwner = assertThrows(V2DomainException.class,
                () -> transactionTemplate.execute(status -> handoverService.handoverByFrequency(
                        foreign, aircraftId, revisionOf(aircraftId), 121.500)));
        assertEquals("AIRCRAFT_NOT_ASSIGNED", notOwner.code());
        assertEquals(sourceTerminalId, jdbc.queryForObject(
                "SELECT terminal_id FROM aircraft_assignment WHERE aircraft_id = ? "
                        + "AND ended_at IS NULL", String.class, aircraftId));
    }

    @Test
    void givenAssignmentsQueriedThenCurrentOnesListedWithCallsigns() {
        String groupId = newGroupWithAircraft("RUNNING");
        activeAircraft(groupId);

        List<Map<String, Object>> assignments = handoverService.assignments(source(groupId), groupId);

        assertEquals(1, assignments.size());
        assertEquals(sourceTerminalId, assignments.get(0).get("terminalId"));
        assertTrue(assignments.get(0).get("callsign") != null);
        assertNotEquals(null, assignments.get(0).get("startedAt"));
    }

    private static String unsafeGet(Future<String> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "ERROR:" + e.getMessage();
        }
    }

    private Map<String, Object> minimalRequest(String callsign) {
        Map<String, Object> request = new LinkedHashMap<>();
        Map<String, Object> aircraft = new LinkedHashMap<>();
        aircraft.put("callsign", callsign);
        aircraft.put("aircraftType", "A320");
        aircraft.put("wakeCategory", "M");
        request.put("aircraft", aircraft);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("origin", "ZGGG");
        plan.put("destination", "ZBAA");
        plan.put("plannedSquawk", "0042");
        plan.put("ssrMode", "C");
        plan.put("route", Arrays.asList("ZGGG", "ZBAA"));
        request.put("flightPlan", plan);
        Map<String, Object> initialState = new LinkedHashMap<>();
        initialState.put("latitudeDeg", 23.3924);
        initialState.put("longitudeDeg", 113.2988);
        initialState.put("trueHeadingDeg", 20.0);
        initialState.put("altitudeFtMsl", 50.0);
        initialState.put("indicatedAirspeedKt", 0.0);
        request.put("initialState", initialState);
        request.put("targetAppearanceSimulationTimeSeconds", 600.0);
        request.put("assignedTerminalId", sourceTerminalId);
        return request;
    }
}

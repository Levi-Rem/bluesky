package org.bluesky.training.aircraft;

import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.common.CallerContext;
import org.bluesky.training.common.V2DomainException;
import org.bluesky.training.exercise.ExerciseStartCoordinator;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P07：计划创建、模板补齐、重复告警与本地取消（详细设计 5.2/9.3）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class AircraftApplicationServiceTest {

    @Autowired
    private AircraftApplicationService applicationService;

    @Autowired
    private AircraftAppearanceScheduler scheduler;

    @Autowired
    private AircraftLifecycleService lifecycleService;

    @Autowired
    private ExerciseStartCoordinator startCoordinator;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String terminalId = "PP-AC-" + System.nanoTime();

    private String newGroup(String state, long simulationSeconds) {
        String groupId = "group-ac-" + UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_group (id, name, state, simulation_time_seconds) "
                        + "VALUES (?, ?, ?, ?)", groupId, "航空器组", state, simulationSeconds);
        String engineId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO engine_instance (id, exercise_group_id, control_endpoint, "
                        + "state_endpoint, state) VALUES (?, ?, 'tcp://127.0.0.1:1', "
                        + "'tcp://127.0.0.1:2', 'CONNECTED')", engineId, groupId);
        jdbc.update("UPDATE exercise_group SET engine_instance_id = ? WHERE id = ?",
                engineId, groupId);
        terminalId = "PP-AC-" + System.nanoTime();
        jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, exercise_group_id) "
                        + "VALUES (?, '机长席', 'PSEUDO_PILOT', ?)", terminalId, groupId);
        return groupId;
    }

    private CallerContext terminalOf(String groupId) {
        return CallerContext.terminal(terminalId, groupId, "digest");
    }

    private Map<String, Object> request(String callsign, String squawk,
                                        double targetTime, boolean withAltitude) {
        Map<String, Object> request = new LinkedHashMap<>();
        Map<String, Object> aircraft = new LinkedHashMap<>();
        aircraft.put("callsign", callsign);
        aircraft.put("aircraftType", "A320");
        aircraft.put("wakeCategory", "M");
        aircraft.put("isFake", false);
        request.put("aircraft", aircraft);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("origin", "ZGGG");
        plan.put("destination", "ZBAA");
        plan.put("plannedSquawk", squawk);
        plan.put("ssrMode", "C");
        plan.put("cruiseAltitudeFtMsl", 32000);
        plan.put("cruiseIndicatedAirspeedKt", 280);
        plan.put("route", Arrays.asList("ZGGG", "LMN", "P47", "ZBAA"));
        request.put("flightPlan", plan);
        Map<String, Object> initialState = new LinkedHashMap<>();
        initialState.put("latitudeDeg", 23.3924);
        initialState.put("longitudeDeg", 113.2988);
        initialState.put("trueHeadingDeg", 20.0);
        initialState.put("indicatedAirspeedKt", 0.0);
        if (withAltitude) {
            initialState.put("altitudeFtMsl", 50.0);
        }
        request.put("initialState", initialState);
        request.put("targetAppearanceSimulationTimeSeconds", targetTime);
        request.put("assignedTerminalId", terminalId);
        return request;
    }

    @Test
    void relativeAppearanceIsResolvedAgainstCurrentServerClock() {
        String groupId=newGroup("RUNNING",600);
        Map<String,Object> body=request("REL"+System.nanoTime()%100000,"0043",0,true);
        body.remove("targetAppearanceSimulationTimeSeconds");
        body.put("appearanceDelaySeconds",60);
        Map<String,Object> created=transactionTemplate.execute(status -> applicationService.createPlan(terminalOf(groupId),groupId,body));
        assertEquals(660.0,jdbc.queryForObject("SELECT target_appearance_time FROM exercise_aircraft WHERE id=?",Double.class,created.get("id")));
        body.put("targetAppearanceSimulationTimeSeconds",660);
        assertEquals(400,assertThrows(V2DomainException.class,()->transactionTemplate.execute(status -> applicationService.createPlan(terminalOf(groupId),groupId,body))).httpStatus());
    }

    @Test
    void givenValidPlanWhenCreatedThenPlannedWithTemplateCompletedInitialState() {
        String groupId = newGroup("READY", 0);

        Map<String, Object> created = transactionTemplate.execute(status ->
                applicationService.createPlan(terminalOf(groupId), groupId,
                        request("CSN" + System.nanoTime() % 100000, "0042", 600.0, false)));

        assertEquals("PLANNED", created.get("lifecycle"));
        Map<String, Object> initialState = (Map<String, Object>) created.get("initialState");
        assertEquals(8000.0, ((Number) initialState.get("altitudeFtMsl")).doubleValue(),
                "A320 模板必须补齐初始高度");
        assertEquals(1, ((Number) ((Map<String, Object>) created.get("flightPlan"))
                .get("planVersion")).intValue());
        Long assignmentCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM aircraft_assignment WHERE aircraft_id = ? "
                        + "AND ended_at IS NULL", Long.class, created.get("id"));
        assertEquals(1L, assignmentCount, "创建事务必须同时写初始当前分配");
        assertEquals("PLANNED", jdbc.queryForObject(
                "SELECT lifecycle FROM exercise_aircraft WHERE id = ?",
                String.class, created.get("id")));
    }

    @Test
    void givenMissingInitialFieldsAndTemplatesWhenCreatingThenInitialStateIncomplete() {
        String groupId = newGroup("READY", 0);
        // 完全没有 initialState 且机型无模板 → 422
        Map<String, Object> request = request("BAD" + System.nanoTime() % 100000, "0043", 60.0, false);
        request.put("aircraft", mapOf("callsign", request.get("aircraft") instanceof Map
                ? ((Map<?, ?>) request.get("aircraft")).get("callsign") : null,
                "aircraftType", "UNKNOWN-TYPE", "wakeCategory", "M"));
        request.remove("initialState");

        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> transactionTemplate.execute(status ->
                        applicationService.createPlan(terminalOf(groupId), groupId, request)));
        assertEquals(422, failure.httpStatus());
        assertEquals("INITIAL_STATE_INCOMPLETE", failure.code());
    }

    @Test
    void givenDuplicateCallsignWhenCreatedThenRejected409() {
        String groupId = newGroup("RUNNING", 0);
        String callsign = "DUP" + System.nanoTime() % 100000;
        transactionTemplate.execute(status -> applicationService.createPlan(
                terminalOf(groupId), groupId, request(callsign, "0044", 600.0, true)));

        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> transactionTemplate.execute(status ->
                        applicationService.createPlan(terminalOf(groupId), groupId,
                                request(callsign, "0045", 600.0, true))));
        assertEquals(409, failure.httpStatus());
        assertEquals("DUPLICATE_CALLSIGN", failure.code());
    }

    @Test
    void givenDuplicateSquawkWhenCreatedThenWarningNotRejection() {
        String groupId = newGroup("RUNNING", 0);
        String squawk = "0055";
        transactionTemplate.execute(status -> applicationService.createPlan(
                terminalOf(groupId), groupId,
                request("SQ1" + System.nanoTime() % 100000, squawk, 600.0, true)));

        Map<String, Object> second = transactionTemplate.execute(status ->
                applicationService.createPlan(terminalOf(groupId), groupId,
                        request("SQ2" + System.nanoTime() % 100000, squawk, 600.0, true)));

        List<Map<String, Object>> warnings = (List<Map<String, Object>>) second.get("warnings");
        assertEquals(1, warnings.size());
        assertEquals("DUPLICATE_SQUAWK", warnings.get(0).get("warningCode"));
    }

    @Test
    void givenFutureAppearanceWhenPausedThenCountdownDoesNotAdvance() {
        String groupId = newGroup("PAUSED", 600);

        Map<String, Object> created = transactionTemplate.execute(status ->
                applicationService.createPlan(terminalOf(groupId), groupId,
                        request("PAU" + System.nanoTime() % 100000, "0046", 1200.0, true)));

        Map<String, Object> scan = scheduler.scanDuePlans(groupId);
        assertEquals(0, ((Number) scan.get("claimed")).intValue(),
                "暂停组不调度出现（倒计时冻结）");
        assertEquals("PAUSED", scan.get("groupState"));
    }

    @Test
    void givenDuePlanWhenRunningThenOneWorkerClaimsItOnce() {
        String groupId = newGroup("RUNNING", 600);
        Map<String, Object> created = transactionTemplate.execute(status ->
                applicationService.createPlan(terminalOf(groupId), groupId,
                        request("DUE" + System.nanoTime() % 100000, "0047", 600.0, true)));
        String aircraftId = String.valueOf(created.get("id"));

        Map<String, Object> first = scheduler.scanDuePlans(groupId);
        Map<String, Object> second = scheduler.scanDuePlans(groupId);

        assertEquals(1, ((Number) first.get("claimed")).intValue());
        assertEquals(0, ((Number) second.get("claimed")).intValue(),
                "第二次扫描不得重复认领同一到期计划");
        assertEquals("CREATE_REQUESTED", jdbc.queryForObject(
                "SELECT lifecycle FROM exercise_aircraft WHERE id = ?",
                String.class, aircraftId));
        Long applyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'AIRCRAFT_APPLY' "
                        + "AND exercise_group_id = ?", Long.class, groupId);
        assertEquals(1L, applyCount, "恰好写一条 AIRCRAFT_APPLY");
        String applyPayload = jdbc.queryForObject(
                "SELECT payload FROM outbox_event WHERE event_type = 'AIRCRAFT_APPLY' "
                        + "AND exercise_group_id = ?", String.class, groupId);
        assertTrue(applyPayload.contains("\"callsign\""));
        assertTrue(applyPayload.contains("\"aircraftType\":\"A320\""));
        assertTrue(applyPayload.contains("\"route\":[\"ZGGG\",\"LMN\",\"P47\",\"ZBAA\"]"),
                "Adapter 动作必须携带可独立重放的完整最新计划，不能只有 aircraftId");
    }

    @Test
    void givenCreateFailedWhenRetriedThenSameIdempotencyKeyReused() {
        String groupId = newGroup("RUNNING", 0);
        Map<String, Object> created = transactionTemplate.execute(status ->
                applicationService.createPlan(terminalOf(groupId), groupId,
                        request("RTY" + System.nanoTime() % 100000, "0053", 0.0, true)));
        String aircraftId = String.valueOf(created.get("id"));
        scheduler.scanDuePlans(groupId);
        lifecycleService.markCreateFailed(aircraftId, "ADAPTER_REJECTED", "性能超限");
        jdbc.update("UPDATE outbox_event SET status = 'CONFIRMED' "
                + "WHERE event_type = 'AIRCRAFT_APPLY' AND idempotency_key = ?", aircraftId);

        Map<String, Object> retried = lifecycleService.retryCreate(aircraftId);

        assertEquals("CREATE_REQUESTED", retried.get("lifecycle"));
        List<Map<String, Object>> actions = jdbc.queryForList(
                "SELECT idempotency_key, status FROM outbox_event "
                        + "WHERE event_type = 'AIRCRAFT_APPLY' AND idempotency_key = ? "
                        + "ORDER BY created_at", aircraftId);
        assertEquals(1, actions.size(), "重试必须复用原 Outbox 动作而不是插入重复动作");
        assertEquals(aircraftId, actions.get(0).get("idempotency_key"));
        assertEquals("PENDING", actions.get(0).get("status"));
    }

    @Test
    void givenAdapterConfirmedWhenAppliedThenActiveWithActualTime() {
        String groupId = newGroup("RUNNING", 600);
        Map<String, Object> created = transactionTemplate.execute(status ->
                applicationService.createPlan(terminalOf(groupId), groupId,
                        request("ACT" + System.nanoTime() % 100000, "0054", 600.0, true)));
        String aircraftId = String.valueOf(created.get("id"));
        scheduler.scanDuePlans(groupId);

        Map<String, Object> active = lifecycleService.confirmActive(aircraftId, 612.5);

        assertEquals("ACTIVE", active.get("lifecycle"));
        assertEquals(612.5, ((Number) active.get("actual_appearance_time")).doubleValue());
        assertTrue(lifecycleService.applyStateFrame(aircraftId, 23.5, 113.4, 90.0,
                9000.0, 260.0, 500.0));
        V2DomainException stale = assertThrows(V2DomainException.class,
                () -> lifecycleService.confirmActive(aircraftId, 999.0));
        assertEquals("TRAINING_STATE_INVALID", stale.code());
    }

    @Test
    void givenPlannedAircraftWhenCancelledThenDeletedLocallyWithoutAdapterOutbox() {
        String groupId = newGroup("READY", 0);
        Map<String, Object> created = transactionTemplate.execute(status ->
                applicationService.createPlan(terminalOf(groupId), groupId,
                        request("CXL" + System.nanoTime() % 100000, "0050", 9999.0, true)));
        String aircraftId = String.valueOf(created.get("id"));

        Map<String, Object> cancelled = transactionTemplate.execute(status ->
                applicationService.cancelPlannedAircraft(terminalOf(groupId), aircraftId));

        assertEquals("DELETED", cancelled.get("lifecycle"));
        assertEquals("DELETED", jdbc.queryForObject(
                "SELECT lifecycle FROM exercise_aircraft WHERE id = ?",
                String.class, aircraftId));
        assertEquals(0L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'AIRCRAFT_APPLY' "
                        + "AND exercise_group_id = ?", Long.class, groupId),
                "未出现航空器取消不得写 Adapter Outbox");
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM aircraft_assignment WHERE aircraft_id = ? "
                        + "AND ended_at IS NULL", Integer.class, aircraftId),
                "取消必须结束当前分配");
    }

    @Test
    void givenActiveAircraftWhenPatchingPlanThen409() {
        String groupId = newGroup("RUNNING", 0);
        Map<String, Object> created = transactionTemplate.execute(status ->
                applicationService.createPlan(terminalOf(groupId), groupId,
                        request("PAT" + System.nanoTime() % 100000, "0051", 0.0, true)));
        String aircraftId = String.valueOf(created.get("id"));
        scheduler.scanDuePlans(groupId);
        lifecycleService.confirmActive(aircraftId, 61.0);

        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> transactionTemplate.execute(status ->
                        applicationService.patchPlannedFlightPlan(terminalOf(groupId), aircraftId,
                                ((Number) created.get("revision")).longValue(),
                                mapOf("route", Arrays.asList("ZGGG", "ZBAA")))));
        assertEquals(409, failure.httpStatus());
    }

    @Test
    void givenPastAppearanceOrForeignAssignedTerminalWhenCreatingThenRejected() {
        String groupId = newGroup("RUNNING", 600);
        V2DomainException past = assertThrows(V2DomainException.class,
                () -> applicationService.createPlan(terminalOf(groupId), groupId,
                        request("PAST1", "0061", 599.999, true)));
        assertEquals("INVALID_INSTRUCTION", past.code());

        String otherGroup = "group-other-" + UUID.randomUUID();
        String otherTerminal = "PP-OTHER-" + System.nanoTime();
        jdbc.update("INSERT INTO exercise_group (id, name, state) VALUES (?, '其他组', 'READY')",
                otherGroup);
        jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, exercise_group_id) "
                + "VALUES (?, '其他席', 'PSEUDO_PILOT', ?)", otherTerminal, otherGroup);
        Map<String, Object> foreign = request("FOREIGN1", "0062", 600.0, true);
        foreign.put("assignedTerminalId", otherTerminal);
        V2DomainException rejected = assertThrows(V2DomainException.class,
                () -> applicationService.createPlan(terminalOf(groupId), groupId, foreign));
        assertEquals("TERMINAL_NOT_FOUND", rejected.code());
    }

    @Test
    void givenOwnedPlannedAircraftWhenPatchingWithExpectedRevisionThenRevisionAdvances() {
        String groupId = newGroup("READY", 0);
        Map<String, Object> created = applicationService.createPlan(terminalOf(groupId), groupId,
                request("PATCH1", "0063", 600.0, true));
        String aircraftId = String.valueOf(created.get("id"));

        Map<String, Object> patched = applicationService.patchPlannedFlightPlan(
                terminalOf(groupId), aircraftId, 1L,
                mapOf("route", Arrays.asList("ZGGG", "P47", "ZBAA")));

        assertEquals(2L, ((Number) patched.get("revision")).longValue());
        assertEquals(2L, jdbc.queryForObject(
                "SELECT revision FROM exercise_aircraft WHERE id = ?", Long.class, aircraftId));
        V2DomainException stale = assertThrows(V2DomainException.class,
                () -> applicationService.patchPlannedFlightPlan(terminalOf(groupId), aircraftId,
                        1L, mapOf("route", Arrays.asList("ZGGG", "ZBAA"))));
        assertEquals("REVISION_CONFLICT", stale.code());
    }

    @Test
    void givenDuePlansDuringStartingThenStartWaitsForEveryAircraftConfirmation() {
        String groupId = newGroup("READY", 0);
        Map<String, Object> first = applicationService.createPlan(terminalOf(groupId), groupId,
                request("STARTA", "0064", 0.0, true));
        Map<String, Object> second = applicationService.createPlan(terminalOf(groupId), groupId,
                request("STARTB", "0065", 0.0, true));
        jdbc.update("UPDATE exercise_group SET state = 'STARTING' WHERE id = ?", groupId);
        String engineId = jdbc.queryForObject(
                "SELECT engine_instance_id FROM exercise_group WHERE id = ?", String.class, groupId);

        startCoordinator.enqueueStart(groupId, engineId);
        assertEquals(2L, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE "
                + "exercise_group_id = ? AND event_type = 'AIRCRAFT_APPLY'", Long.class, groupId));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE "
                + "exercise_group_id = ? AND event_type = 'START'", Long.class, groupId));

        lifecycleService.confirmActive(String.valueOf(first.get("id")), 0.0);
        startCoordinator.continueAfterAircraftApplied(groupId, engineId);
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE "
                + "exercise_group_id = ? AND event_type = 'START'", Long.class, groupId));
        lifecycleService.confirmActive(String.valueOf(second.get("id")), 0.0);
        startCoordinator.continueAfterAircraftApplied(groupId, engineId);
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE "
                + "exercise_group_id = ? AND event_type = 'START'", Long.class, groupId));
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }
}

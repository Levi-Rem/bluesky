package org.bluesky.training.exercise;

import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.common.CallerContext;
import org.bluesky.training.common.V2DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P05：v2 生命周期请求（详细设计 5.1：过渡状态 + Outbox 同事务、revision、重复幂等）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class ExerciseGroupServiceV2Test {

    private static final CallerContext TERMINAL =
            CallerContext.terminal("PP-01", "placeholder", "digest");
    private static final CallerContext ORCHESTRATOR =
            CallerContext.orchestrator("orch-1", "orch-digest");

    @Autowired
    private ExerciseGroupService exerciseGroupService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private org.bluesky.training.adapter.AdapterOutboxDispatcher adapterDispatcher;

    @Autowired
    private org.bluesky.training.reference.ReferenceSnapshotService snapshotService;

    @Autowired
    private org.bluesky.training.adapter.EngineInstanceService engineInstanceService;

    private String newGroup(String state) {
        String groupId = "group-v2-" + UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_group (id, name, state) VALUES (?, ?, ?)",
                groupId, "生命周期组", state);
        if ("READY".equals(state)) {
            org.bluesky.training.testsupport.V2FixtureFactory.seedPublishedSnapshot(jdbc, groupId);
            jdbc.update("INSERT INTO workstation_terminal "
                            + "(id, name, terminal_type, exercise_group_id) VALUES (?, ?, ?, ?)",
                    "terminal-" + UUID.randomUUID(), "机长席", "PSEUDO_PILOT", groupId);
        }
        return groupId;
    }

    private long revisionOf(String groupId) {
        return jdbc.queryForObject("SELECT revision FROM exercise_group WHERE id = ?",
                Long.class, groupId);
    }

    private CallerContext terminalOf(String groupId) {
        return CallerContext.terminal("PP-01", groupId, "digest");
    }

    @Test
    void givenStartWhenReadyThenStartingAndOutboxCommitTogether() {
        String groupId = newGroup("READY");

        Map<String, Object> envelope = transactionTemplate.execute(status ->
                exerciseGroupService.requestStart(terminalOf(groupId), groupId, revisionOf(groupId)));

        assertEquals("STARTING", envelope.get("state"));
        assertEquals(groupId, envelope.get("resourceId"));
        assertEquals("STARTING", jdbc.queryForObject(
                "SELECT state FROM exercise_group WHERE id = ?", String.class, groupId));
        Integer outboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE exercise_group_id = ? "
                        + "AND event_type = 'HELLO' AND outbox_kind = 'ADAPTER_ACTION'",
                Integer.class, groupId);
        assertEquals(1, outboxCount, "START 过渡必须先与 Adapter HELLO 动作同事务写 Outbox");
        assertNotNull(jdbc.queryForObject(
                "SELECT engine_instance_id FROM exercise_group WHERE id = ?", String.class, groupId));
        assertTrue(((Number) envelope.get("revision")).longValue() > 1L);
    }

    @Test
    void givenDuplicateStartThenSameOperationReturned() {
        String groupId = newGroup("STARTING");
        long revisionBefore = revisionOf(groupId);

        Map<String, Object> envelope = transactionTemplate.execute(status ->
                exerciseGroupService.requestStart(terminalOf(groupId), groupId, revisionBefore));

        assertEquals("STARTING", envelope.get("state"));
        assertEquals(revisionBefore, revisionOf(groupId), "重复请求不得再次推进状态或 revision");
        Integer outboxCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE exercise_group_id = ? AND event_type = 'START'",
                Integer.class, groupId);
        assertEquals(0, outboxCount.intValue(), "重复请求不得追加 Outbox");
    }

    @Test
    void givenStaleRevisionThenConflict() {
        String groupId = newGroup("READY");

        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> transactionTemplate.execute(status ->
                        exerciseGroupService.requestStart(terminalOf(groupId), groupId, 999L)));
        assertEquals(409, failure.httpStatus());
        assertEquals("REVISION_CONFLICT", failure.code());
        assertEquals("READY", jdbc.queryForObject(
                "SELECT state FROM exercise_group WHERE id = ?", String.class, groupId));
    }

    @Test
    void givenTerminalFromOtherGroupWhenStartingThenForbidden() {
        String groupId = newGroup("READY");

        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> transactionTemplate.execute(status ->
                        exerciseGroupService.requestStart(TERMINAL, groupId, revisionOf(groupId))));
        assertEquals("TERMINAL_NOT_IN_GROUP", failure.code());
    }

    @Test
    void givenAdapterStartedWhenResultAppliedThenRunning() {
        String groupId = newGroup("STARTING");

        exerciseGroupService.onAdapterLifecycleResult(groupId, "STARTED");

        assertEquals("RUNNING", jdbc.queryForObject(
                "SELECT state FROM exercise_group WHERE id = ?", String.class, groupId));
        assertNotNull(jdbc.queryForObject(
                "SELECT started_at FROM exercise_group WHERE id = ?", java.sql.Timestamp.class, groupId));
    }

    @Test
    void givenAdapterPausedAndResumedWhenAppliedThenStatesAdvance() {
        String groupId = newGroup("PAUSING");
        exerciseGroupService.onAdapterLifecycleResult(groupId, "PAUSED");
        assertEquals("PAUSED", jdbc.queryForObject(
                "SELECT state FROM exercise_group WHERE id = ?", String.class, groupId));

        Map<String, Object> resume = transactionTemplate.execute(status ->
                exerciseGroupService.requestResume(terminalOf(groupId), groupId, revisionOf(groupId)));
        assertEquals("RESUMING", resume.get("state"));

        exerciseGroupService.onAdapterLifecycleResult(groupId, "RESUMED");
        assertEquals("RUNNING", jdbc.queryForObject(
                "SELECT state FROM exercise_group WHERE id = ?", String.class, groupId));
    }

    @Test
    void givenEndRequestedByOrchestratorWhenAppliedThenEndingThenEnded() {
        String groupId = newGroup("RUNNING");

        Map<String, Object> envelope = transactionTemplate.execute(status ->
                exerciseGroupService.requestEnd(ORCHESTRATOR, groupId, revisionOf(groupId), "训练完成"));
        assertEquals("ENDING", envelope.get("state"));
        assertEquals("训练完成", jdbc.queryForObject(
                "SELECT state_reason FROM exercise_group WHERE id = ?", String.class, groupId));

        exerciseGroupService.onAdapterLifecycleResult(groupId, "STOPPED");
        assertEquals("ENDED", jdbc.queryForObject(
                "SELECT state FROM exercise_group WHERE id = ?", String.class, groupId));
        assertNotNull(jdbc.queryForObject(
                "SELECT ended_at FROM exercise_group WHERE id = ?", java.sql.Timestamp.class, groupId));
    }

    @Test
    void givenTerminalWhenEndingThenForbidden() {
        String groupId = newGroup("RUNNING");

        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> transactionTemplate.execute(status ->
                        exerciseGroupService.requestEnd(TERMINAL, groupId, revisionOf(groupId), null)));
        assertEquals("TRUSTED_IDENTITY_REJECTED", failure.code());
        assertEquals("RUNNING", jdbc.queryForObject(
                "SELECT state FROM exercise_group WHERE id = ?", String.class, groupId));
    }

    @Test
    void givenReferenceLoadFailureWhenCompensatedThenGroupReturnsReadyWithReason() {
        String groupId = newGroup("STARTING");

        Map<String, Object> envelope = transactionTemplate.execute(status ->
                exerciseGroupService.compensateFailedStart(groupId, "参考快照校验失败"));

        assertEquals("READY", envelope.get("state"));
        assertEquals("参考快照校验失败", jdbc.queryForObject(
                "SELECT state_reason FROM exercise_group WHERE id = ?", String.class, groupId));
    }

    @Test
    void givenStartWorkflowWhenEachAdapterStepAcknowledgedThenOnlyAfterLoadDoesGroupRun() {
        jdbc.update("DELETE FROM outbox_event");
        String groupId = newGroup("READY");
        transactionTemplate.execute(status -> exerciseGroupService.requestStart(
                terminalOf(groupId), groupId, revisionOf(groupId)));

        adapterDispatcher.dispatchPending("start-hello", action ->
                acceptedResponse("HELLO_ACK", null), 10);
        assertEquals("REFERENCE_SNAPSHOT_LOAD", jdbc.queryForObject(
                "SELECT event_type FROM outbox_event WHERE exercise_group_id = ? "
                        + "AND outbox_kind = 'ADAPTER_ACTION' AND status = 'PENDING'",
                String.class, groupId));

        adapterDispatcher.dispatchPending("start-reference", action -> acceptedResponse(
                "REFERENCE_SNAPSHOT_ACK", snapshotService.pinnedManifestChecksum(groupId)), 10);
        assertEquals("START", jdbc.queryForObject(
                "SELECT event_type FROM outbox_event WHERE exercise_group_id = ? "
                        + "AND outbox_kind = 'ADAPTER_ACTION' AND status = 'PENDING'",
                String.class, groupId));
        assertEquals("STARTING", jdbc.queryForObject(
                "SELECT state FROM exercise_group WHERE id = ?", String.class, groupId));

        adapterDispatcher.dispatchPending("start-run", action -> acceptedResponse("STARTED", null), 10);
        assertEquals("RUNNING", jdbc.queryForObject(
                "SELECT state FROM exercise_group WHERE id = ?", String.class, groupId));
    }

    @Test
    void givenReferenceChecksumMismatchThenEngineStopsAndGroupReturnsReady() {
        jdbc.update("DELETE FROM outbox_event");
        String groupId = newGroup("READY");
        transactionTemplate.execute(status -> exerciseGroupService.requestStart(
                terminalOf(groupId), groupId, revisionOf(groupId)));
        adapterDispatcher.dispatchPending("bad-hello", action -> acceptedResponse("HELLO_ACK", null), 10);

        adapterDispatcher.dispatchPending("bad-reference", action ->
                acceptedResponse("REFERENCE_SNAPSHOT_ACK", "wrong-checksum"), 10);

        assertEquals("READY", jdbc.queryForObject(
                "SELECT state FROM exercise_group WHERE id = ?", String.class, groupId));
        assertEquals("REFERENCE_CHECKSUM_MISMATCH", jdbc.queryForObject(
                "SELECT state_reason FROM exercise_group WHERE id = ?", String.class, groupId));
    }

    @Test
    void givenPauseRejectedThenGroupEntersRecoveringWithoutGuessingMotion() {
        jdbc.update("DELETE FROM outbox_event");
        String groupId = newGroup("RUNNING");
        engineInstanceService.createForGroup(groupId, "inproc://pause-control", "inproc://pause-state");
        transactionTemplate.execute(status -> exerciseGroupService.requestPause(
                terminalOf(groupId), groupId, revisionOf(groupId)));

        adapterDispatcher.dispatchPending("pause-rejected", action ->
                response("PAUSED", false, "PAUSE_REJECTED", null), 10);

        assertEquals("RECOVERING", jdbc.queryForObject(
                "SELECT state FROM exercise_group WHERE id = ?", String.class, groupId));
    }

    @Test
    void givenStopRejectedThenEndingIsRetainedAndOriginalActionIsRetried() {
        jdbc.update("DELETE FROM outbox_event");
        String groupId = newGroup("RUNNING");
        engineInstanceService.createForGroup(groupId, "inproc://stop-control", "inproc://stop-state");
        transactionTemplate.execute(status -> exerciseGroupService.requestEnd(
                ORCHESTRATOR, groupId, revisionOf(groupId), "完成"));

        adapterDispatcher.dispatchPending("stop-rejected", action ->
                response("STOPPED", false, "STOP_REJECTED", null), 10);

        assertEquals("ENDING", jdbc.queryForObject(
                "SELECT state FROM exercise_group WHERE id = ?", String.class, groupId));
        assertEquals(Integer.valueOf(1), jdbc.queryForObject(
                "SELECT attempt_count FROM outbox_event WHERE exercise_group_id = ? "
                        + "AND event_type = 'STOP'", Integer.class, groupId));
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM outbox_event WHERE exercise_group_id = ? "
                        + "AND event_type = 'STOP'", String.class, groupId));
    }

    private static Map<String, Object> acceptedResponse(String messageType,
                                                        String manifestChecksum) {
        return response(messageType, true, "OK", manifestChecksum);
    }

    private static Map<String, Object> response(String messageType, boolean accepted,
                                                String code, String manifestChecksum) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("accepted", accepted);
        payload.put("code", code);
        payload.put("message", accepted ? "accepted" : "rejected");
        if (manifestChecksum != null) {
            payload.put("manifestChecksum", manifestChecksum);
        }
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("messageType", messageType);
        response.put("payload", payload);
        return response;
    }
}

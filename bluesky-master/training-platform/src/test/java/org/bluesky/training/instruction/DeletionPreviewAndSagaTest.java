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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P15：删除预览 token 与可恢复删除 Saga（详细设计 2.2 §7.9）。
 * 评审 P0-5：HMAC 签名（伪造拒绝）、摘要落库原子消费（重放拒绝）、
 * 预览响应不回摘要；评审 D1/D2/D3 随 Saga 断言。
 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class DeletionPreviewAndSagaTest {

    private static final byte[] SECRET =
            "test-deletion-secret".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private DeletionPreviewService previewService;

    @Autowired
    private org.bluesky.training.aircraft.AircraftDeletionSaga saga;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String terminalId;
    private String aircraftId;
    private String groupId;

    private void newAircraft(String lifecycle) {
        newAircraft(lifecycle, "RUNNING");
    }

    private void newAircraft(String lifecycle, String groupState) {
        groupId = "group-del-" + UUID.randomUUID();
        terminalId = "DL-" + System.nanoTime();
        jdbc.update("INSERT INTO exercise_group (id, name, state, simulation_time_seconds) "
                        + "VALUES (?, ?, ?, 600)", groupId, "删除组", groupState);
        jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, "
                        + "exercise_group_id) VALUES (?, '机长席', 'PSEUDO_PILOT', ?)", terminalId, groupId);
        aircraftId = "ac-del-" + UUID.randomUUID();
        String callsign = "DL" + System.nanoTime() % 100000;
        jdbc.update("INSERT INTO exercise_aircraft (id, exercise_group_id, assigned_terminal_id, "
                        + "callsign, aircraft_type, wake_category, origin, destination, "
                        + "heading_degrees, altitude_feet, speed_knots, lifecycle, "
                        + "active_callsign_key) VALUES (?, ?, ?, ?, 'A320', 'M', 'ZGGG', 'ZBAA', "
                        + "20, 8000, 250, ?, ?)",
                aircraftId, groupId, terminalId, callsign, lifecycle, callsign);
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

    private CallerContext otherTerminalCaller() {
        return CallerContext.terminal("DL-OTHER-" + System.nanoTime(), groupId, "digest-other");
    }

    private long revisionOf() {
        return jdbc.queryForObject("SELECT revision FROM exercise_aircraft WHERE id = ?",
                Long.class, aircraftId);
    }

    private String lifecycleOf() {
        return jdbc.queryForObject("SELECT lifecycle FROM exercise_aircraft WHERE id = ?",
                String.class, aircraftId);
    }

    // ---------------------------------------------------------------- token

    @Test
    void givenTokenContractWhenEncodedDecodedThenClaimsSurvive() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("aircraftId", "ac-1");
        claims.put("revision", 5L);
        claims.put("terminalId", "PP-01");
        claims.put("requestId", "req-1");
        claims.put("expiresAtEpochSeconds", 1000L);
        String token = DeletionTokenCodec.encode(claims, SECRET);

        Map<String, Object> decoded = DeletionTokenCodec.decodeAndVerify(token, SECRET);
        assertEquals("ac-1", decoded.get("aircraftId"));
        assertEquals(5, ((Number) decoded.get("revision")).intValue());

        // 摘要不等于明文且稳定
        String digest = DeletionTokenCodec.hashForStorage(token);
        assertEquals(64, digest.length());
        assertEquals(digest, DeletionTokenCodec.hashForStorage(token));
        assertNotEquals(token, digest);

        // 缺 token / 篡改
        V2DomainException missing = assertThrows(V2DomainException.class,
                () -> DeletionTokenCodec.decodeAndVerify(null, SECRET));
        assertEquals("CONFIRMATION_REQUIRED", missing.code());
        assertThrows(V2DomainException.class,
                () -> DeletionTokenCodec.decodeAndVerify("not-a-token!!", SECRET));
    }

    @Test
    void givenUnsignedForgedTokenWhenDecodedThenRejected() {
        // 评审 P0-5：无 HMAC 的 Base64(JSON) 可被任意客户端伪造，必须拒绝
        Map<String, Object> forged = new LinkedHashMap<>();
        forged.put("aircraftId", "ac-1");
        forged.put("revision", 5);
        forged.put("terminalId", "PP-01");
        forged.put("expiresAtEpochSeconds",
                System.currentTimeMillis() / 1000 + 3600);
        String unsignedToken = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String("{\"aircraftId\":\"ac-1\",\"revision\":5,"
                        + "\"terminalId\":\"PP-01\",\"expiresAtEpochSeconds\":9999999999}")
                        .getBytes(StandardCharsets.UTF_8));

        V2DomainException rejected = assertThrows(V2DomainException.class,
                () -> DeletionTokenCodec.decodeAndVerify(unsignedToken, SECRET));
        assertEquals("CONFIRMATION_REQUIRED", rejected.code());

        // 错误密钥签发的 token 同样拒绝（密钥轮换后旧 token 失效）
        String wrongKeyToken = DeletionTokenCodec.encode(forged,
                "other-secret".getBytes(StandardCharsets.UTF_8));
        assertThrows(V2DomainException.class,
                () -> DeletionTokenCodec.decodeAndVerify(wrongKeyToken, SECRET));
    }

    @Test
    void givenPreviewTokenWhenConsumedTwiceThenReplayRejected() throws Exception {
        // 评审 P0-5 核心：一次性消费——同一 token 第二次使用必须 428
        newAircraft("ACTIVE");
        Map<String, Object> preview = transactionTemplate.execute(status ->
                previewService.createPreview(caller(), aircraftId, revisionOf(), "req-1"));
        String token = String.valueOf(preview.get("confirmationToken"));
        assertNotNull(token);
        assertEquals("ACTIVE", preview.get("lifecycle"));
        // 预览响应不得回摘要
        assertFalse(preview.containsKey("tokenDigest"), "摘要不得回传客户端");

        previewService.validateAndConsumeToken(token, aircraftId, revisionOf(), terminalId);

        V2DomainException replay = assertThrows(V2DomainException.class,
                () -> previewService.validateAndConsumeToken(token, aircraftId, revisionOf(),
                        terminalId));
        assertEquals("CONFIRMATION_REQUIRED", replay.code());
        assertTrue(replay.getMessage().contains("重新预览"));
    }

    @Test
    void givenPreviewTokenWhenGuardPathsViolatedThenRejected() throws Exception {
        newAircraft("ACTIVE");
        Map<String, Object> preview = transactionTemplate.execute(status ->
                previewService.createPreview(caller(), aircraftId, revisionOf(), "req-1"));
        String token = String.valueOf(preview.get("confirmationToken"));

        // 版本变化
        jdbc.update("UPDATE exercise_aircraft SET revision = revision + 1 WHERE id = ?",
                aircraftId);
        assertThrows(V2DomainException.class, () -> previewService.validateAndConsumeToken(
                token, aircraftId, revisionOf(), terminalId));

        // 其他终端使用
        assertThrows(V2DomainException.class, () -> previewService.validateAndConsumeToken(
                token, aircraftId, revisionOf(), "PP-OTHER"));

        // 航空器不匹配
        assertThrows(V2DomainException.class, () -> previewService.validateAndConsumeToken(
                token, "ac-other", revisionOf(), terminalId));
    }

    @Test
    void givenExpiredTokenWhenConsumedThenRejected() {
        newAircraft("ACTIVE");
        Map<String, Object> preview = transactionTemplate.execute(status ->
                previewService.createPreview(caller(), aircraftId, revisionOf(), "req-1"));
        String token = String.valueOf(preview.get("confirmationToken"));

        // 将库内过期时间改到过去，模拟 30 秒窗口流逝
        jdbc.update("UPDATE deletion_confirmation_token SET expires_at = "
                + "CURRENT_TIMESTAMP(3) - INTERVAL '1' SECOND WHERE aircraft_id = ?", aircraftId);

        V2DomainException expired = assertThrows(V2DomainException.class,
                () -> previewService.validateAndConsumeToken(token, aircraftId, revisionOf(),
                        terminalId));
        assertEquals("CONFIRMATION_REQUIRED", expired.code());
    }

    // ---------------------------------------------------------------- Saga

    @Test
    void givenDeleteRequestedWhenSagaRunsThenNewWritesRejectedAndStepsOrdered() {
        newAircraft("ACTIVE");
        // 带一条活动指令与活动引导目标，验证 D1 收尾
        String instructionId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO aircraft_instruction (id, exercise_aircraft_id, "
                        + "exercise_group_id, source_terminal_id, instruction_type, "
                        + "control_channel, conflict_key, scheduling, status, sequence_number, "
                        + "raw_text, parsed_payload, insertion_mode) VALUES (?, ?, ?, ?, 'HDG', "
                        + "'LATERAL', 'LATERAL:HDG:nav', 'REPLACE', 'EXECUTING', 1, 'HDG 090', "
                        + "'{}', 'REPLACE')",
                instructionId, aircraftId, groupId, terminalId);
        String guidanceId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO guidance_target (id, instruction_id, aircraft_id, channel, "
                        + "state, target_json) VALUES (?, ?, ?, 'LATERAL', 'ACTIVE', '{}')",
                guidanceId, instructionId, aircraftId);

        Map<String, Object> requested = transactionTemplate.execute(status ->
                saga.requestDelete(caller(), aircraftId, null));
        assertEquals("DELETE_REQUESTED", requested.get("state"));
        assertEquals(Boolean.TRUE, requested.get("adapterPending"));
        assertEquals(1L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'AIRCRAFT_DELETE' "
                        + "AND exercise_group_id = ?", Long.class, groupId));

        // DELETE_REQUESTED 再删：状态机拒绝新写入路径
        V2DomainException rejected = assertThrows(V2DomainException.class,
                () -> transactionTemplate.execute(status ->
                        saga.requestDelete(caller(), aircraftId, null)));
        assertEquals("TRAINING_STATE_INVALID", rejected.code());

        // Adapter 确认 → DELETED：分配结束、指令取消、引导清除、历史保留（评审 D1）
        transactionTemplate.execute(status -> saga.confirmDeleted(aircraftId));
        assertEquals("DELETED", lifecycleOf());
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM aircraft_assignment WHERE aircraft_id = ? "
                        + "AND ended_at IS NULL", Integer.class, aircraftId));
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM aircraft_instruction WHERE id = ?",
                String.class, instructionId), "删除确认后指令必须取消");
        assertEquals("SUPERSEDED", jdbc.queryForObject(
                "SELECT state FROM guidance_target WHERE id = ?",
                String.class, guidanceId), "删除确认后引导目标必须清除");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM flight_plan WHERE aircraft_id = ?",
                Integer.class, aircraftId), "计划历史不物理删除");
    }

    @Test
    void givenNonResponsibleTerminalWhenDeletingThenRejectedAtomically() {
        // 评审 D2：席别校验内嵌于生命周期 CAS——旧席（移交后）删除必须失败
        newAircraft("ACTIVE");
        V2DomainException rejected = assertThrows(V2DomainException.class,
                () -> transactionTemplate.execute(status ->
                        saga.requestDelete(otherTerminalCaller(), aircraftId, null)));
        assertEquals("AIRCRAFT_NOT_ASSIGNED", rejected.code());
        assertEquals("ACTIVE", lifecycleOf());
    }

    @Test
    void givenEndedOrRecoveringGroupWhenDeletingThenRejected() {
        // 评审 D3：组状态校验
        newAircraft("ACTIVE", "ENDED");
        V2DomainException ended = assertThrows(V2DomainException.class,
                () -> transactionTemplate.execute(status ->
                        saga.requestDelete(caller(), aircraftId, null)));
        assertEquals("TRAINING_STATE_INVALID", ended.code());

        newAircraft("ACTIVE", "RECOVERING");
        V2DomainException recovering = assertThrows(V2DomainException.class,
                () -> transactionTemplate.execute(status ->
                        saga.requestDelete(caller(), aircraftId, null)));
        assertEquals("ENGINE_RECOVERING", recovering.code());
        assertEquals(503, recovering.httpStatus());
    }

    @Test
    void givenAdapterRejectsThenDeleteFailedRetainsAircraftAndReason() {
        newAircraft("ACTIVE");
        transactionTemplate.execute(status -> saga.requestDelete(caller(), aircraftId, null));

        transactionTemplate.execute(status ->
                saga.markDeleteFailed(aircraftId, "ADAPTER_REJECTED", "实体状态冲突"));

        assertEquals("DELETE_FAILED", lifecycleOf());
        assertEquals("ADAPTER_REJECTED", jdbc.queryForObject(
                "SELECT failure_code FROM exercise_aircraft WHERE id = ?",
                String.class, aircraftId));
        // 责任席位仍持有（不得隐藏仍存在的目标）
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM aircraft_assignment WHERE aircraft_id = ? "
                        + "AND ended_at IS NULL", Integer.class, aircraftId));

        // 重试复用原 Outbox，避免相同引擎幂等键违反唯一约束。
        transactionTemplate.execute(status -> saga.retry(aircraftId));
        assertEquals("DELETE_REQUESTED", lifecycleOf());
        assertEquals(1L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'AIRCRAFT_DELETE' "
                        + "AND exercise_group_id = ?", Long.class, groupId));
    }

    @Test
    void givenAckLostButEntityAbsentThenReconciliationCompletesDeleted() {
        newAircraft("ACTIVE");
        transactionTemplate.execute(status -> saga.requestDelete(caller(), aircraftId, null));

        // 丢 ACK：对账发现实体不存在 → 直接补记 DELETED，不猜测
        transactionTemplate.execute(status -> saga.reconcileUnknownResult(aircraftId, false));
        assertEquals("DELETED", lifecycleOf());
    }

    @Test
    void givenPlatformRestartsDuringDeleteThenSagaResumesWithoutDuplicateSideEffect() {
        newAircraft("ACTIVE");
        transactionTemplate.execute(status -> saga.requestDelete(caller(), aircraftId, null));
        long outboxAfterFirst = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'AIRCRAFT_DELETE'",
                Long.class);

        // 重启恢复路径：对账发现实体仍存在 → 转 DELETE_FAILED 可重试，不重复删
        transactionTemplate.execute(status -> saga.reconcileUnknownResult(aircraftId, true));
        assertEquals("DELETE_FAILED", lifecycleOf());

        // 重试一次（幂等键航空器维度，与首删一致；评审 D4）
        transactionTemplate.execute(status -> saga.retry(aircraftId));
        assertEquals("DELETE_REQUESTED", lifecycleOf());
        assertEquals(outboxAfterFirst, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'AIRCRAFT_DELETE'",
                Long.class), "恢复后复用原删除动作");
    }

    @Test
    void givenDeleteOutboxWhenEnqueuedThenRoutedToEngineInstanceWithStableIdempotencyKey() {
        // 评审 D4：routeTo 引擎实例 + 幂等键稳定（首删与重试同键）
        newAircraft("ACTIVE");
        transactionTemplate.execute(status -> saga.requestDelete(caller(), aircraftId, null));

        Map<String, Object> first = jdbc.queryForMap(
                "SELECT engine_instance_id, request_id, idempotency_key FROM outbox_event "
                        + "WHERE event_type = 'AIRCRAFT_DELETE' AND exercise_group_id = ? "
                        + "ORDER BY created_at DESC", groupId);
        assertNotNull(first.get("idempotency_key"), "删除 Outbox 必须携带幂等键");
        assertEquals("aircraft-delete:" + aircraftId, first.get("idempotency_key"));
    }

    @Test
    void givenPlannedAircraftWhenDeletedThenCompletedLocallyWithoutAdapterOutbox() {
        newAircraft("PLANNED");

        Map<String, Object> result = transactionTemplate.execute(status ->
                saga.requestDelete(caller(), aircraftId, null));

        assertEquals("DELETED", result.get("state"));
        assertEquals(Boolean.FALSE, result.get("adapterPending"));
        assertEquals("DELETED", lifecycleOf());
        assertEquals(0L, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type = 'AIRCRAFT_DELETE' "
                        + "AND exercise_group_id = ?", Long.class, groupId),
                "未出现航空器删除不写 Adapter Outbox（详细设计 5.2.9）");
    }

    @Test
    void givenOrchestratorCancelWhenEntityStillExistsThenRestoresActive() {
        newAircraft("ACTIVE");
        transactionTemplate.execute(status -> saga.requestDelete(caller(), aircraftId, null));
        transactionTemplate.execute(status ->
                saga.markDeleteFailed(aircraftId, "ADAPTER_ACK_TIMEOUT", "超时"));

        // 编排方取消：实体仍存在 → 恢复 ACTIVE
        transactionTemplate.execute(status -> saga.cancelFailedDelete(aircraftId, true));
        assertEquals("ACTIVE", lifecycleOf());

        // Adapter 已不存在时取消 → 只能补记 DELETED
        jdbc.update("UPDATE exercise_aircraft SET lifecycle = 'DELETE_FAILED' WHERE id = ?",
                aircraftId);
        transactionTemplate.execute(status -> saga.cancelFailedDelete(aircraftId, false));
        assertEquals("DELETED", lifecycleOf());
    }
}

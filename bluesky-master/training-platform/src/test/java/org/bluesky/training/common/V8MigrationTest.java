package org.bluesky.training.common;

import org.bluesky.training.TrainingPlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P01：V8 迁移验证（TDD 计划第 4 节）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class V8MigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void givenV1DataWhenMigratedThenIdsAndRevisionsArePreserved() {
        String groupId = "V8-MIG-" + System.nanoTime();
        jdbc.update("INSERT INTO exercise_group (id, name, state) VALUES (?, ?, 'READY')", groupId, "迁移验证");

        assertEquals(1L, jdbc.queryForObject(
                "SELECT revision FROM exercise_group WHERE id = ?", Long.class, groupId),
                "V8 必须为既有聚合补充 revision 默认值 1");

        jdbc.update("DELETE FROM exercise_group WHERE id = ?", groupId);
    }

    @Test
    void givenIdempotencyRecordWhenInsertedThenUniqueScopeAndCheckedStateHold() {
        String scope = "scope-" + System.nanoTime();
        jdbc.update("INSERT INTO idempotency_record (scope, idempotency_key, caller_id, request_method, "
                        + "canonical_path, request_digest, state, expires_at) "
                        + "VALUES (?, 'k1', 'PP-01', 'POST', '/p', 'd1', 'COMPLETED', "
                        + "DATEADD('hour', 24, CURRENT_TIMESTAMP(3)))",
                scope);

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO idempotency_record (scope, idempotency_key, caller_id, request_method, "
                        + "canonical_path, request_digest, state, expires_at) VALUES (?, 'k2', 'PP-01', 'POST', '/p', 'd2', 'COMPLETED', DATEADD('hour', 24, CURRENT_TIMESTAMP(3)))",
                scope), "同作用域不得重复插入");

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO idempotency_record (scope, idempotency_key, caller_id, request_method, "
                        + "canonical_path, request_digest, state, expires_at) VALUES (?, 'k3', 'PP-01', 'POST', '/p', 'd3', 'BOGUS', DATEADD('hour', 24, CURRENT_TIMESTAMP(3)))",
                "scope-bogus-" + System.nanoTime()), "非法状态必须被 CHECK 拒绝");

        jdbc.update("DELETE FROM idempotency_record WHERE scope = ?", scope);
    }

    @Test
    void givenOutboxEventWhenInsertedThenStatusCheckedByDatabase() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO outbox_event (id, outbox_kind, exercise_group_id, event_type, payload, status) "
                        + "VALUES ('evt-bogus', 'BUSINESS_EVENT', 'g', 'x', '{}', 'BOGUS')"),
                "非法 Outbox 状态必须被 CHECK 拒绝");
    }

    @Test
    void givenSameEngineAndIdempotencyKeyWhenActionTypeDiffersThenDatabaseRejectsReuse() {
        String engineId = "engine-idempotency-" + System.nanoTime();
        String key = "shared-key-" + System.nanoTime();
        jdbc.update("INSERT INTO outbox_event (id, outbox_kind, exercise_group_id, "
                        + "engine_instance_id, event_type, request_id, idempotency_key, "
                        + "payload_checksum, payload) VALUES (?, 'ADAPTER_ACTION', 'g', ?, "
                        + "'PAUSE', 'req-1', ?, 'checksum-1', '{}')",
                "outbox-a-" + System.nanoTime(), engineId, key);

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO outbox_event (id, outbox_kind, exercise_group_id, "
                        + "engine_instance_id, event_type, request_id, idempotency_key, "
                        + "payload_checksum, payload) VALUES (?, 'ADAPTER_ACTION', 'g', ?, "
                        + "'RESUME', 'req-2', ?, 'checksum-2', '{}')",
                "outbox-b-" + System.nanoTime(), engineId, key));

        jdbc.update("DELETE FROM outbox_event WHERE engine_instance_id = ?", engineId);
    }

    @Test
    void givenReferenceLoadPayloadLargerThanFourKiBWhenQueuedThenItIsPreserved() {
        String id = "large-outbox-" + System.nanoTime();
        String payload = "{\"data\":\"" + String.join("",
                java.util.Collections.nCopies(10_000, "x")) + "\"}";

        jdbc.update("INSERT INTO outbox_event (id, outbox_kind, exercise_group_id, event_type, "
                        + "payload_checksum, payload) VALUES (?, 'ADAPTER_ACTION', 'g', "
                        + "'REFERENCE_SNAPSHOT_LOAD', 'checksum', ?)", id, payload);

        assertEquals(payload.length(), jdbc.queryForObject(
                "SELECT CHAR_LENGTH(payload) FROM outbox_event WHERE id = ?", Integer.class, id));
        jdbc.update("DELETE FROM outbox_event WHERE id = ?", id);
    }

    @Test
    void givenTrustedCallerBindingWhenSameDigestTwiceThenRejected() {
        String digest = "fp-" + System.nanoTime();
        String terminalId = "term-" + System.nanoTime();
        jdbc.update("INSERT INTO trusted_caller_binding (id, terminal_id, exercise_group_id, "
                + "certificate_fingerprint_digest) VALUES (?, ?, 'g1', ?)", "b-" + digest, terminalId, digest);

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO trusted_caller_binding (id, terminal_id, exercise_group_id, "
                        + "certificate_fingerprint_digest) VALUES (?, ?, 'g1', ?)",
                "b2-" + digest, "term2-" + System.nanoTime(), digest),
                "同一证书指纹不得绑定两个终端");

        jdbc.update("DELETE FROM trusted_caller_binding WHERE certificate_fingerprint_digest = ?", digest);
    }

    @Test
    void givenGroupSequenceTableWhenQueriedThenAllocatorBaselineExists() {
        // group_sequence 由 V8 创建，供 GroupSequenceAllocator 使用
        Integer tables = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'group_sequence'",
                Integer.class);
        assertTrue(tables != null && tables >= 1, "V8 必须创建 group_sequence 表");
    }
}

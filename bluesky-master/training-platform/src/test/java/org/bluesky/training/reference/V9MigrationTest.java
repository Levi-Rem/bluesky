package org.bluesky.training.reference;

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

/** P03：V9 迁移验证（TDD 计划第 4 节）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class V9MigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void givenLegacyDefaultGroupWhenMigratedThenReferenceAndEngineFieldsAreNullableButValid() {
        String groupId = "V9-MIG-" + System.nanoTime();
        jdbc.update("INSERT INTO exercise_group (id, name, state) VALUES (?, ?, 'READY')", groupId, "迁移验证");

        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM exercise_group WHERE id = ? AND reference_snapshot_id IS NULL "
                        + "AND engine_instance_id IS NULL AND state_reason IS NULL",
                Long.class, groupId), "V9 扩展列必须可空且默认为 NULL");

        jdbc.update("DELETE FROM exercise_group WHERE id = ?", groupId);
    }

    @Test
    void givenEngineInstanceWhenInsertedThenStateCheckedByDatabase() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO engine_instance (id, exercise_group_id, control_endpoint, state_endpoint, state) "
                        + "VALUES ('ei-bogus', 'g', 'tcp://127.0.0.1:9000', 'tcp://127.0.0.1:9001', 'BOGUS')"),
                "非法引擎状态必须被 CHECK 拒绝");
    }

    @Test
    void givenReferenceSnapshotWhenInsertedThenStatusCheckedByDatabase() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO reference_snapshot (id, version_label, schema_version, manifest_json, "
                        + "manifest_checksum, store_path, status) "
                        + "VALUES ('rs-bogus', 'v0', 'reference-manifest/1', '{}', '00', '/tmp/x', 'ARCHIVED')"),
                "非法快照状态必须被 CHECK 拒绝");
    }
}

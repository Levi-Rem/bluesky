package org.bluesky.training.exercise;

import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.common.CallerContext;
import org.bluesky.training.common.V2DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P05：运维身份创建/查询训练组（详细设计 2.2 §9.2）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class ExerciseGroupProvisioningServiceTest {

    private static final CallerContext OPS =
            CallerContext.operations("ops-1", "ops-digest");

    @Autowired
    private ExerciseGroupProvisioningService provisioningService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void givenOpsIdentityWhenCreatingGroupThenGroupIsReadyAndStartRequiresSnapshotAndTerminals() {
        Map<String, Object> created = provisioningService.createGroup(OPS, "隔离验证组");
        String groupId = String.valueOf(created.get("id"));
        assertEquals("READY", created.get("state"));
        assertEquals(1L, ((Number) created.get("revision")).longValue());

        // 未固定快照或组内无启用终端时 start 拒绝
        V2DomainException noSnapshot = assertThrows(V2DomainException.class,
                () -> provisioningService.startReadiness(groupId));
        assertEquals("REFERENCE_NOT_FOUND", noSnapshot.code());

        jdbc.update("UPDATE exercise_group SET reference_snapshot_id = ? WHERE id = ?",
                "snapshot-placeholder", groupId);
        V2DomainException noTerminals = assertThrows(V2DomainException.class,
                () -> provisioningService.startReadiness(groupId));
        assertEquals("TERMINAL_NOT_FOUND", noTerminals.code());

        jdbc.update("INSERT INTO workstation_terminal (id, name, terminal_type, exercise_group_id) "
                + "VALUES (?, '机长席', 'PSEUDO_PILOT', ?)",
                "term-iso-" + System.nanoTime(), groupId);
        assertTrue(provisioningService.startReadiness(groupId), "快照与终端齐备后 start 前置满足");
    }

    @Test
    void givenNonOpsIdentityWhenCreatingGroupThenForbidden() {
        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> provisioningService.createGroup(
                        CallerContext.terminal("PP-01", "g", "d"), "越权组"));
        assertEquals("TRUSTED_IDENTITY_REJECTED", failure.code());
    }

    @Test
    void givenExistingGroupsWhenListedThenNamesAndStatesPresent() {
        Map<String, Object> created = provisioningService.createGroup(OPS, "列表验证组");

        List<Map<String, Object>> groups = provisioningService.listGroups(OPS);
        assertTrue(groups.stream().anyMatch(group ->
                created.get("id").equals(group.get("id")) && "READY".equals(group.get("state"))));
    }
}

package org.bluesky.training.common;

import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.persistence.TrustedCallerBindingMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** P02：终端与受信绑定的运维管理（详细设计 2.2 §5.1/§9.2/§14）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class TerminalProvisioningServiceTest {

    private static final CallerContext OPS = CallerContext.operations("ops-1", "ops-digest");

    @Autowired
    private TerminalProvisioningService provisioningService;

    @Autowired
    private TrustedCallerBindingMapper bindingMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private String newGroup() {
        String groupId = "group-prov-" + UUID.randomUUID();
        jdbc.update("INSERT INTO exercise_group (id, name, state) VALUES (?, ?, 'READY')",
                groupId, "管理验证组");
        return groupId;
    }

    @Test
    void givenOpsIdentityWhenCreatingTerminalThenFrequencyIsUniqueInGroup() {
        String groupId = newGroup();

        Map<String, Object> created = provisioningService.createTerminal(
                OPS, groupId, "机长席-A", new BigDecimal("118.350"), "IMP");
        assertEquals(groupId, created.get("exerciseGroupId"));
        assertEquals(1L, ((Number) created.get("revision")).longValue());

        V2DomainException duplicate = assertThrows(V2DomainException.class,
                () -> provisioningService.createTerminal(
                        OPS, groupId, "机长席-B", new BigDecimal("118.350"), "MET"));
        assertEquals(409, duplicate.httpStatus());
        assertEquals("TERMINAL_FREQUENCY_IN_USE", duplicate.code());

        // 同一频率在不同训练组内允许
        String otherGroup = newGroup();
        Map<String, Object> other = provisioningService.createTerminal(
                OPS, otherGroup, "机长席-C", new BigDecimal("118.350"), "IMP");
        assertEquals(otherGroup, other.get("exerciseGroupId"));
    }

    @Test
    void givenTerminalCertificateWhenManagingTerminalsThenForbidden() {
        String groupId = newGroup();
        CallerContext terminal = CallerContext.terminal("PP-01", groupId, "digest");

        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> provisioningService.createTerminal(
                        terminal, groupId, "越权终端", new BigDecimal("121.500"), "IMP"));
        assertEquals(403, failure.httpStatus());
        assertEquals("TRUSTED_IDENTITY_REJECTED", failure.code());
    }

    @Test
    void givenUnknownGroupWhenCreatingTerminalThenNotFound() {
        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> provisioningService.createTerminal(
                        OPS, "group-missing-" + UUID.randomUUID(),
                        "孤儿终端", new BigDecimal("125.200"), "IMP"));
        assertEquals(404, failure.httpStatus());
        assertEquals("TERMINAL_NOT_FOUND", failure.code());
    }

    @Test
    void givenBoundFingerprintWhenReboundThenDigestUpdatedAtomically() {
        String groupId = newGroup();
        Map<String, Object> terminal = provisioningService.createTerminal(
                OPS, groupId, "换绑终端", new BigDecimal("122.100"), "IMP");
        String terminalId = String.valueOf(terminal.get("id"));

        provisioningService.bindCallerCertificate(OPS, terminalId, "digest-old");
        provisioningService.bindCallerCertificate(OPS, terminalId, "digest-new");

        assertEquals("digest-new",
                bindingMapper.findByTerminalId(terminalId).getCertificateFingerprintDigest());

        // 指纹已被占用时不允许绑到第二个终端
        Map<String, Object> second = provisioningService.createTerminal(
                OPS, groupId, "冲突终端", new BigDecimal("122.200"), "IMP");
        V2DomainException conflict = assertThrows(V2DomainException.class,
                () -> provisioningService.bindCallerCertificate(
                        OPS, String.valueOf(second.get("id")), "digest-new"));
        assertEquals("TRUSTED_IDENTITY_REJECTED", conflict.code());
    }

    @Test
    void givenTerminalWhenUpdatedThenEnabledAndUnitModeToggle() {
        String groupId = newGroup();
        Map<String, Object> terminal = provisioningService.createTerminal(
                OPS, groupId, "更新终端", new BigDecimal("123.100"), "IMP");
        String terminalId = String.valueOf(terminal.get("id"));

        Map<String, Object> updated = provisioningService.updateTerminal(
                OPS, terminalId, "更新终端-2", "MET", false);
        assertEquals(false, updated.get("enabled"));
        assertEquals("MET", updated.get("unitMode"));

        List<Map<String, Object>> listed = provisioningService.listByGroup(OPS, groupId);
        assertEquals(true, listed.stream().anyMatch(row -> terminalId.equals(row.get("id"))));
    }

    @Test
    void givenUnknownTerminalWhenUpdatedThenNotFound() {
        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> provisioningService.updateTerminal(
                        OPS, "terminal-missing-" + UUID.randomUUID(), null, null, true));
        assertEquals(404, failure.httpStatus());
        assertEquals("TERMINAL_NOT_FOUND", failure.code());
    }
}

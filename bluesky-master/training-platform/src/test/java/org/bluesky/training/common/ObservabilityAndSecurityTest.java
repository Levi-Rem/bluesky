package org.bluesky.training.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** P20：可观测低基数、日志脱敏、ZeroMQ fail-fast、升级门禁、v1 退役扫描。 */
class ObservabilityAndSecurityTest {

    // ---------------------------------------------------------------- TrainingMetrics

    @Test
    void givenMetricsTagsWhenBuiltThenOnlyLowCardinalityEnumsAllowed() {
        Map<String, String> tags = TrainingMetrics.instructionTransitionTags("COMPLETED",
                "QUEUE_CLEARED_BY_REPLACE");
        assertEquals("COMPLETED", tags.get("state"));
        assertEquals("QUEUE_CLEARED_BY_REPLACE", tags.get("reason"));

        // 高基数拒绝：UUID / 终端 ID / 路径形态都不允许作为标签
        assertThrows(IllegalArgumentException.class,
                () -> TrainingMetrics.instructionTransitionTags("group-uuid-123", null));
        assertThrows(IllegalArgumentException.class,
                () -> TrainingMetrics.instructionTransitionTags("COMPLETED",
                        "instruction-6f2a8c31-9d4b"));
        assertThrows(IllegalArgumentException.class,
                () -> TrainingMetrics.instructionTransitionTags("PENDING", null),
                "v1 状态不入指标");
        assertEquals("COMPLETED", TrainingMetrics.handoverTags("COMPLETED").get("outcome"));
        assertThrows(IllegalArgumentException.class,
                () -> TrainingMetrics.handoverTags("PP-01"));
        assertEquals("PAUSED", TrainingMetrics.recoveryTags("PAUSED").get("outcome"));
    }

    // ---------------------------------------------------------------- SecretRedactor

    @Test
    void givenSecretMaterialWhenRedactedThenNothingLeaks() {
        String config = "spring.datasource.password=bluesky123 curve-secret-key=ABCDEF "
                + "BS_TRUST_TOKEN:Bearer abc.def";
        String redacted = SecretRedactor.redactConfiguration(config);
        assertFalse(redacted.contains("bluesky123"), "数据库密码不得出现");
        assertFalse(redacted.contains("ABCDEF"), "Curve 密钥不得出现");
        assertFalse(redacted.contains("abc.def"), "Bearer 令牌不得出现");
        assertTrue(redacted.contains("***REDACTED***"));
        assertFalse(SecretRedactor.containsSecret(redacted), "脱敏后不得再检出密钥形态");

        String uri = "jdbc:mysql://h/db?user=u&password=secret&useSSL=false";
        assertFalse(SecretRedactor.redactUri(uri).contains("secret"));
        assertFalse(SecretRedactor.containsSecret(SecretRedactor.redactUri(uri)));

        // 指纹：前 8 后 4，完整指纹不得保留
        String fingerprint = "0123456789abcdef";
        assertEquals("01234567…cdef", SecretRedactor.redactFingerprint(fingerprint));
        assertFalse(SecretRedactor.redactFingerprint(fingerprint).equals(fingerprint));
        assertEquals("short", SecretRedactor.redactFingerprint("short"));
    }

    // ---------------------------------------------------------------- ZeroMqSecurityValidator

    @Test
    void givenNonLoopbackTcpWithoutCurveWhenValidatedThenFailFast() {
        ZeroMqSecurityValidator validator = new ZeroMqSecurityValidator();

        // loopback 允许（无需 Curve）
        assertDoesNotThrow(() -> validator.validateLoopbackOrCurve("tcp://127.0.0.1:5555", false));
        assertDoesNotThrow(() -> validator.validateLoopbackOrCurve("tcp://localhost:5555", false));
        // 非 loopback + Curve 允许
        assertDoesNotThrow(() -> validator.validateLoopbackOrCurve("tcp://10.0.0.5:5555", true));

        // 非 loopback 无 Curve：启动失败（fail-fast，详细设计 14.9）
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> validator.validateLoopbackOrCurve("tcp://10.0.0.5:5555", false));
        assertTrue(failure.getMessage().contains("CurveZMQ"));
        assertThrows(IllegalStateException.class,
                () -> validator.validateLoopbackOrCurve("tcp://192.168.1.10:9000", false));
        assertThrows(IllegalStateException.class,
                () -> validator.validateLoopbackOrCurve(" tcp:// ", false));
        assertThrows(IllegalStateException.class,
                () -> validator.validateLoopbackOrCurve(null, true));
    }

    // ---------------------------------------------------------------- UpgradeReadinessService

    @Test
    void givenGroupStatesWhenUpgradeCheckedThenOnlyReadyOrEndedPass() {
        UpgradeReadinessService service = new UpgradeReadinessService();

        Map<String, String> upgradable = new LinkedHashMap<>();
        upgradable.put("g1", "READY");
        upgradable.put("g2", "ENDED");
        assertDoesNotThrow(() -> service.assertUpgradeAllowed(upgradable));
        assertDoesNotThrow(() -> service.assertUpgradeAllowed(null));

        // 全部活动状态参数化拒绝
        for (String active : UpgradeReadinessService.ACTIVE_STATES) {
            Map<String, String> blocking = new LinkedHashMap<>();
            blocking.put("g1", active);
            V2DomainException failure = assertThrows(V2DomainException.class,
                    () -> service.assertUpgradeAllowed(blocking),
                    active + " 应拒绝升级");
            assertEquals(409, failure.httpStatus());
            assertTrue(failure.getMessage().contains(active));
        }
        // 未知状态同样拒绝
        Map<String, String> unknown = new LinkedHashMap<>();
        unknown.put("g1", "BOOTING");
        assertThrows(V2DomainException.class, () -> service.assertUpgradeAllowed(unknown));
    }

    // ---------------------------------------------------------------- V1RetirementVerifier

    @Test
    void givenV1ReferencesWhenScannedThenViolationsListed(@TempDir Path tempDir)
            throws Exception {
        V1RetirementVerifier verifier = new V1RetirementVerifier();

        // 干净目录：通过
        Path clean = Files.createDirectories(tempDir.resolve("clean"));
        Files.write(clean.resolve("app.ts"), "fetch('/api/v2/workstations')".getBytes("UTF-8"));
        Files.createDirectories(clean.resolve("sub"));
        Files.write(clean.resolve("sub/ctl.java"),
                "@RestController @RequestMapping(\"/api/v2\")".getBytes("UTF-8"));
        assertDoesNotThrow(() -> verifier.assertNoV1Dependencies(clean, clean, clean));

        // 前端 v1 引用
        Path frontend = Files.createDirectories(tempDir.resolve("frontend"));
        Files.write(frontend.resolve("api.ts"), "fetch('/api/v1/aircraft')".getBytes("UTF-8"));
        assertTrue(verifier.scanFrontendRoutes(frontend).size() == 1);

        // Spring v1 映射
        Path java = Files.createDirectories(tempDir.resolve("java"));
        Files.write(java.resolve("OldController.java"),
                "@RestController class X { @RequestMapping(\"/api/v1/exercise-groups\") }"
                        .getBytes("UTF-8"));
        assertTrue(verifier.scanSpringMappings(java).size() == 1);

        // Adapter Protocol 1.0
        Path adapter = Files.createDirectories(tempDir.resolve("adapter"));
        Files.write(adapter.resolve("protocol.py"), "\"protocolVersion\": \"1.0\""
                .getBytes("UTF-8"));
        assertTrue(verifier.scanProtocolVersion(adapter).size() == 1);

        // 汇总断言失败并列出全部违规
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> verifier.assertNoV1Dependencies(frontend, java, adapter));
        assertTrue(failure.getMessage().contains("第二版不得发布"));
        assertTrue(failure.getMessage().contains("前端引用 v1 API"));
        assertTrue(failure.getMessage().contains("Spring 映射仍指向 v1"));
        assertTrue(failure.getMessage().contains("Protocol 1.0"));
    }
}

package org.bluesky.training.common;

import org.bluesky.training.TrainingPlatformApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** P01：写审计与指纹脱敏（详细设计 14.10）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class AuditServiceTest {

    @Autowired
    private AuditService auditService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void givenMutationWhenRecordedThenCallerAndRedactedFingerprintAreStored() {
        CallerContext caller = CallerContext.terminal("PP-01", "group-audit", "0123456789abcdef");
        String requestId = "req-audit-1";

        transactionTemplate.execute(status -> {
            auditService.recordMutation(caller, "INSTRUCTION_SUBMIT", "instruction-1",
                    requestId, "key-1", true, "{\"type\":\"HDG\"}");
            return null;
        });

        String storedDigest = jdbc.queryForObject(
                "SELECT certificate_fingerprint_digest FROM audit_record WHERE request_id = ?",
                String.class, requestId);
        assertEquals(AuditService.redactFingerprint("0123456789abcdef"), storedDigest);
        assertNotEquals("0123456789abcdef", storedDigest, "审计不得保存完整指纹");
        assertEquals("TERMINAL", jdbc.queryForObject(
                "SELECT caller_type FROM audit_record WHERE request_id = ?", String.class, requestId));
    }

    @Test
    void givenShortFingerprintWhenRedactedThenKeptAsIs() {
        assertEquals("abcd", AuditService.redactFingerprint("abcd"));
        assertEquals("01234567…cdef", AuditService.redactFingerprint("0123456789abcdef"));
    }
}

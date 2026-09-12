package org.bluesky.training.common;

import org.bluesky.training.TrainingPlatformApplication;
import org.bluesky.training.persistence.TrustedCallerBindingMapper;
import org.bluesky.training.persistence.TrustedCallerBindingRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** P02：证书指纹绑定解析（详细设计 14.7）。 */
@SpringBootTest(classes = TrainingPlatformApplication.class)
@ActiveProfiles("test")
class TrustedCallerBindingServiceTest {

    @Autowired
    private TrustedCallerBindingService bindingService;

    @Autowired
    private TrustedCallerBindingMapper bindingMapper;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private TrustedCallerBindingRow insertBinding(String terminalId, String groupId,
                                                  String digest, boolean enabled) {
        TrustedCallerBindingRow row = new TrustedCallerBindingRow();
        row.setId(UUID.randomUUID().toString());
        row.setTerminalId(terminalId);
        row.setExerciseGroupId(groupId);
        row.setCertificateFingerprintDigest(digest);
        row.setEnabled(enabled);
        transactionTemplate.execute(status -> {
            bindingMapper.insert(row);
            return null;
        });
        return row;
    }

    @Test
    void givenBoundFingerprintWhenResolvedThenRowReturnedAndLastSeenTouched() {
        String digest = "digest-" + UUID.randomUUID();
        insertBinding("PP-B1", "group-binding-1", digest, true);

        TrustedCallerBindingRow resolved = transactionTemplate.execute(
                status -> bindingService.resolveByFingerprintDigest(digest));

        assertNotNull(resolved);
        assertEquals("PP-B1", resolved.getTerminalId());
        bindingService.touchLastSeen(resolved.getId());
        assertNotNull(bindingMapper.findById(resolved.getId()).getLastSeenAt());
    }

    @Test
    void givenUnknownFingerprintWhenResolvedThenRejected() {
        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> bindingService.resolveByFingerprintDigest("digest-unknown-" + UUID.randomUUID()));
        assertEquals(403, failure.httpStatus());
        assertEquals("TRUSTED_IDENTITY_REJECTED", failure.code());
    }

    @Test
    void givenDisabledBindingWhenAssertedThenRejected() {
        String digest = "digest-" + UUID.randomUUID();
        TrustedCallerBindingRow row = insertBinding("PP-B2", "group-binding-2", digest, false);

        TrustedCallerBindingRow resolved = bindingService.resolveByFingerprintDigest(digest);
        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> bindingService.assertEnabled(resolved));
        assertEquals(403, failure.httpStatus());
        assertEquals("TRUSTED_IDENTITY_REJECTED", failure.code());
        assertEquals("PP-B2", row.getTerminalId());
    }

    @Test
    void givenMismatchedTerminalWhenAssertedThenRejected() {
        String digest = "digest-" + UUID.randomUUID();
        TrustedCallerBindingRow resolved = bindingService.resolveByFingerprintDigest(
                insertBinding("PP-B3", "group-binding-3", digest, true)
                        .getCertificateFingerprintDigest());

        V2DomainException failure = assertThrows(V2DomainException.class,
                () -> bindingService.assertMatchesTerminal(resolved, "PP-OTHER"));
        assertEquals("TRUSTED_IDENTITY_REJECTED", failure.code());
    }
}

package org.bluesky.training.common;

import org.bluesky.training.persistence.AuditRecordMapper;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** P01：写审计（详细设计 14.10）；审计只保存证书指纹摘要的再脱敏形式。 */
@Service
public class AuditService {

    private final AuditRecordMapper mapper;

    public AuditService(AuditRecordMapper mapper) {
        this.mapper = mapper;
    }

    public void recordMutation(CallerContext caller, String action, String entityId,
                               String requestId, String idempotencyKey, boolean success, String detail) {
        mapper.insert(
                UUID.randomUUID().toString(),
                caller == null || caller.callerType() == null ? "UNKNOWN" : caller.callerType().name(),
                caller == null ? null : caller.callerId(),
                caller == null ? null : caller.terminalId(),
                caller == null ? null : caller.exerciseGroupId(),
                action,
                entityId,
                requestId,
                idempotencyKey,
                redactFingerprint(caller == null ? null : caller.certificateFingerprintDigest()),
                success,
                detail);
    }

    public static String redactFingerprint(String digest) {
        if (digest == null || digest.length() <= 12) {
            return digest;
        }
        return digest.substring(0, 8) + "…" + digest.substring(digest.length() - 4);
    }
}

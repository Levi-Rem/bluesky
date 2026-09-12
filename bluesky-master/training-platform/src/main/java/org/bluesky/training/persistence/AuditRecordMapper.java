package org.bluesky.training.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/** P01：写审计持久化（V8 audit_record）。 */
public interface AuditRecordMapper {

    @Insert("INSERT INTO audit_record (id, caller_type, caller_id, terminal_id, exercise_group_id, "
            + "action, entity_id, request_id, idempotency_key, certificate_fingerprint_digest, "
            + "success, detail) "
            + "VALUES (#{id}, #{callerType}, #{callerId}, #{terminalId}, #{exerciseGroupId}, "
            + "#{action}, #{entityId}, #{requestId}, #{idempotencyKey}, #{fingerprintDigest}, "
            + "#{success}, #{detail})")
    int insert(@Param("id") String id,
               @Param("callerType") String callerType,
               @Param("callerId") String callerId,
               @Param("terminalId") String terminalId,
               @Param("exerciseGroupId") String exerciseGroupId,
               @Param("action") String action,
               @Param("entityId") String entityId,
               @Param("requestId") String requestId,
               @Param("idempotencyKey") String idempotencyKey,
               @Param("fingerprintDigest") String fingerprintDigest,
               @Param("success") boolean success,
               @Param("detail") String detail);
}

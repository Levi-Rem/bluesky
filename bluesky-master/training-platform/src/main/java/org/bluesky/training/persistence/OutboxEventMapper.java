package org.bluesky.training.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.sql.Timestamp;
import java.util.List;

/** P01：Outbox 持久化与条件认领（V8 outbox_event）。 */
public interface OutboxEventMapper {

    String COLUMNS = "id, outbox_kind, exercise_group_id, engine_instance_id, event_type, "
            + "request_id, idempotency_key, payload_checksum, payload, status, "
            + "attempt_count, max_attempts, next_attempt_at, claimed_by, claimed_at";

    @Insert("INSERT INTO outbox_event (id, outbox_kind, exercise_group_id, engine_instance_id, "
            + "event_type, request_id, idempotency_key, payload_checksum, payload) "
            + "VALUES (#{id}, #{outboxKind}, #{exerciseGroupId}, #{engineInstanceId}, "
            + "#{eventType}, #{requestId}, #{idempotencyKey}, #{payloadChecksum}, #{payload})")
    int insert(OutboxEventRow row);

    @Select("SELECT " + COLUMNS + " FROM outbox_event WHERE id = #{id}")
    OutboxEventRow findById(@Param("id") String id);

    @Update("UPDATE outbox_event SET status = 'PENDING', attempt_count = 0, "
            + "next_attempt_at = CURRENT_TIMESTAMP(3), claimed_by = NULL, claimed_at = NULL, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE outbox_kind = 'ADAPTER_ACTION' "
            + "AND (engine_instance_id = #{engineInstanceId} OR (engine_instance_id IS NULL AND #{engineInstanceId} IS NULL)) AND idempotency_key = #{idempotencyKey} "
            + "AND status IN ('PENDING', 'CONFIRMED', 'FAILED')")
    int retryAdapterAction(@Param("engineInstanceId") String engineInstanceId,
                           @Param("idempotencyKey") String idempotencyKey);

    @Update("UPDATE outbox_event SET status='FAILED',claimed_by=NULL,claimed_at=NULL WHERE event_type='INSTRUCTION_APPLY' AND idempotency_key=#{key} AND status IN ('PENDING','SENT')")
    int terminateInstructionApply(@Param("key") String key);

    @Select("SELECT COUNT(*) FROM outbox_event WHERE (engine_instance_id=#{instance} OR (engine_instance_id IS NULL AND #{instance} IS NULL)) AND idempotency_key=#{key}")
    int countAdapterAction(@Param("instance") String instance,@Param("key") String key);

    @Select("SELECT id FROM outbox_event "
            + "WHERE status = 'PENDING' AND claimed_by IS NULL "
            + "AND next_attempt_at <= CURRENT_TIMESTAMP(3) "
            + "ORDER BY created_at LIMIT #{limit}")
    List<String> findClaimableIds(@Param("limit") int limit);

    @Select("SELECT id FROM outbox_event "
            + "WHERE status = 'PENDING' AND claimed_by IS NULL AND outbox_kind = #{kind} "
            + "AND next_attempt_at <= CURRENT_TIMESTAMP(3) "
            + "ORDER BY created_at LIMIT #{limit}")
    List<String> findClaimableIdsByKind(@Param("kind") String kind, @Param("limit") int limit);

    @Update("<script>"
            + "UPDATE outbox_event SET claimed_by = #{worker}, "
            + "claimed_at = CURRENT_TIMESTAMP(3), updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE status = 'PENDING' AND claimed_by IS NULL AND id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int claim(@Param("worker") String worker, @Param("ids") List<String> ids);

    @Select("<script>"
            + "SELECT id FROM outbox_event WHERE claimed_by = #{worker} AND id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<String> findClaimedByWorker(@Param("worker") String worker, @Param("ids") List<String> ids);

    @Update("UPDATE outbox_event SET status = 'SENT', claimed_by = NULL, claimed_at = NULL, "
            + "updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = #{id} AND claimed_by IS NOT NULL")
    int markSent(@Param("id") String id);

    @Update("UPDATE outbox_event SET status = 'CONFIRMED', claimed_by = NULL, claimed_at = NULL, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int confirm(@Param("id") String id);

    @Update("UPDATE outbox_event SET status = 'PENDING', claimed_by = NULL, claimed_at = NULL, "
            + "attempt_count = attempt_count + 1, next_attempt_at = #{nextAttemptAt}, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id} AND status <> 'FAILED'")
    int reschedule(@Param("id") String id, @Param("nextAttemptAt") Timestamp nextAttemptAt);

    @Update("UPDATE outbox_event SET status = 'FAILED', claimed_by = NULL, claimed_at = NULL, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int markFailed(@Param("id") String id);

    @Update("UPDATE outbox_event SET claimed_by = NULL, claimed_at = NULL, "
            + "updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE status = 'PENDING' AND claimed_by IS NOT NULL AND claimed_at < #{cutoff}")
    int releaseExpiredClaims(@Param("cutoff") Timestamp cutoff);
}

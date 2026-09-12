package org.bluesky.training.persistence;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.sql.Timestamp;

/** P01：幂等记录持久化（V8 idempotency_record）。 */
public interface IdempotencyRecordMapper {

    String COLUMNS = "scope, idempotency_key, caller_id, request_method, canonical_path, "
            + "request_digest, state, http_status, response_body, created_at, completed_at, expires_at";

    @Insert("INSERT INTO idempotency_record (scope, idempotency_key, caller_id, request_method, "
            + "canonical_path, request_digest, state, http_status, expires_at) "
            + "VALUES (#{scope}, #{idempotencyKey}, #{callerId}, #{requestMethod}, #{canonicalPath}, "
            + "#{requestDigest}, #{state}, #{httpStatus}, #{expiresAt})")
    int insert(IdempotencyRecordRow row);

    @Select("SELECT " + COLUMNS + " FROM idempotency_record WHERE scope = #{scope}")
    IdempotencyRecordRow find(@Param("scope") String scope);

    @Select("SELECT " + COLUMNS + " FROM idempotency_record WHERE scope = #{scope} FOR UPDATE")
    IdempotencyRecordRow findForUpdate(@Param("scope") String scope);

    @Update("UPDATE idempotency_record SET state = 'COMPLETED', http_status = #{httpStatus}, "
            + "response_body = #{responseBody}, completed_at = CURRENT_TIMESTAMP(3) "
            + "WHERE scope = #{scope}")
    int complete(@Param("scope") String scope,
                 @Param("httpStatus") int httpStatus,
                 @Param("responseBody") String responseBody);

    @Delete("DELETE FROM idempotency_record WHERE expires_at < #{cutoff}")
    int deleteExpired(@Param("cutoff") Timestamp cutoff);
}

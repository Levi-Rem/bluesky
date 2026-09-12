package org.bluesky.training.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** P02：受信终端绑定持久化（V8 trusted_caller_binding）。 */
public interface TrustedCallerBindingMapper {

    String COLUMNS = "id, terminal_id, exercise_group_id, certificate_fingerprint_digest, "
            + "enabled, bound_at, last_seen_at, revision";

    @Insert("INSERT INTO trusted_caller_binding (id, terminal_id, exercise_group_id, "
            + "certificate_fingerprint_digest, enabled) "
            + "VALUES (#{id}, #{terminalId}, #{exerciseGroupId}, #{certificateFingerprintDigest}, #{enabled})")
    int insert(TrustedCallerBindingRow row);

    @Select("SELECT " + COLUMNS + " FROM trusted_caller_binding "
            + "WHERE certificate_fingerprint_digest = #{digest}")
    TrustedCallerBindingRow findByFingerprintDigest(@Param("digest") String digest);

    @Select("SELECT " + COLUMNS + " FROM trusted_caller_binding WHERE terminal_id = #{terminalId}")
    TrustedCallerBindingRow findByTerminalId(@Param("terminalId") String terminalId);

    @Select("SELECT " + COLUMNS + " FROM trusted_caller_binding WHERE id = #{id}")
    TrustedCallerBindingRow findById(@Param("id") String id);

    @Update("UPDATE trusted_caller_binding SET certificate_fingerprint_digest = #{digest}, "
            + "revision = revision + 1, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE terminal_id = #{terminalId} AND revision = #{expectedRevision}")
    int updateDigest(@Param("terminalId") String terminalId,
                     @Param("expectedRevision") long expectedRevision,
                     @Param("digest") String digest);

    @Update("UPDATE trusted_caller_binding SET last_seen_at = CURRENT_TIMESTAMP(3) WHERE id = #{id}")
    int touchLastSeen(@Param("id") String id);
}

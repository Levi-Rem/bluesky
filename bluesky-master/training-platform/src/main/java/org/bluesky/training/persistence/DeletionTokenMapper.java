package org.bluesky.training.persistence;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.sql.Timestamp;

/**
 * P15：一次性删除确认 token 摘要（评审 P0-5）。
 * 只存 SHA-256 摘要；consume 以 consumed_at IS NULL 条件更新，
 * 返回 0 行即已消费/过期——同一 token 30 秒窗口内不可重放。
 */
public interface DeletionTokenMapper {

    @Insert("INSERT INTO deletion_confirmation_token (token_digest, aircraft_id, terminal_id, "
            + "bound_revision, expires_at) VALUES (#{digest}, #{aircraftId}, #{terminalId}, "
            + "#{boundRevision}, #{expiresAt})")
    int insertToken(@Param("digest") String digest,
                    @Param("aircraftId") String aircraftId,
                    @Param("terminalId") String terminalId,
                    @Param("boundRevision") long boundRevision,
                    @Param("expiresAt") Timestamp expiresAt);

    /** 原子消费：仅在未消费且未过期时成功；成功返回 1，重放/过期返回 0。 */
    @Update("UPDATE deletion_confirmation_token SET consumed_at = CURRENT_TIMESTAMP(3) "
            + "WHERE token_digest = #{digest} AND consumed_at IS NULL "
            + "AND expires_at > CURRENT_TIMESTAMP(3)")
    int consumeToken(@Param("digest") String digest);
}

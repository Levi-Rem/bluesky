-- P15：一次性删除确认 token 摘要表（评审 P0-5：摘要落库 + 原子消费）。
-- 只存 SHA-256 摘要不存明文；consumed_at 条件更新保证一次性；重放被拒绝。
CREATE TABLE deletion_confirmation_token (
    token_digest CHAR(64) PRIMARY KEY,
    aircraft_id VARCHAR(64) NOT NULL,
    terminal_id VARCHAR(64) NOT NULL,
    bound_revision BIGINT NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    consumed_at TIMESTAMP(3),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE INDEX idx_deletion_token_aircraft ON deletion_confirmation_token (aircraft_id);

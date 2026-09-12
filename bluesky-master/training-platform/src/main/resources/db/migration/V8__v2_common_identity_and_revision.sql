-- V8：第二版通用底座（详细设计 2.2 第 5.0、9.1、14 节）
-- 已执行迁移只读；本迁移只新增对象并为既有聚合补充 revision 审计列。

-- 1. 既有聚合补充 revision（每次成功写入递增 1）
ALTER TABLE exercise_group ADD COLUMN revision BIGINT NOT NULL DEFAULT 1;
ALTER TABLE workstation_terminal ADD COLUMN revision BIGINT NOT NULL DEFAULT 1;
ALTER TABLE exercise_aircraft ADD COLUMN revision BIGINT NOT NULL DEFAULT 1;
ALTER TABLE aircraft_instruction ADD COLUMN revision BIGINT NOT NULL DEFAULT 1;

-- 1b. 终端身份列前移（P02 需要：组内频率唯一按 DECIMAL(6,3) 精度比较）
ALTER TABLE workstation_terminal ADD COLUMN frequency DECIMAL(6,3);
ALTER TABLE workstation_terminal ADD COLUMN unit_mode VARCHAR(8) DEFAULT 'IMP';
ALTER TABLE workstation_terminal ADD COLUMN enabled TINYINT(1) NOT NULL DEFAULT 1;
ALTER TABLE workstation_terminal ADD COLUMN last_seen_at TIMESTAMP(3);
ALTER TABLE workstation_terminal ADD CONSTRAINT uq_terminal_group_frequency
    UNIQUE (exercise_group_id, frequency);

-- 2. 受信终端绑定：证书指纹 → terminalId → exerciseGroupId（详细设计 14.7）
CREATE TABLE trusted_caller_binding (
    id CHAR(36) PRIMARY KEY,
    terminal_id VARCHAR(64) NOT NULL,
    exercise_group_id VARCHAR(64) NOT NULL,
    certificate_fingerprint_digest VARCHAR(64) NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    bound_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    last_seen_at TIMESTAMP(3),
    revision BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_trusted_caller_terminal UNIQUE (terminal_id),
    CONSTRAINT uq_trusted_caller_fingerprint UNIQUE (certificate_fingerprint_digest),
    CONSTRAINT chk_trusted_caller_enabled CHECK (enabled IN (0, 1))
);

CREATE INDEX idx_trusted_caller_group ON trusted_caller_binding (exercise_group_id);

-- 3. 幂等记录（详细设计 9.1：作用域 = 受信调用方 + 方法 + 规范路径 + key）
CREATE TABLE idempotency_record (
    scope VARCHAR(128) PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    caller_id VARCHAR(64) NOT NULL,
    request_method VARCHAR(16) NOT NULL,
    canonical_path VARCHAR(256) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    http_status INT,
    response_body VARCHAR(4000),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at TIMESTAMP(3),
    expires_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT chk_idempotency_state CHECK (state IN ('RUNNING', 'COMPLETED')),
    CONSTRAINT chk_idempotency_http CHECK (http_status IS NULL OR http_status IN (200, 201, 202))
);

CREATE INDEX idx_idempotency_expiry ON idempotency_record (expires_at);

-- 4. Outbox：业务事件与 Adapter 动作与业务写入同事务（详细设计 3.3.8 / 5.0.5）
CREATE TABLE outbox_event (
    id CHAR(36) PRIMARY KEY,
    outbox_kind VARCHAR(24) NOT NULL,
    exercise_group_id VARCHAR(64) NOT NULL,
    engine_instance_id CHAR(36),
    event_type VARCHAR(64) NOT NULL,
    request_id VARCHAR(64),
    idempotency_key VARCHAR(128),
    payload_checksum VARCHAR(64) NOT NULL,
    payload MEDIUMTEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 8,
    next_attempt_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    claimed_by VARCHAR(64),
    claimed_at TIMESTAMP(3),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_outbox_kind CHECK (outbox_kind IN ('BUSINESS_EVENT', 'ADAPTER_ACTION')),
    CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING', 'SENT', 'CONFIRMED', 'FAILED')),
    CONSTRAINT uq_outbox_adapter_idempotency
        UNIQUE (engine_instance_id, idempotency_key)
);

CREATE INDEX idx_outbox_claim ON outbox_event (status, next_attempt_at);

-- 5. 写审计（详细设计 14.10：调用方、指纹摘要、requestId、幂等键）
CREATE TABLE audit_record (
    id CHAR(36) PRIMARY KEY,
    caller_type VARCHAR(24) NOT NULL,
    caller_id VARCHAR(64) NOT NULL,
    terminal_id VARCHAR(64),
    exercise_group_id VARCHAR(64),
    action VARCHAR(64) NOT NULL,
    entity_id VARCHAR(64),
    request_id VARCHAR(64),
    idempotency_key VARCHAR(128),
    certificate_fingerprint_digest VARCHAR(32),
    success TINYINT(1) NOT NULL,
    detail VARCHAR(4000),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE INDEX idx_audit_group_time ON audit_record (exercise_group_id, created_at);

-- 6. 组序号分配基线（详细设计 5.0：group_sequence 在组内由 MySQL 单调分配；
--    键宽 160 兼容 P06 终端投递序号的复合键 delivery:{epoch}:{terminalId}）
CREATE TABLE group_sequence (
    exercise_group_id VARCHAR(160) PRIMARY KEY,
    next_value BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

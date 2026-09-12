-- V9：训练组扩展、引擎实例与参考快照（详细设计 2.2 §5.1/§11）
-- 快照资源副本存平台只读目录；MySQL 只保存 manifest 与 checksum。

ALTER TABLE exercise_group ADD COLUMN state_reason VARCHAR(256);
ALTER TABLE exercise_group ADD COLUMN reference_snapshot_id CHAR(36);
ALTER TABLE exercise_group ADD COLUMN engine_instance_id CHAR(36);
ALTER TABLE exercise_group ADD COLUMN last_checkpoint_id CHAR(36);
ALTER TABLE exercise_group ADD COLUMN started_at TIMESTAMP(3);
ALTER TABLE exercise_group ADD COLUMN ended_at TIMESTAMP(3);

CREATE TABLE engine_instance (
    id CHAR(36) PRIMARY KEY,
    exercise_group_id VARCHAR(64) NOT NULL,
    control_endpoint VARCHAR(128) NOT NULL,
    state_endpoint VARCHAR(128) NOT NULL,
    protocol_version VARCHAR(8) NOT NULL DEFAULT '2.0',
    process_identifier VARCHAR(128),
    state VARCHAR(16) NOT NULL DEFAULT 'STARTING',
    last_outbound_sequence BIGINT NOT NULL DEFAULT 0,
    last_inbound_sequence BIGINT NOT NULL DEFAULT 0,
    reference_snapshot_checksum VARCHAR(64),
    last_heartbeat_at TIMESTAMP(3),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    stopped_at TIMESTAMP(3),
    revision BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT chk_engine_state CHECK (state IN ('STARTING', 'CONNECTED', 'DEGRADED', 'DISCONNECTED', 'STOPPED'))
);

CREATE INDEX idx_engine_group ON engine_instance (exercise_group_id, state);

CREATE TABLE reference_snapshot (
    id CHAR(36) PRIMARY KEY,
    version_label VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    schema_version VARCHAR(32) NOT NULL,
    manifest_json MEDIUMTEXT NOT NULL,
    manifest_checksum VARCHAR(64) NOT NULL,
    source_batch VARCHAR(128),
    store_path VARCHAR(512) NOT NULL,
    published_at TIMESTAMP(3),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    revision BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT chk_snapshot_status CHECK (status IN ('DRAFT', 'PUBLISHED'))
);

CREATE INDEX idx_snapshot_status ON reference_snapshot (status, published_at);

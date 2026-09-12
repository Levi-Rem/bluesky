-- V14：特情 profile、检查点与状态段元数据（详细设计 2.2 §7.8/§13）
-- 检查点/状态段表供 Wave 7 P19 使用，本波次先建结构。

CREATE TABLE special_operation_profile (
    id CHAR(36) PRIMARY KEY,
    operation_type VARCHAR(16) NOT NULL,
    mode_code VARCHAR(32) NOT NULL DEFAULT 'DEFAULT',
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    affected_channels VARCHAR(128) NOT NULL,
    adapter_action VARCHAR(64) NOT NULL,
    duration_seconds INT NOT NULL,
    target_altitude_ft_msl INT,
    vertical_rate_fpm INT,
    target_indicated_airspeed_kt INT,
    recovery_policy VARCHAR(48) NOT NULL,
    override_policy VARCHAR(24) NOT NULL,
    parameters_schema_version VARCHAR(32) NOT NULL,
    parameters_json VARCHAR(4000) NOT NULL DEFAULT '{}',
    effective_from TIMESTAMP(3),
    published_by VARCHAR(64),
    checksum VARCHAR(64),
    revision BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_profile_type CHECK (operation_type IN ('ID', 'DECOMP')),
    CONSTRAINT chk_profile_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT chk_profile_override CHECK (override_policy IN ('ALL_OR_NOTHING', 'PARTIAL')),
    CONSTRAINT chk_profile_recovery CHECK (recovery_policy IN (
        'RESTORE_PREVIOUS_GUIDANCE', 'RESTORE_MANAGED_TARGET', 'HOLD_RESULT')),
    CONSTRAINT chk_profile_duration CHECK (duration_seconds BETWEEN 1 AND 3600),
    CONSTRAINT chk_profile_action CHECK (adapter_action IN (
        'SPECIAL_MARK_APPLY', 'DECOMPRESSION_APPLY', 'SPECIAL_OPERATION_CLEAR'))
);

CREATE INDEX idx_profile_published ON special_operation_profile (operation_type, status);

CREATE TABLE engine_checkpoint (
    id CHAR(36) PRIMARY KEY,
    exercise_group_id VARCHAR(64) NOT NULL,
    engine_instance_id CHAR(36),
    simulation_time_seconds DECIMAL(15, 3) NOT NULL,
    format_version VARCHAR(24) NOT NULL DEFAULT 'BSSTATE2',
    reference_snapshot_checksum VARCHAR(64),
    content_checksum VARCHAR(64) NOT NULL,
    file_path VARCHAR(512) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_checkpoint_format CHECK (format_version = 'BSSTATE2')
);

CREATE INDEX idx_checkpoint_group ON engine_checkpoint (exercise_group_id, created_at);

CREATE TABLE state_segment (
    id CHAR(36) PRIMARY KEY,
    exercise_group_id VARCHAR(64) NOT NULL,
    engine_instance_id CHAR(36),
    segment_date DATE NOT NULL,
    file_name VARCHAR(128) NOT NULL,
    format_version VARCHAR(24) NOT NULL DEFAULT 'BSSTATE2',
    start_sim_seconds DECIMAL(15, 3) NOT NULL,
    end_sim_seconds DECIMAL(15, 3) NOT NULL,
    manifest_checksum VARCHAR(64) NOT NULL,
    file_checksum VARCHAR(64),
    registered_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_segment_name CHECK (file_name REGEXP '^[0-9a-fA-F-]{36}$'),
    CONSTRAINT chk_segment_format CHECK (format_version = 'BSSTATE2')
);

CREATE INDEX idx_segment_group_date ON state_segment (exercise_group_id, segment_date);

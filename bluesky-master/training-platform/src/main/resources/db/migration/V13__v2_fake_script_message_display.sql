-- V13：报告、脚本、消息、显示方案与标牌布局（详细设计 2.2 §5.5/§5.7/§8.7）

CREATE TABLE command_report (
    id CHAR(36) PRIMARY KEY,
    exercise_group_id VARCHAR(64) NOT NULL,
    instruction_id VARCHAR(64) NOT NULL,
    transition_sequence INT NOT NULL,
    terminal_id VARCHAR(64),
    from_status VARCHAR(24),
    to_status VARCHAR(24) NOT NULL,
    reason_code VARCHAR(64),
    description VARCHAR(512),
    raw_text VARCHAR(256),
    normalized_command VARCHAR(512),
    simulation_time_seconds DECIMAL(15, 3),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_command_report_transition UNIQUE (instruction_id, transition_sequence)
);

CREATE INDEX idx_command_report_query ON command_report
    (exercise_group_id, simulation_time_seconds, id);

CREATE TABLE flight_report (
    id CHAR(36) PRIMARY KEY,
    exercise_group_id VARCHAR(64) NOT NULL,
    aircraft_id VARCHAR(64) NOT NULL,
    terminal_id VARCHAR(64),
    event_type VARCHAR(32) NOT NULL,
    source_event_id VARCHAR(128) NOT NULL,
    detail VARCHAR(1024),
    simulation_time_seconds DECIMAL(15, 3),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_flight_report_source UNIQUE (aircraft_id, source_event_id),
    CONSTRAINT chk_flight_report_type CHECK (event_type IN (
        'TARGET_REACHED', 'WAYPOINT_PASSED', 'PHASE_CHANGED', 'TAKEOFF', 'LANDED',
        'MISSED_APPROACH', 'SPECIAL_OPERATION', 'ABNORMAL'))
);

CREATE INDEX idx_flight_report_query ON flight_report
    (exercise_group_id, simulation_time_seconds, id);

CREATE TABLE exercise_script_item (
    id CHAR(36) PRIMARY KEY,
    exercise_group_id VARCHAR(64) NOT NULL,
    target_terminal_ids VARCHAR(512) NOT NULL,
    trigger_simulation_time_seconds DECIMAL(15, 3) NOT NULL,
    severity VARCHAR(16) NOT NULL DEFAULT 'INFO',
    ack_required TINYINT(1) NOT NULL DEFAULT 0,
    content VARCHAR(2000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    acknowledged_at TIMESTAMP(3),
    acknowledged_by VARCHAR(64),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    revision BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT chk_script_status CHECK (status IN ('PENDING', 'DELIVERED', 'ACKNOWLEDGED', 'CANCELLED')),
    CONSTRAINT chk_script_severity CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL'))
);

CREATE INDEX idx_script_due ON exercise_script_item (exercise_group_id, status, trigger_simulation_time_seconds);

CREATE TABLE terminal_message (
    id CHAR(36) PRIMARY KEY,
    exercise_group_id VARCHAR(64) NOT NULL,
    target_terminal_id VARCHAR(64) NOT NULL,
    sender_source VARCHAR(64) NOT NULL,
    body VARCHAR(2000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'UNREAD',
    read_at TIMESTAMP(3),
    deleted_at TIMESTAMP(3),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    revision BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT chk_message_status CHECK (status IN ('UNREAD', 'READ', 'DELETED'))
);

CREATE INDEX idx_message_terminal ON terminal_message (target_terminal_id, status, created_at);

CREATE TABLE display_profile (
    id CHAR(36) PRIMARY KEY,
    terminal_id VARCHAR(64) NOT NULL,
    name VARCHAR(64) NOT NULL,
    is_default TINYINT(1) NOT NULL DEFAULT 0,
    content_json VARCHAR(8000) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    revision BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT uq_display_profile_name UNIQUE (terminal_id, name)
);

CREATE TABLE aircraft_label_layout (
    id CHAR(36) PRIMARY KEY,
    terminal_id VARCHAR(64) NOT NULL,
    aircraft_id VARCHAR(64) NOT NULL,
    angle_deg DECIMAL(6, 2) NOT NULL,
    distance_px INT NOT NULL,
    mode VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
    pinned TINYINT(1) NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_label_layout UNIQUE (terminal_id, aircraft_id)
);

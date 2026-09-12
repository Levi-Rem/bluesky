-- V16：假目标表（详细设计 2.2 §5.6；P16 领域逻辑已就位）
CREATE TABLE fake_target (
    id CHAR(36) PRIMARY KEY,
    exercise_group_id VARCHAR(64) NOT NULL,
    target_kind VARCHAR(24) NOT NULL,
    callsign VARCHAR(16) NOT NULL,
    squawk VARCHAR(4),
    state VARCHAR(24) NOT NULL DEFAULT 'SCHEDULED',
    start_simulation_seconds DECIMAL(15, 3) NOT NULL,
    expire_simulation_seconds DECIMAL(15, 3) NOT NULL,
    latitude_deg DECIMAL(10, 7),
    longitude_deg DECIMAL(10, 7),
    true_heading_deg DECIMAL(6, 2),
    ground_speed_kt INT,
    altitude_ft_msl INT,
    created_terminal_id VARCHAR(64) NOT NULL,
    last_latitude_deg DECIMAL(10, 7),
    last_longitude_deg DECIMAL(10, 7),
    is_fake_aircraft_id VARCHAR(64),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    revision BIGINT NOT NULL DEFAULT 1,
    CONSTRAINT chk_fake_target_kind CHECK (target_kind IN ('RADAR_SYNTHETIC', 'SIMULATED_AIRCRAFT')),
    CONSTRAINT chk_fake_target_state CHECK (state IN (
        'SCHEDULED', 'ACTIVE', 'STOPPED', 'EXPIRED', 'DELETE_REQUESTED', 'DELETED', 'FAILED'))
);

CREATE INDEX idx_fake_target_group ON fake_target (exercise_group_id, state);

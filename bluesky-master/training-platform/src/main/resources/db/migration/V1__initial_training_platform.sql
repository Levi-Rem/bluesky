CREATE TABLE exercise_group (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    state VARCHAR(16) NOT NULL,
    simulation_time_seconds BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE workstation_terminal (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    terminal_type VARCHAR(32) NOT NULL,
    exercise_group_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_terminal_group FOREIGN KEY (exercise_group_id) REFERENCES exercise_group(id)
);

CREATE TABLE exercise_aircraft (
    id VARCHAR(64) PRIMARY KEY,
    exercise_group_id VARCHAR(64) NOT NULL,
    assigned_terminal_id VARCHAR(64) NOT NULL,
    callsign VARCHAR(16) NOT NULL,
    aircraft_type VARCHAR(16) NOT NULL,
    wake_category VARCHAR(1) NOT NULL,
    transponder_code VARCHAR(4) NOT NULL,
    origin VARCHAR(8) NOT NULL,
    destination VARCHAR(8) NOT NULL,
    appearance_offset_minutes INT NOT NULL DEFAULT 0,
    latitude DOUBLE,
    longitude DOUBLE,
    initial_waypoint VARCHAR(16),
    heading_degrees DOUBLE NOT NULL,
    altitude_feet DOUBLE NOT NULL,
    speed_knots DOUBLE NOT NULL,
    vertical_speed_feet_per_minute DOUBLE NOT NULL DEFAULT 0,
    route_text VARCHAR(2000),
    active_instruction_text VARCHAR(128),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_aircraft_group_callsign UNIQUE (exercise_group_id, callsign),
    CONSTRAINT fk_aircraft_group FOREIGN KEY (exercise_group_id) REFERENCES exercise_group(id),
    CONSTRAINT fk_aircraft_terminal FOREIGN KEY (assigned_terminal_id) REFERENCES workstation_terminal(id)
);

CREATE TABLE aircraft_instruction (
    id VARCHAR(64) PRIMARY KEY,
    exercise_aircraft_id VARCHAR(64) NOT NULL,
    raw_text VARCHAR(256) NOT NULL,
    instruction_type VARCHAR(32) NOT NULL,
    insertion_mode VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    sequence_number BIGINT NOT NULL,
    parsed_payload VARCHAR(2000),
    failure_code VARCHAR(64),
    failure_message VARCHAR(512),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    started_at TIMESTAMP(3),
    completed_at TIMESTAMP(3),
    CONSTRAINT fk_instruction_aircraft FOREIGN KEY (exercise_aircraft_id) REFERENCES exercise_aircraft(id)
);

CREATE TABLE system_parameter (
    parameter_key VARCHAR(128) PRIMARY KEY,
    parameter_value VARCHAR(2000) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

INSERT INTO exercise_group (id, name, state, simulation_time_seconds)
VALUES ('GROUP-DEFAULT', '默认训练组', 'READY', 0);

INSERT INTO workstation_terminal (id, name, terminal_type, exercise_group_id)
VALUES ('PP-DEFAULT', '默认模拟飞行员终端', 'PSEUDO_PILOT', 'GROUP-DEFAULT');

INSERT INTO system_parameter (parameter_key, parameter_value)
VALUES ('ui.theme', 'DEFAULT_DARK');
INSERT INTO system_parameter (parameter_key, parameter_value)
VALUES ('ui.trackColor', '#58d7ff');
INSERT INTO system_parameter (parameter_key, parameter_value)
VALUES ('ui.selectedTrackColor', '#ffe66d');

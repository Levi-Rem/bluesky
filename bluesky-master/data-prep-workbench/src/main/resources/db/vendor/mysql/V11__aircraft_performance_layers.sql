CREATE TABLE aircraft_type (
    id VARCHAR(36) PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    manufacturer VARCHAR(64),
    model_name VARCHAR(64),
    engine_type VARCHAR(32),
    icao_wake_category VARCHAR(8),
    reacat_wake_category VARCHAR(8),
    maximum_takeoff_weight_kg INT,
    performance_category VARCHAR(8),
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    revision INT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL DEFAULT 'local',
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(64) NOT NULL DEFAULT 'local'
);

CREATE TABLE aircraft_performance (
    id VARCHAR(36) PRIMARY KEY,
    aircraft_id VARCHAR(36) NOT NULL,
    sequence_no INT NOT NULL,
    altitude_layer VARCHAR(16) NOT NULL,
    holding_speed_low VARCHAR(16),
    holding_speed_middle VARCHAR(16),
    holding_speed_high VARCHAR(16),
    takeoff_speed VARCHAR(16),
    takeoff_duration_s INT,
    takeoff_altitude_ft INT,
    takeoff_distance_nm DECIMAL(8, 3),
    landing_speed VARCHAR(16),
    radar_cross_section DECIMAL(10, 3),
    maximum_speed VARCHAR(16),
    maximum_altitude_layer VARCHAR(16),
    maximum_turn INT,
    mach_capable BOOLEAN,
    jet_aircraft BOOLEAN,
    standard_turn INT,
    turn_response_1 INT,
    turn_response_2 INT,
    turn_response_3 INT,
    acceleration_response_1 INT,
    acceleration_response_2 INT,
    acceleration_response_3 INT,
    deceleration_response_1 INT,
    deceleration_response_2 INT,
    deceleration_response_3 INT,
    climb_response_1 INT,
    climb_response_2 INT,
    climb_response_3 INT,
    descent_response_1 INT,
    descent_response_2 INT,
    descent_response_3 INT,
    climb_rate_ft_min INT,
    descent_rate_ft_min INT,
    acceleration_kts_min INT,
    deceleration_kts_min INT,
    cruise_speed VARCHAR(16),
    stall_speed VARCHAR(16),
    climb_speed VARCHAR(16),
    descent_speed VARCHAR(16),
    revision INT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL DEFAULT 'local',
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(64) NOT NULL DEFAULT 'local',
    CONSTRAINT fk_aircraft_performance_type FOREIGN KEY (aircraft_id) REFERENCES aircraft_type(id)
);

CREATE UNIQUE INDEX uq_aircraft_type_identity ON aircraft_type (code, icao_wake_category, reacat_wake_category);
CREATE UNIQUE INDEX uq_aircraft_performance_layer ON aircraft_performance (aircraft_id, altitude_layer);
CREATE INDEX idx_aircraft_performance_order ON aircraft_performance (aircraft_id, sequence_no);

INSERT INTO aircraft_type (id, code, name, manufacturer, model_name, engine_type,
                           icao_wake_category, reacat_wake_category, maximum_takeoff_weight_kg,
                           status, revision, deleted, created_by, updated_by)
SELECT id, code, name, manufacturer, model_name, engine_type,
       wake_turbulence_category, wake_turbulence_category, maximum_takeoff_weight_kg,
       status, revision, deleted, created_by, updated_by
FROM aircraft_type_performance;

INSERT INTO aircraft_performance (id, aircraft_id, sequence_no, altitude_layer,
                                  maximum_altitude_layer, maximum_turn, standard_turn,
                                  revision, deleted, created_by, updated_by)
SELECT id, id, 0, CONCAT('F', CAST(maximum_altitude_ft / 100 AS UNSIGNED)),
       CONCAT('F', CAST(maximum_altitude_ft / 100 AS UNSIGNED)),
       CAST(default_bank_angle_deg AS UNSIGNED), CAST(default_bank_angle_deg AS UNSIGNED),
       revision, deleted, created_by, updated_by
FROM aircraft_type_performance;

DROP TABLE aircraft_type_performance;

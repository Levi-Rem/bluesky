CREATE TABLE aircraft_performance_type (
    aircraft_type VARCHAR(16) PRIMARY KEY,
    source_aircraft_type VARCHAR(16) NOT NULL,
    aircraft_name VARCHAR(64) NOT NULL,
    wake_category VARCHAR(1) NOT NULL,
    ceiling_meters DECIMAL(10,4) NOT NULL,
    load_type VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    source_database VARCHAR(64) NOT NULL,
    source_plane_type_id BIGINT NOT NULL,
    source_modified_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_performance_source_type UNIQUE (source_database, source_plane_type_id),
    CONSTRAINT ck_performance_ceiling CHECK (ceiling_meters > 0),
    CONSTRAINT ck_performance_load CHECK (load_type IN ('LIGHT', 'MEDIUM', 'HEAVY')),
    CONSTRAINT ck_performance_wake CHECK (wake_category IN ('L', 'M', 'H', 'J'))
);

CREATE TABLE aircraft_performance_envelope (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    aircraft_type VARCHAR(16) NOT NULL,
    flight_phase VARCHAR(16) NOT NULL,
    altitude_meters DECIMAL(10,4) NOT NULL,
    nominal_cas_mps DECIMAL(14,8) NOT NULL,
    minimum_cas_mps DECIMAL(14,8) NOT NULL,
    maximum_cas_mps DECIMAL(14,8) NOT NULL,
    maximum_vertical_rate_mps DECIMAL(14,8) NOT NULL,
    source_fly_height_id BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_performance_envelope UNIQUE (aircraft_type, flight_phase, altitude_meters),
    CONSTRAINT uq_performance_source_height UNIQUE (source_fly_height_id),
    CONSTRAINT fk_performance_envelope_type FOREIGN KEY (aircraft_type)
        REFERENCES aircraft_performance_type(aircraft_type),
    CONSTRAINT ck_performance_phase CHECK (flight_phase IN ('CLIMB', 'CRUISE', 'DESCENT')),
    CONSTRAINT ck_performance_altitude CHECK (altitude_meters >= 0),
    CONSTRAINT ck_performance_speed_envelope CHECK (
        minimum_cas_mps > 0 AND maximum_cas_mps > minimum_cas_mps
    ),
    CONSTRAINT ck_performance_vertical_rate CHECK (
        (flight_phase = 'CRUISE' AND maximum_vertical_rate_mps = 0)
        OR (flight_phase IN ('CLIMB', 'DESCENT') AND maximum_vertical_rate_mps > 0)
    )
);

CREATE INDEX idx_performance_envelope_lookup
    ON aircraft_performance_envelope(aircraft_type, flight_phase, altitude_meters);

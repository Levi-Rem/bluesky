-- Unique business keys apply only to active rows; NULL generated keys permit soft-deleted history.
ALTER TABLE navigation_point ADD COLUMN active_code VARCHAR(64)
    GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN UPPER(TRIM(code)) ELSE NULL END) STORED;
CREATE UNIQUE INDEX uq_navigation_point_active_code ON navigation_point (active_code);
ALTER TABLE airport ADD COLUMN active_code VARCHAR(64)
    GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN UPPER(TRIM(code)) ELSE NULL END) STORED;
CREATE UNIQUE INDEX uq_airport_active_code ON airport (active_code);
ALTER TABLE airspace ADD COLUMN active_code VARCHAR(64)
    GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN UPPER(TRIM(code)) ELSE NULL END) STORED;
CREATE UNIQUE INDEX uq_airspace_active_code ON airspace (active_code);
ALTER TABLE airway ADD COLUMN active_code VARCHAR(64)
    GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN UPPER(TRIM(code)) ELSE NULL END) STORED;
CREATE UNIQUE INDEX uq_airway_active_code ON airway (active_code);
ALTER TABLE wind_field ADD COLUMN active_code VARCHAR(64)
    GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN UPPER(TRIM(code)) ELSE NULL END) STORED;
CREATE UNIQUE INDEX uq_wind_field_active_code ON wind_field (active_code);
ALTER TABLE airport_weather ADD COLUMN active_code VARCHAR(64)
    GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN UPPER(TRIM(code)) ELSE NULL END) STORED;
CREATE UNIQUE INDEX uq_airport_weather_active_code ON airport_weather (active_code);
ALTER TABLE significant_weather_area ADD COLUMN active_code VARCHAR(64)
    GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN UPPER(TRIM(code)) ELSE NULL END) STORED;
CREATE UNIQUE INDEX uq_weather_area_active_code ON significant_weather_area (active_code);
ALTER TABLE logical_radar_site ADD COLUMN active_code VARCHAR(64)
    GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN UPPER(TRIM(code)) ELSE NULL END) STORED;
CREATE UNIQUE INDEX uq_radar_site_active_code ON logical_radar_site (active_code);
ALTER TABLE asterix_channel ADD COLUMN active_code VARCHAR(64)
    GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN UPPER(TRIM(code)) ELSE NULL END) STORED;
CREATE UNIQUE INDEX uq_asterix_channel_active_code ON asterix_channel (active_code);

DROP INDEX uq_aircraft_type_identity ON aircraft_type;
ALTER TABLE aircraft_type ADD COLUMN active_identity VARCHAR(160)
    GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN CONCAT(UPPER(TRIM(code)), '|',
        UPPER(COALESCE(TRIM(icao_wake_category), '')), '|',
        UPPER(COALESCE(TRIM(reacat_wake_category), ''))) ELSE NULL END) STORED;
CREATE UNIQUE INDEX uq_aircraft_type_active_identity ON aircraft_type (active_identity);

DROP INDEX uq_aircraft_performance_layer ON aircraft_performance;
ALTER TABLE aircraft_performance ADD COLUMN active_layer VARCHAR(128)
    GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN CONCAT(aircraft_id, '|', UPPER(TRIM(altitude_layer))) ELSE NULL END) STORED;
CREATE UNIQUE INDEX uq_aircraft_performance_active_layer ON aircraft_performance (active_layer);

ALTER TABLE aircraft_performance
    MODIFY holding_speed_low VARCHAR(64), MODIFY holding_speed_middle VARCHAR(64),
    MODIFY holding_speed_high VARCHAR(64), MODIFY takeoff_speed VARCHAR(64),
    MODIFY landing_speed VARCHAR(64), MODIFY maximum_speed VARCHAR(64),
    MODIFY maximum_altitude_layer VARCHAR(64), MODIFY cruise_speed VARCHAR(64),
    MODIFY stall_speed VARCHAR(64), MODIFY climb_speed VARCHAR(64), MODIFY descent_speed VARCHAR(64);

-- 数据准备与分析工作台 · 一期数据模型
-- 所有业务表遵循 BaseRecord 公共列约定；几何以 TEXT(CLOB) 存 GeoJSON。

CREATE TABLE navigation_point (
    id VARCHAR(36) PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    point_type VARCHAR(16) NOT NULL,
    longitude DECIMAL(9, 6) NOT NULL,
    latitude DECIMAL(9, 6) NOT NULL,
    elevation_m INT,
    frequency_mhz DECIMAL(6, 2),
    magnetic_variation_deg DECIMAL(5, 1),
    description VARCHAR(512),
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    source_type VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    source_reference VARCHAR(256),
    revision INT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL DEFAULT 'local',
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(64) NOT NULL DEFAULT 'local'
);
CREATE INDEX idx_nav_point_code ON navigation_point (code);

CREATE TABLE airport (
    id VARCHAR(36) PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    icao VARCHAR(8),
    iata VARCHAR(8),
    country VARCHAR(64),
    airport_grade VARCHAR(32),
    max_runway_length_m INT,
    longitude DECIMAL(9, 6) NOT NULL,
    latitude DECIMAL(9, 6) NOT NULL,
    elevation_m INT,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    source_type VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    source_reference VARCHAR(256),
    revision INT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL DEFAULT 'local',
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(64) NOT NULL DEFAULT 'local'
);
CREATE INDEX idx_airport_code ON airport (code);

CREATE TABLE runway (
    id VARCHAR(36) PRIMARY KEY,
    airport_id VARCHAR(36) NOT NULL,
    designation VARCHAR(16) NOT NULL,
    thr1_longitude DECIMAL(9, 6),
    thr1_latitude DECIMAL(9, 6),
    thr2_longitude DECIMAL(9, 6),
    thr2_latitude DECIMAL(9, 6),
    length_m INT,
    width_m INT,
    true_heading_deg DECIMAL(6, 2),
    magnetic_heading_deg DECIMAL(6, 2),
    surface VARCHAR(32),
    runway_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    order_no INT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_runway_airport FOREIGN KEY (airport_id) REFERENCES airport (id)
);
CREATE INDEX idx_runway_airport ON runway (airport_id);

CREATE TABLE airspace (
    id VARCHAR(36) PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    airspace_type VARCHAR(24) NOT NULL,
    boundary CLOB,
    lower_value DECIMAL(10, 2),
    lower_reference VARCHAR(8),
    upper_value DECIMAL(10, 2),
    upper_reference VARCHAR(8),
    valid_from TIMESTAMP(3),
    valid_to TIMESTAMP(3),
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    source_type VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    source_reference VARCHAR(256),
    revision INT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL DEFAULT 'local',
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(64) NOT NULL DEFAULT 'local'
);
CREATE INDEX idx_airspace_code ON airspace (code);

CREATE TABLE airway (
    id VARCHAR(36) PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    airway_direction VARCHAR(16) NOT NULL,
    lower_value DECIMAL(10, 2),
    lower_reference VARCHAR(8),
    upper_value DECIMAL(10, 2),
    upper_reference VARCHAR(8),
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    source_type VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    source_reference VARCHAR(256),
    revision INT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL DEFAULT 'local',
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(64) NOT NULL DEFAULT 'local'
);
CREATE INDEX idx_airway_code ON airway (code);

CREATE TABLE airway_segment (
    id VARCHAR(36) PRIMARY KEY,
    airway_id VARCHAR(36) NOT NULL,
    order_no INT NOT NULL,
    start_point_id VARCHAR(36) NOT NULL,
    end_point_id VARCHAR(36) NOT NULL,
    segment_direction VARCHAR(16),
    lower_value DECIMAL(10, 2),
    lower_reference VARCHAR(8),
    upper_value DECIMAL(10, 2),
    upper_reference VARCHAR(8),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_segment_airway FOREIGN KEY (airway_id) REFERENCES airway (id),
    CONSTRAINT fk_segment_start FOREIGN KEY (start_point_id) REFERENCES navigation_point (id),
    CONSTRAINT fk_segment_end FOREIGN KEY (end_point_id) REFERENCES navigation_point (id)
);
CREATE INDEX idx_segment_airway ON airway_segment (airway_id);

CREATE TABLE wind_field (
    id VARCHAR(36) PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    wind_field_type VARCHAR(24) NOT NULL,
    wind_direction_deg DECIMAL(6, 1),
    wind_speed_ms DECIMAL(6, 2),
    boundary CLOB,
    effective_from TIMESTAMP(3),
    effective_to TIMESTAMP(3),
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    source_type VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    source_reference VARCHAR(256),
    revision INT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL DEFAULT 'local',
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(64) NOT NULL DEFAULT 'local'
);
CREATE INDEX idx_wind_field_code ON wind_field (code);

CREATE TABLE wind_field_point (
    id VARCHAR(36) PRIMARY KEY,
    wind_field_id VARCHAR(36) NOT NULL,
    order_no INT NOT NULL,
    longitude DECIMAL(9, 6) NOT NULL,
    latitude DECIMAL(9, 6) NOT NULL,
    altitude_m INT NOT NULL,
    wind_direction_deg DECIMAL(6, 1) NOT NULL,
    wind_speed_ms DECIMAL(6, 2) NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_wind_point_field FOREIGN KEY (wind_field_id) REFERENCES wind_field (id)
);
CREATE INDEX idx_wind_point_field ON wind_field_point (wind_field_id);

CREATE TABLE airport_weather (
    id VARCHAR(36) PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    airport_id VARCHAR(36) NOT NULL,
    valid_from TIMESTAMP(3),
    valid_to TIMESTAMP(3),
    wind_direction_deg DECIMAL(6, 1),
    wind_speed_ms DECIMAL(6, 2),
    gust_ms DECIMAL(6, 2),
    visibility_m INT,
    rvr_m INT,
    temperature_c DECIMAL(5, 1),
    dew_point_c DECIMAL(5, 1),
    humidity_pct INT,
    qnh_hpa INT,
    qfe_hpa INT,
    cloud_summary VARCHAR(256),
    phenomena VARCHAR(256),
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    source_type VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    source_reference VARCHAR(256),
    revision INT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL DEFAULT 'local',
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(64) NOT NULL DEFAULT 'local',
    CONSTRAINT fk_weather_airport FOREIGN KEY (airport_id) REFERENCES airport (id)
);
CREATE INDEX idx_airport_weather_code ON airport_weather (code);

CREATE TABLE significant_weather_area (
    id VARCHAR(36) PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    sig_weather_type VARCHAR(24) NOT NULL,
    boundary CLOB,
    lower_value DECIMAL(10, 2),
    lower_reference VARCHAR(8),
    upper_value DECIMAL(10, 2),
    upper_reference VARCHAR(8),
    intensity VARCHAR(16),
    moving_direction_deg DECIMAL(6, 1),
    moving_speed_ms DECIMAL(6, 2),
    valid_from TIMESTAMP(3),
    valid_to TIMESTAMP(3),
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    source_type VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    source_reference VARCHAR(256),
    revision INT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL DEFAULT 'local',
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(64) NOT NULL DEFAULT 'local'
);
CREATE INDEX idx_sig_weather_code ON significant_weather_area (code);

CREATE TABLE aircraft_type_performance (
    id VARCHAR(36) PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    manufacturer VARCHAR(64),
    model_name VARCHAR(64),
    performance_source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    engine_type VARCHAR(32),
    wake_turbulence_category VARCHAR(8),
    maximum_takeoff_weight_kg INT,
    maximum_altitude_ft INT,
    maximum_mach DECIMAL(4, 2),
    default_bank_angle_deg DECIMAL(5, 1),
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    source_type VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    source_reference VARCHAR(256),
    revision INT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL DEFAULT 'local',
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(64) NOT NULL DEFAULT 'local'
);
CREATE INDEX idx_performance_code ON aircraft_type_performance (code);

CREATE TABLE logical_radar_site (
    id VARCHAR(36) PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    sac INT NOT NULL,
    sic INT NOT NULL,
    longitude DECIMAL(9, 6) NOT NULL,
    latitude DECIMAL(9, 6) NOT NULL,
    altitude_m INT,
    maximum_range_nm DECIMAL(7, 1),
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    source_type VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    source_reference VARCHAR(256),
    revision INT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL DEFAULT 'local',
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(64) NOT NULL DEFAULT 'local'
);
CREATE INDEX idx_radar_site_code ON logical_radar_site (code);

CREATE TABLE asterix_channel (
    id VARCHAR(36) PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    category VARCHAR(8) NOT NULL,
    edition VARCHAR(16),
    period_ms INT,
    transmission_mode VARCHAR(16),
    destination_ip VARCHAR(64),
    destination_port INT,
    network_interface VARCHAR(64),
    ttl INT,
    maximum_datagram_bytes INT DEFAULT 1400,
    channel_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    config_revision INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    source_type VARCHAR(24) NOT NULL DEFAULT 'MANUAL',
    source_reference VARCHAR(256),
    revision INT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    created_by VARCHAR(64) NOT NULL DEFAULT 'local',
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_by VARCHAR(64) NOT NULL DEFAULT 'local'
);
CREATE INDEX idx_channel_code ON asterix_channel (code);

CREATE TABLE radar_channel_binding (
    id VARCHAR(36) PRIMARY KEY,
    radar_site_id VARCHAR(36) NOT NULL,
    channel_id VARCHAR(36) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    display_order INT NOT NULL DEFAULT 0,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_binding_site FOREIGN KEY (radar_site_id) REFERENCES logical_radar_site (id),
    CONSTRAINT fk_binding_channel FOREIGN KEY (channel_id) REFERENCES asterix_channel (id),
    CONSTRAINT uq_binding UNIQUE (radar_site_id, channel_id)
);

CREATE TABLE import_batch (
    id VARCHAR(36) PRIMARY KEY,
    file_name VARCHAR(256) NOT NULL,
    template_version VARCHAR(16),
    data_type VARCHAR(32) NOT NULL,
    total_rows INT NOT NULL DEFAULT 0,
    success_rows INT NOT NULL DEFAULT 0,
    failed_rows INT NOT NULL DEFAULT 0,
    batch_status VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    started_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at TIMESTAMP(3)
);

CREATE TABLE import_row_error (
    id VARCHAR(36) PRIMARY KEY,
    batch_id VARCHAR(36) NOT NULL,
    sheet_name VARCHAR(64),
    row_number INT,
    field_name VARCHAR(64),
    error_code VARCHAR(32),
    error_message VARCHAR(512),
    CONSTRAINT fk_error_batch FOREIGN KEY (batch_id) REFERENCES import_batch (id)
);
CREATE INDEX idx_error_batch ON import_row_error (batch_id);

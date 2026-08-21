-- 原型示例种子数据：开箱界面与原型截图一致（PUD/AND/SASAN、TMA-01/ZSHA/R-210、A593/B221/W13、
-- WIND-E01/MET-ZSPD/CB-07、A320/B738/C919、RDR-SHA-01/CH-048-01/CH-021-01）。

INSERT INTO navigation_point (id, code, name, point_type, longitude, latitude, elevation_m, frequency_mhz, source_type, status) VALUES
('seed-nav-pud',   'PUD',   '浦东导航台', 'VOR', 121.808200, 31.142100, 4,   116.90, 'BLUESKY', 'ENABLED'),
('seed-nav-and',   'AND',   '安东点',     'FIX', 121.427000, 31.385000, 8,   NULL,   'MANUAL',  'ENABLED'),
('seed-nav-sasan', 'SASAN', '莎山点',     'FIX', 121.206000, 30.993000, 6,   NULL,   'MANUAL',  'ENABLED');

INSERT INTO airport (id, code, name, icao, iata, country, airport_grade, max_runway_length_m, longitude, latitude, elevation_m, source_type, status) VALUES
('seed-ap-zspd', 'ZSPD', '上海浦东', 'ZSPD', 'PVG', 'CN', '4F', 4000, 121.808300, 31.144300, 4, 'BLUESKY', 'ENABLED'),
('seed-ap-zsss', 'ZSSS', '上海虹桥', 'ZSSS', 'SHA', 'CN', '4E', 3300, 121.336300, 31.197900, 3, 'BLUESKY', 'ENABLED');

INSERT INTO runway (id, airport_id, designation, thr1_longitude, thr1_latitude, thr2_longitude, thr2_latitude, length_m, width_m, true_heading_deg, magnetic_heading_deg, surface, order_no) VALUES
('seed-rwy-zspd-1', 'seed-ap-zspd', '17L/35R', 121.774000, 31.168000, 121.842000, 31.120000, 4000, 60, 173.0, 176.0, 'ASPHALT', 0),
('seed-rwy-zspd-2', 'seed-ap-zspd', '16R/34L', 121.768000, 31.150000, 121.836000, 31.104000, 3400, 60, 169.0, 172.0, 'ASPHALT', 1),
('seed-rwy-zsss-1', 'seed-ap-zsss', '18/36',   121.328000, 31.215000, 121.344000, 31.178000, 3300, 57, 177.0, 180.0, 'ASPHALT', 0);

INSERT INTO airspace (id, code, name, airspace_type, boundary, lower_value, lower_reference, upper_value, upper_reference, source_type, status) VALUES
('seed-as-tma01', 'TMA-01', '终端管制区一号', 'TMA',
 '{"type":"Polygon","coordinates":[[[121.20,31.35],[121.95,31.38],[122.10,31.05],[121.90,30.75],[121.30,30.72],[121.10,31.05],[121.20,31.35]]]}',
 0, 'SFC', 19700, 'FL', 'MANUAL', 'ENABLED'),
('seed-as-zsha', 'ZSHA', '上海飞行情报区', 'FIR',
 '{"type":"Polygon","coordinates":[[[120.50,32.30],[122.60,32.40],[123.40,31.40],[122.60,29.90],[120.80,29.90],[119.90,31.20],[120.50,32.30]]]}',
 0, 'SFC', NULL, 'UNL', 'BLUESKY', 'ENABLED'),
('seed-as-r210', 'R-210', '限制区 210', 'RESTRICTED',
 '{"type":"Polygon","coordinates":[[[121.30,31.55],[121.55,31.58],[121.58,31.38],[121.32,31.36],[121.30,31.55]]]}',
 0, 'SFC', 15000, 'FL', 'MANUAL', 'DISABLED');

INSERT INTO airway (id, code, name, airway_direction, lower_value, lower_reference, upper_value, upper_reference, source_type, status) VALUES
('seed-aw-a593', 'A593', 'A593 航路', 'TWO_WAY', 9000,  'FL', 39000, 'FL', 'BLUESKY', 'ENABLED'),
('seed-aw-b221', 'B221', 'B221 航路', 'ONE_WAY', 12000, 'FL', 41000, 'FL', 'MANUAL',  'ENABLED'),
('seed-aw-w13',  'W13',  'W13 航路',  'TWO_WAY', 8000,  'FL', 31000, 'FL', 'MANUAL',  'ENABLED');

INSERT INTO airway_segment (id, airway_id, order_no, start_point_id, end_point_id, segment_direction, lower_value, lower_reference, upper_value, upper_reference) VALUES
('seed-seg-a593-1', 'seed-aw-a593', 0, 'seed-nav-pud',   'seed-nav-sasan', 'TWO_WAY', 9000,  'FL', 39000, 'FL'),
('seed-seg-b221-1', 'seed-aw-b221', 0, 'seed-nav-sasan', 'seed-nav-and',   'ONE_WAY', 12000, 'FL', 41000, 'FL'),
('seed-seg-w13-1',  'seed-aw-w13',  0, 'seed-nav-and',   'seed-nav-pud',   'TWO_WAY', 8000,  'FL', 31000, 'FL');

INSERT INTO wind_field (id, code, name, wind_field_type, wind_direction_deg, wind_speed_ms, effective_from, effective_to, source_type, status) VALUES
('seed-wind-e01', 'WIND-E01', '华东区域三维风场', 'THREE_DIMENSIONAL', NULL, NULL, NULL, NULL, 'MANUAL', 'ENABLED');

INSERT INTO wind_field_point (id, wind_field_id, order_no, longitude, latitude, altitude_m, wind_direction_deg, wind_speed_ms) VALUES
('seed-wpt-1', 'seed-wind-e01', 0, 121.50, 31.30, 1000, 120.0, 8.5),
('seed-wpt-2', 'seed-wind-e01', 1, 121.80, 31.10, 3000, 130.0, 10.0),
('seed-wpt-3', 'seed-wind-e01', 2, 121.40, 30.90, 6000, 140.0, 13.5);

INSERT INTO airport_weather (id, code, name, airport_id, valid_from, valid_to, wind_direction_deg, wind_speed_ms, visibility_m, temperature_c, dew_point_c, qnh_hpa, cloud_summary, phenomena, source_type, status) VALUES
('seed-met-zspd', 'MET-ZSPD', '浦东机场气象', 'seed-ap-zspd',
 TIMESTAMP '2026-08-21 08:00:00', TIMESTAMP '2026-08-21 14:00:00',
 120.0, 8.0, 9999, 32.0, 24.0, 1006, 'FEW030 SCT045', '无', 'MANUAL', 'ENABLED');

INSERT INTO significant_weather_area (id, code, name, sig_weather_type, boundary, lower_value, lower_reference, upper_value, upper_reference, intensity, moving_direction_deg, moving_speed_ms, valid_from, valid_to, source_type, status) VALUES
('seed-cb-07', 'CB-07', '雷暴区 07', 'THUNDERSTORM',
 '{"type":"Polygon","coordinates":[[[121.70,31.35],[121.95,31.38],[122.00,31.15],[121.75,31.10],[121.70,31.35]]]}',
 600, 'AGL', 11000, 'M', 'MODERATE', 250.0, 5.0,
 TIMESTAMP '2026-08-21 10:00:00', TIMESTAMP '2026-08-21 12:00:00', 'MANUAL', 'ENABLED');

INSERT INTO aircraft_type_performance (id, code, name, manufacturer, model_name, performance_source, engine_type, wake_turbulence_category, maximum_takeoff_weight_kg, maximum_altitude_ft, maximum_mach, default_bank_angle_deg, source_type, source_reference, status) VALUES
('seed-perf-a320', 'A320', 'A320', 'Airbus', 'A320-200', 'OPENAP', '涡扇', 'M', 78000, 39800, 0.82, 25.0, 'BLUESKY', 'openap:A320', 'ENABLED'),
('seed-perf-b738', 'B738', 'B738', 'Boeing', '737-800',  'OPENAP', '涡扇', 'M', 79000, 41000, 0.82, 25.0, 'BLUESKY', 'openap:B738', 'ENABLED'),
('seed-perf-c919', 'C919', 'C919', 'COMAC',  'C919',      'MANUAL', '涡扇', 'M', 72500, 39800, 0.82, 25.0, 'MANUAL',  NULL,          'ENABLED');

INSERT INTO logical_radar_site (id, code, name, sac, sic, longitude, latitude, altitude_m, maximum_range_nm, source_type, status) VALUES
('seed-site-sha01', 'RDR-SHA-01', '上海逻辑雷达站', 1, 20, 121.500000, 31.100000, 5, 200.0, 'MANUAL', 'ENABLED');

INSERT INTO asterix_channel (id, code, name, category, edition, period_ms, transmission_mode, destination_ip, destination_port, network_interface, ttl, maximum_datagram_bytes, channel_enabled, config_revision, source_type, status) VALUES
('seed-ch048-01', 'CH-048-01', 'CAT048 主通道', 'CAT048', '1.32', 4000, 'MULTICAST', '239.1.1.10', 5000, NULL, 1, 1400, TRUE, 3, 'MANUAL', 'ENABLED'),
('seed-ch021-01', 'CH-021-01', 'CAT021 主通道', 'CAT021', '2.7',  1000, 'MULTICAST', '239.1.1.11', 5001, NULL, 1, 1400, FALSE, 1, 'MANUAL', 'DISABLED');

INSERT INTO radar_channel_binding (id, radar_site_id, channel_id, enabled, display_order) VALUES
('seed-bind-1', 'seed-site-sha01', 'seed-ch048-01', TRUE, 0);

UPDATE workbench_state SET revision = 27 WHERE id = 1;

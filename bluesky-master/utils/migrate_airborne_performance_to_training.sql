-- Copy the validated airborne-only performance subset into bluesky_training.
-- Idempotent: existing rows from the same source are updated, not duplicated.

START TRANSACTION;

INSERT INTO bluesky_training.aircraft_performance_type (
    aircraft_type,
    source_aircraft_type,
    aircraft_name,
    wake_category,
    ceiling_meters,
    load_type,
    source_database,
    source_plane_type_id,
    source_modified_at
)
SELECT
    CASE p.icao
        WHEN 'A321NEO' THEN 'A21N'
        WHEN 'A380' THEN 'A388'
        WHEN 'B773ER' THEN 'B77W'
        ELSE p.icao
    END,
    p.icao,
    p.plane_type_name,
    CASE p.wake_flow_type
        WHEN 1 THEN 'L'
        WHEN 2 THEN 'M'
        WHEN 3 THEN 'H'
        WHEN 4 THEN 'H'
        WHEN 5 THEN 'J'
    END,
    f.ceiling_max,
    'MEDIUM',
    'simulator_backup',
    p.plane_type_id,
    p.gmt_modified
FROM simulator_backup.ap_plane_type_info p
JOIN simulator_backup.ap_config_fly_info f
  ON f.plane_type_id = p.plane_type_id
WHERE p.icao IN (
    'A319', 'A320', 'A321', 'A321NEO', 'A332',
    'A380', 'B738', 'B744', 'B773ER'
)
ON DUPLICATE KEY UPDATE
    source_aircraft_type = VALUES(source_aircraft_type),
    aircraft_name = VALUES(aircraft_name),
    wake_category = VALUES(wake_category),
    ceiling_meters = VALUES(ceiling_meters),
    load_type = VALUES(load_type),
    source_database = VALUES(source_database),
    source_plane_type_id = VALUES(source_plane_type_id),
    source_modified_at = VALUES(source_modified_at),
    updated_at = CURRENT_TIMESTAMP(3);

INSERT INTO bluesky_training.aircraft_performance_envelope (
    aircraft_type,
    flight_phase,
    altitude_meters,
    nominal_cas_mps,
    minimum_cas_mps,
    maximum_cas_mps,
    maximum_vertical_rate_mps,
    source_fly_height_id
)
SELECT
    CASE p.icao
        WHEN 'A321NEO' THEN 'A21N'
        WHEN 'A380' THEN 'A388'
        WHEN 'B773ER' THEN 'B77W'
        ELSE p.icao
    END,
    CASE h.flight_status
        WHEN 1 THEN 'CLIMB'
        WHEN 2 THEN 'CRUISE'
        WHEN 3 THEN 'DESCENT'
    END,
    h.height,
    LEAST(
        h.indicated_airspeed_max,
        GREATEST(h.indicated_airspeed_min, h.indicated_airspeed)
    ) / 3.6,
    h.indicated_airspeed_min / 3.6,
    h.indicated_airspeed_max / 3.6,
    CASE h.flight_status
        WHEN 1 THEN h.climb_rate
        WHEN 2 THEN 0
        WHEN 3 THEN h.decline_rate
    END,
    h.fly_height_id
FROM simulator_backup.ap_plane_type_info p
JOIN simulator_backup.ap_config_fly_info f
  ON f.plane_type_id = p.plane_type_id
JOIN simulator_backup.ap_fly_height_info h
  ON h.plane_type_id = p.plane_type_id
WHERE p.icao IN (
    'A319', 'A320', 'A321', 'A321NEO', 'A332',
    'A380', 'B738', 'B744', 'B773ER'
)
  AND h.load_type = 2
  AND h.flight_status IN (1, 2, 3)
  AND h.height <= f.ceiling_max
  AND h.indicated_airspeed_min > 0
  AND h.indicated_airspeed_max > h.indicated_airspeed_min
  AND (h.flight_status <> 1 OR h.climb_rate > 0)
  AND (h.flight_status <> 3 OR h.decline_rate > 0)
ON DUPLICATE KEY UPDATE
    aircraft_type = VALUES(aircraft_type),
    flight_phase = VALUES(flight_phase),
    altitude_meters = VALUES(altitude_meters),
    nominal_cas_mps = VALUES(nominal_cas_mps),
    minimum_cas_mps = VALUES(minimum_cas_mps),
    maximum_cas_mps = VALUES(maximum_cas_mps),
    maximum_vertical_rate_mps = VALUES(maximum_vertical_rate_mps),
    source_fly_height_id = VALUES(source_fly_height_id),
    updated_at = CURRENT_TIMESTAMP(3);

-- Return post-import counts for the caller to assert.
SET @imported_type_count = (
    SELECT COUNT(*)
    FROM bluesky_training.aircraft_performance_type
    WHERE source_database = 'simulator_backup'
);
SET @imported_envelope_count = (
    SELECT COUNT(*)
    FROM bluesky_training.aircraft_performance_envelope e
    JOIN bluesky_training.aircraft_performance_type t
      ON t.aircraft_type = e.aircraft_type
    WHERE t.source_database = 'simulator_backup'
);

COMMIT;

SELECT @imported_type_count AS imported_type_count,
       @imported_envelope_count AS imported_envelope_count;

INSERT INTO aircraft_performance_type (
    aircraft_type, source_aircraft_type, aircraft_name, wake_category,
    ceiling_meters, load_type, source_database, source_plane_type_id
) VALUES
('A319', 'A319', 'A319', 'M', 12500, 'MEDIUM', 'simulator_backup', 78),
('A320', 'A320', 'A320', 'M', 12500, 'MEDIUM', 'simulator_backup', 59),
('A321', 'A321', 'A321', 'M', 12500, 'MEDIUM', 'simulator_backup', 60),
('A21N', 'A321NEO', 'A321NEO', 'M', 12500, 'MEDIUM', 'simulator_backup', 77),
('A332', 'A332', 'A330-200', 'H', 12500, 'MEDIUM', 'simulator_backup', 61),
('A388', 'A380', 'A380', 'J', 12500, 'MEDIUM', 'simulator_backup', 65),
('B738', 'B738', 'B737-800', 'M', 12500, 'MEDIUM', 'simulator_backup', 20),
('B744', 'B744', 'B747-400', 'H', 12500, 'MEDIUM', 'simulator_backup', 53),
('B77W', 'B773ER', 'B777-300ER', 'H', 12500, 'MEDIUM', 'simulator_backup', 79);

INSERT INTO aircraft_performance_envelope (
    aircraft_type, flight_phase, altitude_meters, nominal_cas_mps,
    minimum_cas_mps, maximum_cas_mps, maximum_vertical_rate_mps,
    source_fly_height_id
)
SELECT t.aircraft_type, p.flight_phase, r.n * 250,
       100, 80, 120,
       CASE WHEN p.flight_phase='CRUISE' THEN 0 ELSE 10 END,
       ROW_NUMBER() OVER ()
FROM aircraft_performance_type t
CROSS JOIN (VALUES ('CLIMB'), ('CRUISE'), ('DESCENT')) p(flight_phase)
CROSS JOIN SYSTEM_RANGE(0, 43) r(n);

UPDATE aircraft_performance_envelope
SET maximum_vertical_rate_mps=14.6304
WHERE aircraft_type='A320' AND flight_phase='CLIMB' AND altitude_meters=6000;

-- Reproduce one of the source anomalies before V5 normalizes it.
UPDATE aircraft_performance_envelope
SET nominal_cas_mps=130, maximum_cas_mps=120
WHERE aircraft_type='A320' AND flight_phase='CLIMB' AND altitude_meters=1500;

-- The source contains 18 low-altitude climb rows whose reference CAS exceeds
-- its own upper speed bound. Keep the source record traceable by ID, but store
-- a usable target-speed schedule by clipping the reference CAS to the envelope.
UPDATE aircraft_performance_envelope
SET nominal_cas_mps = LEAST(
        maximum_cas_mps,
        GREATEST(minimum_cas_mps, nominal_cas_mps)
    ),
    updated_at = CURRENT_TIMESTAMP(3)
WHERE nominal_cas_mps < minimum_cas_mps
   OR nominal_cas_mps > maximum_cas_mps;

ALTER TABLE aircraft_performance_envelope
    ADD CONSTRAINT ck_performance_nominal_speed CHECK (
        nominal_cas_mps >= minimum_cas_mps
        AND nominal_cas_mps <= maximum_cas_mps
    );

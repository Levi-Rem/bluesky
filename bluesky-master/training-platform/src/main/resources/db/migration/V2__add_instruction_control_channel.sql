ALTER TABLE aircraft_instruction
    ADD COLUMN control_channel VARCHAR(16) NOT NULL DEFAULT 'LATERAL';

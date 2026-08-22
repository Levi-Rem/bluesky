ALTER TABLE airway ADD COLUMN route_type VARCHAR(16) NOT NULL DEFAULT 'CODED_ROUTE';
ALTER TABLE airway ADD COLUMN procedure_airport VARCHAR(16);
ALTER TABLE airway ADD COLUMN procedure_profile VARCHAR(32);
ALTER TABLE airway ADD COLUMN procedure_runway VARCHAR(16);
ALTER TABLE airway ADD COLUMN procedure_direction VARCHAR(16);
ALTER TABLE airway ADD COLUMN procedure_operation VARCHAR(16);
ALTER TABLE airway ADD COLUMN eligible_route VARCHAR(64);

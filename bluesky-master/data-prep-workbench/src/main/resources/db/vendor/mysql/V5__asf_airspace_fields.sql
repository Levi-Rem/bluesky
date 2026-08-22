ALTER TABLE navigation_point ADD COLUMN source_point_type VARCHAR(24);
ALTER TABLE navigation_point ADD COLUMN coordinate_text VARCHAR(32);
ALTER TABLE navigation_point ADD COLUMN relevant_flag VARCHAR(8);
ALTER TABLE navigation_point ADD COLUMN applicable_airports VARCHAR(512);
ALTER TABLE navigation_point ADD COLUMN pilot_flag VARCHAR(8);
ALTER TABLE navigation_point ADD COLUMN dti_flag VARCHAR(8);
ALTER TABLE navigation_point ADD COLUMN tfm_flag VARCHAR(8);

ALTER TABLE airway ADD COLUMN cruise_level_rule VARCHAR(16);
ALTER TABLE airway ADD COLUMN rnav_capability VARCHAR(32);
ALTER TABLE airway ADD COLUMN rnav_capability_post_2012 VARCHAR(32);
ALTER TABLE airway ADD COLUMN rnp_capability_post_2012 VARCHAR(32);
ALTER TABLE airway ADD COLUMN rvsm_level VARCHAR(32);

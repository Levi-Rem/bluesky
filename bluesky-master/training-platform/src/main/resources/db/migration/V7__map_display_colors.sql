INSERT INTO system_parameter (parameter_key, parameter_value)
SELECT 'ui.mapWaypointColor', '#7FD3FF'
WHERE NOT EXISTS (SELECT 1 FROM system_parameter WHERE parameter_key = 'ui.mapWaypointColor');
INSERT INTO system_parameter (parameter_key, parameter_value)
SELECT 'ui.mapAirwayColor', '#4AA8D8'
WHERE NOT EXISTS (SELECT 1 FROM system_parameter WHERE parameter_key = 'ui.mapAirwayColor');
INSERT INTO system_parameter (parameter_key, parameter_value)
SELECT 'ui.mapSectorColor', '#D6A7FF'
WHERE NOT EXISTS (SELECT 1 FROM system_parameter WHERE parameter_key = 'ui.mapSectorColor');
INSERT INTO system_parameter (parameter_key, parameter_value)
SELECT 'ui.mapSectorFillColor', '#7B4DB3'
WHERE NOT EXISTS (SELECT 1 FROM system_parameter WHERE parameter_key = 'ui.mapSectorFillColor');
INSERT INTO system_parameter (parameter_key, parameter_value)
SELECT 'ui.mapWeatherColor', '#FFCF66'
WHERE NOT EXISTS (SELECT 1 FROM system_parameter WHERE parameter_key = 'ui.mapWeatherColor');
INSERT INTO system_parameter (parameter_key, parameter_value)
SELECT 'ui.mapWeatherFillColor', '#D9822B'
WHERE NOT EXISTS (SELECT 1 FROM system_parameter WHERE parameter_key = 'ui.mapWeatherFillColor');

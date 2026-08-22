-- Sxxxx 使用十米单位：600 米对应 S0060，11000 米对应 S1100。
UPDATE significant_weather_area
SET lower_value = lower_value / 10,
    upper_value = upper_value / 10
WHERE deleted = FALSE;

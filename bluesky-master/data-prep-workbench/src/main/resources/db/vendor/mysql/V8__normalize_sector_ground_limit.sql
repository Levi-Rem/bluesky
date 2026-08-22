UPDATE physical_sector SET lower_limit = 'S0000' WHERE UPPER(lower_limit) = 'GROUND' OR lower_limit = '地面';

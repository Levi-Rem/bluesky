-- V6: 航迹显示颜色改为绿色系（常规绿 / 选中高亮绿）
-- 需求：航迹符号、标杆线颜色跟随标牌主题色；常规 #3fae6d，选中 #27e58d
UPDATE system_parameter SET parameter_value = '#3fae6d' WHERE parameter_key = 'ui.trackColor';
UPDATE system_parameter SET parameter_value = '#27e58d' WHERE parameter_key = 'ui.selectedTrackColor';

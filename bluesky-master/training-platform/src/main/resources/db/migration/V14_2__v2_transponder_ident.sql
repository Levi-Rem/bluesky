-- P0-8（评审）：IDENT 应答机识别业务字段。
-- transponder_ident_active 激活标记；transponder_ident_expires_at 到期仿真时刻
-- （DECIMAL(15,3) 与组仿真时间同精度），由仿真时钟推进清除（暂停冻结语义）。
ALTER TABLE exercise_aircraft ADD COLUMN transponder_ident_active TINYINT(1)
    NOT NULL DEFAULT 0;
ALTER TABLE exercise_aircraft ADD COLUMN transponder_ident_expires_at DECIMAL(15, 3);

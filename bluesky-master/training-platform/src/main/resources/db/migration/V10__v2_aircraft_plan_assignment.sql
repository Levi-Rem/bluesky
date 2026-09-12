-- V10：航空器扩展、版本化飞行计划、责任分配（详细设计 2.2 §5.2/§5.3）
-- 既有 exercise_aircraft 保留并扩展；v1 assigned_terminal_id 语义由 aircraft_assignment 取代。

ALTER TABLE exercise_aircraft ADD COLUMN lifecycle VARCHAR(24) NOT NULL DEFAULT 'PLANNED';
ALTER TABLE exercise_aircraft ADD COLUMN flight_phase VARCHAR(24) NOT NULL DEFAULT 'PRE_DEPARTURE';
ALTER TABLE exercise_aircraft ADD COLUMN control_status VARCHAR(24) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE exercise_aircraft ADD COLUMN is_fake TINYINT(1) NOT NULL DEFAULT 0;
ALTER TABLE exercise_aircraft ADD COLUMN icao24 VARCHAR(6);
ALTER TABLE exercise_aircraft ADD COLUMN planned_squawk VARCHAR(4);
ALTER TABLE exercise_aircraft ADD COLUMN current_squawk VARCHAR(4);
ALTER TABLE exercise_aircraft ADD COLUMN ssr_mode VARCHAR(1);
ALTER TABLE exercise_aircraft ADD COLUMN target_appearance_time DECIMAL(15, 3);
ALTER TABLE exercise_aircraft ADD COLUMN actual_appearance_time DECIMAL(15, 3);
ALTER TABLE exercise_aircraft ADD COLUMN deleted_at TIMESTAMP(3);
ALTER TABLE exercise_aircraft ADD COLUMN active_callsign_key VARCHAR(16);
ALTER TABLE exercise_aircraft ADD COLUMN failure_code VARCHAR(64);
ALTER TABLE exercise_aircraft ADD COLUMN failure_message VARCHAR(512);
ALTER TABLE exercise_aircraft ADD CONSTRAINT chk_aircraft_lifecycle CHECK (lifecycle IN (
    'PLANNED', 'CREATE_REQUESTED', 'CREATE_FAILED', 'ACTIVE',
    'DELETE_REQUESTED', 'DELETE_FAILED', 'DELETED'));
ALTER TABLE exercise_aircraft ADD CONSTRAINT chk_aircraft_phase CHECK (flight_phase IN (
    'PRE_DEPARTURE', 'TAKEOFF_ROLL', 'INITIAL_CLIMB', 'CLIMB', 'CRUISE',
    'DESCENT', 'APPROACH', 'FINAL', 'FLARE', 'ROLLOUT', 'LANDED', 'MISSED_APPROACH'));
ALTER TABLE exercise_aircraft ADD CONSTRAINT chk_aircraft_control CHECK (control_status IN (
    'NORMAL', 'SPECIAL_OPERATION', 'DELETE_PENDING', 'ENGINE_FAILED'));

-- 活动键由数据库处理并发竞态；删除时置 NULL。
-- V10.1 Java 迁移按数据库方言移除 v1 的永久呼号唯一约束。
CREATE UNIQUE INDEX uq_aircraft_group_active_callsign
    ON exercise_aircraft (exercise_group_id, active_callsign_key);
-- ICAO24 删除时同样置 NULL；非空值在训练组内唯一，并由数据库处理并发竞态。
CREATE UNIQUE INDEX uq_aircraft_group_active_icao24
    ON exercise_aircraft (exercise_group_id, icao24);

CREATE TABLE flight_plan (
    id CHAR(36) PRIMARY KEY,
    aircraft_id VARCHAR(64) NOT NULL,
    plan_version INT NOT NULL,
    origin VARCHAR(8) NOT NULL,
    destination VARCHAR(8) NOT NULL,
    planned_squawk VARCHAR(4),
    ssr_mode VARCHAR(1),
    cruise_altitude_ft_msl INT,
    cruise_indicated_airspeed_kt INT,
    route_text VARCHAR(2000),
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_flight_plan_version UNIQUE (aircraft_id, plan_version)
);

CREATE TABLE flight_plan_leg (
    id CHAR(36) PRIMARY KEY,
    flight_plan_id CHAR(36) NOT NULL,
    sequence_number INT NOT NULL,
    nav_point_id VARCHAR(16),
    point_code VARCHAR(16) NOT NULL,
    latitude_deg DECIMAL(10, 7),
    longitude_deg DECIMAL(10, 7),
    fly_over TINYINT(1) NOT NULL DEFAULT 0,
    altitude_constraint_ft INT,
    speed_constraint_kt INT,
    target_time_seconds DECIMAL(15, 3),
    procedure_kind VARCHAR(24),
    runway_id VARCHAR(16),
    CONSTRAINT uq_flight_plan_leg_sequence UNIQUE (flight_plan_id, sequence_number),
    CONSTRAINT fk_leg_flight_plan FOREIGN KEY (flight_plan_id) REFERENCES flight_plan (id)
);

CREATE TABLE aircraft_assignment (
    id CHAR(36) PRIMARY KEY,
    aircraft_id VARCHAR(64) NOT NULL,
    terminal_id VARCHAR(64) NOT NULL,
    started_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    ended_at TIMESTAMP(3),
    -- 当前分配键：开放行为 aircraft_id，关闭后置 NULL；唯一约束保证每机恰一条当前分配
    current_key VARCHAR(64),
    CONSTRAINT uq_aircraft_current_assignment UNIQUE (aircraft_id, current_key)
);

CREATE INDEX idx_assignment_terminal ON aircraft_assignment (terminal_id, ended_at);

CREATE TABLE aircraft_handover (
    id CHAR(36) PRIMARY KEY,
    aircraft_id VARCHAR(64) NOT NULL,
    source_terminal_id VARCHAR(64) NOT NULL,
    target_terminal_id VARCHAR(64) NOT NULL,
    target_frequency_mhz DECIMAL(6, 3) NOT NULL,
    aircraft_revision BIGINT NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

-- 迁移回填：既有航空器生成 v1 计划与唯一当前分配。
-- 主键取 v1 id 去连字符后的 32 字符派生：CONCAT(id,'-p1') 拼接 36 字符 UUID 得 39 字符，
-- 必然溢出 CHAR(36)（MySQL 严格模式 1406 且 DDL 不可回滚，评审 P0-1）；REPLACE 写法
-- 在 MySQL 与 H2(MySQL 模式) 下行为一致。NOT EXISTS 保证迁移中断后可安全重试（评审 G9）。
INSERT INTO flight_plan (id, aircraft_id, plan_version, origin, destination,
                         planned_squawk, ssr_mode, route_text)
SELECT REPLACE(id, '-', ''), id, 1, origin, destination, transponder_code, 'C', route_text
FROM exercise_aircraft ea
WHERE NOT EXISTS (SELECT 1 FROM flight_plan fp
                  WHERE fp.aircraft_id = ea.id AND fp.plan_version = 1);

INSERT INTO aircraft_assignment (id, aircraft_id, terminal_id, current_key)
SELECT CONCAT('a', REPLACE(id, '-', '')), id, assigned_terminal_id, id
FROM exercise_aircraft ea
WHERE NOT EXISTS (SELECT 1 FROM aircraft_assignment aa
                  WHERE aa.aircraft_id = ea.id AND aa.current_key IS NOT NULL);

UPDATE exercise_aircraft SET active_callsign_key = callsign,
    planned_squawk = transponder_code, current_squawk = transponder_code,
    ssr_mode = 'C', lifecycle = 'ACTIVE', flight_phase = 'CRUISE'
WHERE deleted_at IS NULL AND lifecycle = 'PLANNED';

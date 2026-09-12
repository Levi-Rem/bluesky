-- V15：最终约束与 v1 退役（详细设计 2.2 §5.0/§13）
-- 仅在 P04~P18 全部测试通过后执行；本迁移收紧唯一键/CHECK 并清理 v1 兼容残留。

-- 1. 训练组状态 CHECK 收紧（v2 全枚举）
ALTER TABLE exercise_group ADD CONSTRAINT chk_group_state_v2 CHECK (state IN (
    'READY', 'STARTING', 'RUNNING', 'PAUSING', 'PAUSED', 'RESUMING',
    'RECOVERING', 'RECOVERY_FAILED', 'ENDING', 'ENDED'));

-- 2. 指令状态收紧为 v2 枚举（移除旧 PENDING/WAITING 语义，V11 已迁移存量）
ALTER TABLE aircraft_instruction ADD CONSTRAINT chk_instruction_status_v2 CHECK (status IN (
    'RECEIVED', 'VALIDATED', 'BLOCKED', 'DISPATCHING', 'EXECUTING',
    'COMPLETED', 'REPLACED', 'FAILED', 'TIMED_OUT', 'CANCELLED', 'REJECTED'));

-- 3. 指令幂等键唯一（详细设计 5.0：UNIQUE(caller_scope, idempotency_key)）
CREATE INDEX uq_instruction_idempotency ON aircraft_instruction (exercise_aircraft_id,
    idempotency_key);

-- 4. 活动呼号键唯一收紧：删除后置 NULL 已在应用层维护，这里兜底防重复非空键
CREATE UNIQUE INDEX uq_aircraft_active_callsign ON exercise_aircraft (exercise_group_id,
    active_callsign_key);

-- 5. 引擎实例当前实例查询索引（部分索引语法 H2/MySQL 均不支持，用普通组合索引；
--    同组最多一个非 STOPPED 当前实例由应用层 assertCurrentInstance 保证）
CREATE INDEX idx_engine_group_state ON engine_instance (exercise_group_id, state);

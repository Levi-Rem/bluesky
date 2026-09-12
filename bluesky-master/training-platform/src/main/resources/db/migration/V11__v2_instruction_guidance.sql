-- V11：v2 指令内核（详细设计 2.2 §5.4）
-- 遗留 PENDING/WAITING 语义废弃：PENDING 迁移为 CANCELLED（只读终态）

ALTER TABLE aircraft_instruction ADD COLUMN exercise_group_id VARCHAR(64);
ALTER TABLE aircraft_instruction ADD COLUMN source_terminal_id VARCHAR(64);
ALTER TABLE aircraft_instruction ADD COLUMN conflict_key VARCHAR(160);
ALTER TABLE aircraft_instruction ADD COLUMN scheduling VARCHAR(24) NOT NULL DEFAULT 'REPLACE';
ALTER TABLE aircraft_instruction ADD COLUMN predecessor_id CHAR(36);
ALTER TABLE aircraft_instruction ADD COLUMN replaced_instruction_id CHAR(36);
ALTER TABLE aircraft_instruction ADD COLUMN parent_instruction_id CHAR(36);
ALTER TABLE aircraft_instruction ADD COLUMN execution_phase VARCHAR(48);
ALTER TABLE aircraft_instruction ADD COLUMN idempotency_key VARCHAR(128);

ALTER TABLE aircraft_instruction ADD CONSTRAINT chk_instruction_scheduling
    CHECK (scheduling IN ('REPLACE', 'AFTER_COMPLETION'));

-- LegacyInstructionMigrator.mapTerminalStates：旧 PENDING 映射为 CANCELLED
UPDATE aircraft_instruction SET status = 'CANCELLED' WHERE status = 'PENDING';

CREATE TABLE instruction_blocker (
    id CHAR(36) PRIMARY KEY,
    instruction_id VARCHAR(64) NOT NULL,
    reason VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    released_at TIMESTAMP(3),
    CONSTRAINT uq_blocker_instruction_reason UNIQUE (instruction_id, reason),
    CONSTRAINT chk_blocker_reason CHECK (reason IN (
        'TRAINING_PAUSED', 'PREDECESSOR_ACTIVE', 'ENGINE_RECOVERING',
        'SCHEDULED_TIME_NOT_REACHED'))
);

CREATE INDEX idx_blocker_instruction ON instruction_blocker (instruction_id, released_at);

CREATE TABLE guidance_target (
    id CHAR(36) PRIMARY KEY,
    instruction_id VARCHAR(64) NOT NULL,
    aircraft_id VARCHAR(64) NOT NULL,
    channel VARCHAR(24) NOT NULL,
    state VARCHAR(24) NOT NULL DEFAULT 'PENDING_APPLY',
    target_json VARCHAR(4000) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_guidance_state CHECK (state IN (
        'PENDING_APPLY', 'ACTIVE', 'SUPERSEDED', 'CLEARED', 'FAILED'))
);

CREATE INDEX idx_guidance_aircraft ON guidance_target (aircraft_id, channel, state);

CREATE TABLE composite_instruction_child (
    id CHAR(36) PRIMARY KEY,
    parent_id VARCHAR(64) NOT NULL,
    child_id VARCHAR(64) NOT NULL,
    channel VARCHAR(24) NOT NULL,
    required TINYINT(1) NOT NULL DEFAULT 1,
    CONSTRAINT uq_composite_child UNIQUE (parent_id, channel)
);

CREATE TABLE dispatch_slot (
    aircraft_id VARCHAR(64) NOT NULL,
    conflict_key VARCHAR(160) NOT NULL,
    instruction_id VARCHAR(64) NOT NULL,
    claimed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (aircraft_id, conflict_key)
);

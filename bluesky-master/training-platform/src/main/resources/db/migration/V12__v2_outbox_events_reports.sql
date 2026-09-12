-- V12：可靠业务事件、终端投递与流纪元（详细设计 2.2 §5.0/§9.5）
-- 一个逻辑事件一条 business_event；终端差异只体现在 terminal_event_delivery。

CREATE TABLE business_event (
    id CHAR(36) PRIMARY KEY,
    exercise_group_id VARCHAR(64) NOT NULL,
    group_sequence BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    source_outbox_id CHAR(36),
    entity_id VARCHAR(64),
    system_time_utc TIMESTAMP(3) NOT NULL,
    simulation_time_seconds DECIMAL(15, 3) NOT NULL DEFAULT 0,
    payload MEDIUMTEXT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_business_event_group_sequence UNIQUE (exercise_group_id, group_sequence),
    CONSTRAINT uq_business_event_source_outbox UNIQUE (source_outbox_id)
);

CREATE INDEX idx_business_event_group ON business_event (exercise_group_id, group_sequence);

CREATE TABLE terminal_event_delivery (
    id CHAR(36) PRIMARY KEY,
    terminal_id VARCHAR(64) NOT NULL,
    stream_epoch VARCHAR(64) NOT NULL,
    delivery_sequence BIGINT NOT NULL,
    business_event_id CHAR(36) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uq_terminal_delivery_sequence UNIQUE (terminal_id, stream_epoch, delivery_sequence),
    CONSTRAINT fk_delivery_business_event FOREIGN KEY (business_event_id)
        REFERENCES business_event (id)
);

CREATE INDEX idx_delivery_terminal_after
    ON terminal_event_delivery (terminal_id, stream_epoch, delivery_sequence);

-- 流纪元：服务端重启不得改变；仅显式投影重建才更换（详细设计 9.5.3）
CREATE TABLE sse_stream_epoch (
    singleton TINYINT NOT NULL DEFAULT 0,
    epoch VARCHAR(64) NOT NULL,
    rebuilt_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (singleton),
    CONSTRAINT chk_stream_epoch_singleton CHECK (singleton = 0)
);

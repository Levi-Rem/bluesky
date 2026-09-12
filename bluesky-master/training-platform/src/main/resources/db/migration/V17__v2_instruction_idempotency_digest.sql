-- 指令幂等请求摘要（详细设计 9.1：同键异摘要必须 409 IDEMPOTENCY_KEY_REUSED）。
-- 修复：指令提交路径原先仅按（航空器, 幂等键）回放，未比对请求摘要，
-- 同键不同体（如仅 scheduling 不同）被误当重放返回 202。
ALTER TABLE aircraft_instruction
    ADD COLUMN idempotency_request_digest CHAR(64) NULL;

CREATE INDEX idx_instruction_idem_digest
    ON aircraft_instruction (exercise_aircraft_id, idempotency_key);

-- M2：规则自动暂停（ORC-07）、Turn 跨实例 trace 传播（GOV-08）
ALTER TABLE orch_rule ADD COLUMN paused_at TIMESTAMPTZ;
ALTER TABLE orch_rule ADD COLUMN paused_reason VARCHAR(255);
ALTER TABLE orch_rule ADD COLUMN consecutive_failures INT NOT NULL DEFAULT 0;
ALTER TABLE runtime_turn ADD COLUMN traceparent VARCHAR(64);

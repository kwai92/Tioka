-- OBS-03 / ADR-073：关联上下文列（request_id / subject；audit_event.request_id 已在 V127）
-- PK-07 从本契约原样复制到 server migration。

ALTER TABLE runtime_turn ADD COLUMN IF NOT EXISTS request_id VARCHAR(64);
ALTER TABLE runtime_turn ADD COLUMN IF NOT EXISTS subject VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_runtime_turn_request_id ON runtime_turn (request_id);

ALTER TABLE runtime_approval ADD COLUMN IF NOT EXISTS request_id VARCHAR(64);
ALTER TABLE runtime_approval ADD COLUMN IF NOT EXISTS subject VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_runtime_approval_request_id ON runtime_approval (request_id);

ALTER TABLE orch_run ADD COLUMN IF NOT EXISTS request_id VARCHAR(64);
ALTER TABLE orch_run ADD COLUMN IF NOT EXISTS subject VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_orch_run_request_id ON orch_run (request_id);

ALTER TABLE model_usage ADD COLUMN IF NOT EXISTS request_id VARCHAR(64);
ALTER TABLE model_usage ADD COLUMN IF NOT EXISTS subject VARCHAR(255);
ALTER TABLE model_usage ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_model_usage_request_id ON model_usage (request_id);

ALTER TABLE audit_event ADD COLUMN IF NOT EXISTS subject VARCHAR(255);
CREATE INDEX IF NOT EXISTS idx_audit_subject_create ON audit_event (subject, create_at);

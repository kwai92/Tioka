-- M1 收口 OBS-01：审计关联请求号（ADR-072）
ALTER TABLE audit_event ADD COLUMN IF NOT EXISTS request_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_audit_request_id ON audit_event (request_id);

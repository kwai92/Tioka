-- M1：审计转发标记（GOV-02）
ALTER TABLE audit_event ADD COLUMN forwarded BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX idx_audit_unforwarded ON audit_event (ts) WHERE forwarded = FALSE;
CREATE INDEX idx_audit_actor_ts ON audit_event (actor_id, ts);

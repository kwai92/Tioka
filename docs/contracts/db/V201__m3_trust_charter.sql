-- V201__m3_trust_charter.sql
-- TRU-01：Agent 授权书状态与复核时间、trust_charter_event、orch_mandate.agent_version_id
-- 字段按 ADR-032「数据模型」；无 tenant_id（F10 / F40）

ALTER TABLE agent_agent
    ADD COLUMN charter_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE agent_agent
    ADD COLUMN reviewed_at TIMESTAMPTZ;

ALTER TABLE orch_mandate
    ADD COLUMN agent_version_id VARCHAR(64);

CREATE TABLE trust_charter_event (
    id           VARCHAR(32)  PRIMARY KEY,
    workspace_id VARCHAR(64)  NOT NULL,
    agent_id     VARCHAR(64)  NOT NULL,
    kind         VARCHAR(16)  NOT NULL,
    reason       TEXT,
    from_owner   VARCHAR(64),
    to_owner     VARCHAR(64),
    actor        VARCHAR(64),
    create_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by    VARCHAR(64)  NOT NULL DEFAULT 'system'
);

CREATE INDEX idx_trust_charter_event_agent ON trust_charter_event (agent_id, create_at);
CREATE INDEX idx_trust_charter_event_ws ON trust_charter_event (workspace_id, create_at);

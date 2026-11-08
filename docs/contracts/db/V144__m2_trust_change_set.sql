-- M2：变更单 trust_action 与 mcp_tool 可逆性列（TRU-05）。施工设计：M2-05-预演与变更单.md；ADR-033。
CREATE TABLE trust_action (
    id                VARCHAR(32)  PRIMARY KEY,
    workspace_id      VARCHAR(64)  NOT NULL,
    agent_id          VARCHAR(64)  NOT NULL,
    agent_version_id  VARCHAR(64)  NOT NULL,
    thread_id         VARCHAR(64)  NOT NULL,
    turn_id           VARCHAR(32)  NOT NULL,
    orch_run_id       VARCHAR(32),
    item_id           VARCHAR(32)  NOT NULL UNIQUE,
    seq               INT          NOT NULL,
    tool_name         VARCHAR(255) NOT NULL,
    action_class      VARCHAR(64)  NOT NULL,
    risk              VARCHAR(16)  NOT NULL,
    reversibility     VARCHAR(16)  NOT NULL,
    status            VARCHAR(24)  NOT NULL,
    gate              VARCHAR(24)  NOT NULL,
    args_json         TEXT         NOT NULL,
    change_set_json   TEXT         NOT NULL,
    fingerprint       VARCHAR(64),
    estimated         BOOLEAN      NOT NULL,
    valid_until       TIMESTAMPTZ,
    records           INT          NOT NULL DEFAULT 0,
    amount_micros     BIGINT,
    approval_id       VARCHAR(32),
    receipt_json      TEXT,
    undo_deadline     TIMESTAMPTZ,
    undone_by         VARCHAR(64),
    undo_reason       TEXT,
    create_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_trust_action_turn ON trust_action (turn_id, seq);
CREATE INDEX idx_trust_action_run ON trust_action (orch_run_id, seq);
CREATE INDEX idx_trust_action_agent ON trust_action (agent_id, action_class, create_at);

ALTER TABLE mcp_tool ADD COLUMN reversibility VARCHAR(16) NOT NULL DEFAULT 'IRREVERSIBLE';
ALTER TABLE mcp_tool ADD COLUMN compensate_tool VARCHAR(255);
UPDATE mcp_tool SET reversibility = 'NONE' WHERE risk = 'READ';

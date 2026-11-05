-- M2：审批台查询、轨迹按步用量、Flow 节点级运行与人工节点挂起（HCI-05、HCI-07、FLW-03）。施工设计：M2-04-审批台与轨迹.md
ALTER TABLE runtime_approval ADD COLUMN requested_by VARCHAR(64);
ALTER TABLE runtime_approval ADD COLUMN title VARCHAR(255);
ALTER TABLE runtime_approval ADD COLUMN risk VARCHAR(16);
ALTER TABLE runtime_approval ADD COLUMN agent_id VARCHAR(64);
CREATE INDEX idx_approval_requested_by ON runtime_approval (requested_by, create_at);

CREATE TABLE runtime_approval_assignee (
    approval_id VARCHAR(32) NOT NULL,
    user_id     VARCHAR(64) NOT NULL,
    PRIMARY KEY (approval_id, user_id)
);
CREATE INDEX idx_approval_assignee_user ON runtime_approval_assignee (user_id);

ALTER TABLE model_usage ADD COLUMN step INT;
CREATE INDEX idx_usage_turn ON model_usage (turn_id);

-- flow_run 按 ADR-019 重建（ADR-029 第 35 条：无生产数据）
DROP TABLE IF EXISTS flow_run;
CREATE TABLE flow_run (
    id             VARCHAR(32) PRIMARY KEY,
    workspace_id   VARCHAR(64),
    version_id     VARCHAR(64) NOT NULL,
    status         VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING','WAITING','SUCCEEDED','FAILED','CANCELLED')),
    origin         VARCHAR(16) NOT NULL,                 -- studio / api / tool / mcp / orch
    publication_id VARCHAR(32),
    run_as_json    TEXT        NOT NULL,
    input_json     TEXT,
    output_json    TEXT,
    error          TEXT,
    state_json     TEXT,                                 -- M2 专用：挂起快照（EngineSnapshot），M3 删除
    create_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    create_by      VARCHAR(64) NOT NULL DEFAULT 'system',
    update_by      VARCHAR(64) NOT NULL DEFAULT 'system',
    ended_at       TIMESTAMPTZ
);
CREATE INDEX idx_flow_run_version ON flow_run (version_id, create_at);

CREATE TABLE flow_node_run (
    id          VARCHAR(32) PRIMARY KEY,
    run_id      VARCHAR(32) NOT NULL,
    node_id     VARCHAR(64) NOT NULL,
    iteration   INT         NOT NULL DEFAULT 0,
    status      VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','RUNNING','WAITING','SUCCEEDED','FAILED','SKIPPED','CANCELLED')),
    attempt     INT         NOT NULL DEFAULT 0,
    wait_key    VARCHAR(64),                             -- approvalId 或 turnId
    input_json  TEXT,
    output_json TEXT,
    error       TEXT,
    worker_id   VARCHAR(64),
    lease_until TIMESTAMPTZ,
    started_at  TIMESTAMPTZ,
    ended_at    TIMESTAMPTZ,
    UNIQUE (run_id, node_id, iteration)
);
CREATE INDEX idx_node_run_wait ON flow_node_run (wait_key);

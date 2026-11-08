-- M2：Flow 节点级运行与人工节点挂起（FLW-03）。从 V143 拆出。施工设计：M2-04-审批台与轨迹.md
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

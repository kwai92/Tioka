-- V214__m3_mh06_route_event_competence.sql
-- MH-06 / MH-07（ADR-024「一次选模」「胜任度」）；无 tenant_id，workspace 隔离（与 model_usage 同口径）
-- 路由事件时间线：每步（DECIDE/SELECT/REUSE/FAILOVER/FAIL）一条，构成会话路由时间线
-- 胜任度：Turn 结束打 0–1 分，M3 只记录不参与决策；UNIQUE(turn_id) 承接 ADR turn_id 主键语义

CREATE TABLE model_route_event (
    id                 VARCHAR(32)  PRIMARY KEY,
    workspace_id       VARCHAR(64),
    thread_id          VARCHAR(64),
    turn_id            VARCHAR(64),
    plan_id            VARCHAR(64),
    plan_version_no    INT,
    kind               VARCHAR(16)  NOT NULL,   -- DECIDE / SELECT / REUSE / FAILOVER / FAIL
    mode               VARCHAR(16),             -- FIXED / DIFFICULTY / BRANCH / FREE_FIRST
    decider_id         VARCHAR(64),
    decided_group      VARCHAR(64),
    selected_binding_id VARCHAR(64),
    confidence         DOUBLE PRECISION,
    reason             TEXT,
    latency_ms         INT,
    create_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by          VARCHAR(64)  NOT NULL DEFAULT 'system'
);
CREATE INDEX idx_route_event_thread ON model_route_event (thread_id, create_at);
CREATE INDEX idx_route_event_turn   ON model_route_event (turn_id, create_at);
CREATE INDEX idx_route_event_ws_ts  ON model_route_event (workspace_id, create_at);

CREATE TABLE model_competence (
    id           VARCHAR(32)  PRIMARY KEY,
    workspace_id VARCHAR(64),
    binding_id   VARCHAR(64)  NOT NULL,
    turn_id      VARCHAR(64)  NOT NULL,
    score        DOUBLE PRECISION NOT NULL CHECK (score >= 0 AND score <= 1),
    source       VARCHAR(32)  NOT NULL,        -- user_feedback / judge_model / eval 等
    create_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by    VARCHAR(64)  NOT NULL DEFAULT 'system',
    UNIQUE (turn_id, binding_id)
);
CREATE INDEX idx_competence_binding ON model_competence (binding_id, create_at DESC);

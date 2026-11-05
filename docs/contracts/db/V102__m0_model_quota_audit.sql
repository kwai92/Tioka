-- M0 契约迁移 3/3：模型调用收口、配额与审计（ADR-017；定案 ADR-029 第 7、8、13 条）

-- 轮次冻结，替代 model_session_freeze（旧表由 V120 在 MH-01 合入时删除）
CREATE TABLE model_turn_freeze (
    session_id     VARCHAR(64)  NOT NULL,
    turn_id        VARCHAR(64)  NOT NULL,
    binding_id     VARCHAR(64)  NOT NULL,
    reason         VARCHAR(255),
    failover_count INT          NOT NULL DEFAULT 0,
    create_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by      VARCHAR(64)  NOT NULL DEFAULT 'system',
    update_by      VARCHAR(64)  NOT NULL DEFAULT 'system',
    PRIMARY KEY (session_id, turn_id)
);

-- 用量明细，替代 cost_event；只增不改
-- cost_event 由 V121 在 MH-05 合入时删除
CREATE TABLE model_usage (
    id                VARCHAR(32)  PRIMARY KEY,
    tenant_id         VARCHAR(64)  NOT NULL,
    workspace_id      VARCHAR(64),
    user_id           VARCHAR(64),
    agent_id          VARCHAR(64),
    session_id        VARCHAR(64),
    turn_id           VARCHAR(64),
    purpose           VARCHAR(16)  NOT NULL,                 -- ModelPurpose
    binding_id        VARCHAR(64),
    model_ref         VARCHAR(255),
    prompt_tokens     INT          NOT NULL DEFAULT 0,
    completion_tokens INT          NOT NULL DEFAULT 0,
    cost_micros       BIGINT,
    latency_ms        INT,
    status            VARCHAR(16)  NOT NULL,                 -- SUCCESS / ERROR / FAILOVER
    create_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by         VARCHAR(64)  NOT NULL DEFAULT 'system'
);
CREATE INDEX idx_usage_ws_ts    ON model_usage (workspace_id, create_at);
CREATE INDEX idx_usage_agent_ts ON model_usage (agent_id, create_at);

-- 配额，替代 sys_quota；Redis 计数，60 秒落库
-- sys_quota 由 V122 在 GOV-03 合入时删除
CREATE TABLE gov_quota (
    scope_type   VARCHAR(16)  NOT NULL,                      -- workspace / agent / user / tool
    scope_id     VARCHAR(64)  NOT NULL,
    metric       VARCHAR(32)  NOT NULL
        CHECK (metric IN ('tokens_month','concurrent_turns','tool_rpm')),
    limit_value  BIGINT       NOT NULL,
    used_value   BIGINT       NOT NULL DEFAULT 0,
    window_start TIMESTAMPTZ,
    create_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by    VARCHAR(64)  NOT NULL DEFAULT 'system',
    update_by    VARCHAR(64)  NOT NULL DEFAULT 'system',
    PRIMARY KEY (scope_type, scope_id, metric)
);

-- 审计；只增不改，哈希链列预留到 M4（GOV-09）
CREATE TABLE audit_event (
    id            VARCHAR(32)  PRIMARY KEY,
    ts            TIMESTAMPTZ  NOT NULL,
    tenant_id     VARCHAR(64)  NOT NULL,
    workspace_id  VARCHAR(64),
    actor_type    VARCHAR(16),
    actor_id      VARCHAR(64),
    action        VARCHAR(64)  NOT NULL,
    resource_type VARCHAR(32),
    resource_id   VARCHAR(64),
    outcome       VARCHAR(16)  NOT NULL CHECK (outcome IN ('SUCCESS','DENIED','ERROR')),
    reason        TEXT,
    trace_id      VARCHAR(64),
    client_ip     VARCHAR(64),
    details_json  TEXT,
    prev_hash     VARCHAR(64),
    hash          VARCHAR(64)
);
CREATE INDEX idx_audit_ws_ts       ON audit_event (workspace_id, ts);
CREATE INDEX idx_audit_resource    ON audit_event (resource_type, resource_id, ts);

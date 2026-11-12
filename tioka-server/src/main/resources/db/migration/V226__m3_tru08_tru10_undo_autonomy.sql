-- V226__m3_tru08_tru10_undo_autonomy.sql
-- C-21 / TRU-08 爆炸半径与撤销熔断 + TRU-10 自动降级与通知（M3-05 §6/§8/§10）。
-- 号段纪律：顺序取号（V223 C-16、V224 C-17、V225 C-18 已占用）。
-- 三张新表都带 workspace_id，不进 workspace-scope-exempt 清单。

-- 熔断阈值按空间可配（缺省 1h 内撤销 3 次 / 补偿失败 1 次）；配置变化写审计。
CREATE TABLE trust_undo_circuit_policy (
    workspace_id         VARCHAR(64)  PRIMARY KEY,
    undo_threshold       INT          NOT NULL DEFAULT 3,
    comp_fail_threshold  INT          NOT NULL DEFAULT 1,
    window_minutes       INT          NOT NULL DEFAULT 60,
    update_by            VARCHAR(64)  NOT NULL DEFAULT 'system',
    create_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 熔断开启状态：同一 Agent 同一滑动窗口只发一次 tioka.trust.undo_circuit_open.v1。
ALTER TABLE trust_undo_circuit ADD COLUMN opened_at    TIMESTAMPTZ;
ALTER TABLE trust_undo_circuit ADD COLUMN open_reason  VARCHAR(64);

-- TRU-10 降级幂等：一次撤销涉及多个 action_undone，按 (撤销范围, 动作类别) 只降一级；
-- 补偿失败 / 熔断按 (事件族, Agent, 窗口) 只降一次。重复投递只处理一次。
CREATE TABLE trust_autonomy_demotion (
    demotion_key  VARCHAR(160) PRIMARY KEY,
    workspace_id  VARCHAR(64)  NOT NULL,
    agent_id      VARCHAR(64)  NOT NULL,
    action_class  VARCHAR(64)  NOT NULL,
    event_id      VARCHAR(64),
    from_level    VARCHAR(4),
    to_level      VARCHAR(4),
    create_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_trust_autonomy_demotion_agent ON trust_autonomy_demotion (agent_id, create_at);

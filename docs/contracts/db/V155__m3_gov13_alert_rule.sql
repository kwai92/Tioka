-- GOV-13（ADR-041 §5.3）：告警规则与告警事件。
--   规则 = 监控对象（供应商 / binding / 别名 / 配额作用对象 / 全部）
--          + 条件（健康状态变为某值；错误率、P95 延迟、故障接力率在 N 分钟内超过阈值；
--                 配额越过预警比例或用完；有配置被发布或回滚，用于知会）
--          + 阈值 / 统计窗口 + 通知渠道与静默时段。
--   求值：每 30 秒由一个实例执行（Redis 锁），事件入库可查历史。
--   去重与恢复：同一规则对同一对象只在状态变化时发；恢复时发恢复通知；静默时段内只入事件不发通知。
--   内置默认规则（builtin=TRUE，幂等播种）：供应商 DOWN；任一供给侧配额达到 80% 或 100%；
--          10 分钟内故障接力率超过 20%。
--   号段纪律：GOV-13 用 ADR-041 §9 预留号 V155（本组 V150〜V159）。
--   两表均为系统级监控对象，不带 workspace_id，登记 ScopeLine 旁路白名单
--   （PersistenceProperties.workspaceScopeExemptTables）。

CREATE TABLE ops_alert_rule (
    id              VARCHAR(32)  PRIMARY KEY,
    name            VARCHAR(128) NOT NULL,
    target_type     VARCHAR(16)  NOT NULL
        CHECK (target_type IN ('VENDOR','BINDING','ALIAS','QUOTA_SCOPE','ALL')),
    target_id       VARCHAR(64),                              -- target_type='ALL' 时为空
    condition_type  VARCHAR(24)  NOT NULL
        CHECK (condition_type IN ('HEALTH_STATUS','ERROR_RATE','P95_LATENCY','FAILOVER_RATE',
                                  'QUOTA_WARN','QUOTA_EXHAUSTED','CONFIG_PUBLISHED','CONFIG_ROLLED_BACK')),
    comparator      VARCHAR(4)   NOT NULL
        CHECK (comparator IN ('GT','GTE','LT','LTE','EQ','NE')),
    threshold_value DOUBLE PRECISION,                         -- 数值条件阈值（利用率/比例/毫秒）
    threshold_text  VARCHAR(64),                              -- 文本条件阈值（如健康状态 DOWN）
    window_minutes  INT          NOT NULL DEFAULT 10,         -- 统计窗口（分钟）
    severity        VARCHAR(16)  NOT NULL
        CHECK (severity IN ('INFO','WARNING','CRITICAL')),
    channels        VARCHAR(128) NOT NULL DEFAULT 'in_app',   -- 逗号分隔：in_app / im:feishu / email
    recipients      VARCHAR(512),                             -- 逗号分隔接收人；空则用系统缺省
    silence_start   VARCHAR(5),                               -- 静默时段起 HH:mm（含），可空
    silence_end     VARCHAR(5),                               -- 静默时段止 HH:mm（不含），可空
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    builtin         BOOLEAN      NOT NULL DEFAULT FALSE,      -- 内置默认规则（按名字幂等播种）
    last_fired_at   TIMESTAMPTZ,                              -- 事件型条件（配置发布/回滚）的游标水位
    description     TEXT,
    create_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by       VARCHAR(64)  NOT NULL DEFAULT 'system',
    update_by       VARCHAR(64)  NOT NULL DEFAULT 'system'
);
CREATE UNIQUE INDEX uk_alert_rule_builtin_name ON ops_alert_rule (name) WHERE builtin;
CREATE INDEX idx_alert_rule_enabled ON ops_alert_rule (enabled);

CREATE TABLE ops_alert_event (
    id              VARCHAR(32)  PRIMARY KEY,
    rule_id         VARCHAR(32)  NOT NULL,
    rule_name       VARCHAR(128) NOT NULL,
    target_type     VARCHAR(16)  NOT NULL,
    target_id       VARCHAR(64),
    condition_type  VARCHAR(24)  NOT NULL,
    state           VARCHAR(16)  NOT NULL CHECK (state IN ('FIRING','RESOLVED')),
    severity        VARCHAR(16)  NOT NULL,
    metric_value    DOUBLE PRECISION,
    threshold_value DOUBLE PRECISION,
    message         TEXT,
    dedup_key       VARCHAR(160) NOT NULL,
    notified        BOOLEAN      NOT NULL DEFAULT FALSE,
    observed_at     TIMESTAMPTZ  NOT NULL,
    create_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_alert_event_rule_target ON ops_alert_event (rule_id, target_id, observed_at DESC);
CREATE INDEX idx_alert_event_state       ON ops_alert_event (state, observed_at DESC);
CREATE INDEX idx_alert_event_observed    ON ops_alert_event (observed_at DESC);

COMMENT ON TABLE  ops_alert_rule             IS 'GOV-13 / ADR-041 §5.3：告警规则（即时型配置对象，写 cfg_change 流水）';
COMMENT ON COLUMN ops_alert_rule.target_type IS 'VENDOR / BINDING / ALIAS / QUOTA_SCOPE / ALL';
COMMENT ON COLUMN ops_alert_rule.last_fired_at IS '事件型条件游标：仅取该时刻之后新发生的配置变更';
COMMENT ON TABLE  ops_alert_event            IS 'GOV-13 / ADR-041 §5.3：告警事件历史（FIRING / RESOLVED 状态变化）';
COMMENT ON COLUMN ops_alert_event.dedup_key  IS '去重键：rule_id:target_id:condition:state';

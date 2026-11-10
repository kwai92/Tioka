-- V203__m3_trust_autonomy.sql
-- TRU-09 自治等级（M3-05 §7/§11）。字段按 ADR-034「数据模型」；
-- trust_shadow_case 与编排规则影子列在 M4（不建）。
-- trust_action 增 autonomy_level：留存第 10 步判定等级（抽检与统计取数用）。

CREATE TABLE trust_autonomy (
    agent_id                 VARCHAR(64)  NOT NULL,
    action_class             VARCHAR(64)  NOT NULL,
    level                    VARCHAR(4)   NOT NULL,
    observe_until            TIMESTAMPTZ,
    promotion_blocked_until  TIMESTAMPTZ,
    update_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (agent_id, action_class)
);

CREATE TABLE trust_autonomy_event (
    id            VARCHAR(32)  PRIMARY KEY,
    agent_id      VARCHAR(64)  NOT NULL,
    action_class  VARCHAR(64)  NOT NULL,
    kind          VARCHAR(16)  NOT NULL,      -- PROMOTE / DEMOTE / INCIDENT / RESET / SAMPLING
    from_level    VARCHAR(4),
    to_level      VARCHAR(4),
    reason        TEXT,
    evidence_json TEXT,
    actor         VARCHAR(64),
    create_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_trust_autonomy_event_agent ON trust_autonomy_event (agent_id, action_class, create_at);

CREATE TABLE trust_autonomy_stats (
    agent_id      VARCHAR(64)  NOT NULL,
    action_class  VARCHAR(64)  NOT NULL,
    day           DATE         NOT NULL,
    stats_json    TEXT         NOT NULL,
    PRIMARY KEY (agent_id, action_class, day)
);

ALTER TABLE trust_action ADD COLUMN autonomy_level VARCHAR(4);

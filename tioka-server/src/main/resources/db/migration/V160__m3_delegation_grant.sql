-- V160__m3_delegation_grant.sql
-- MAS-01（ADR-039 / M3-01）：委派授予表 rt_grant + runtime_thread.grant_id。
-- audit_event 五列（root_principal / on_behalf_of / chain_json / grant_id / data_label）
-- 已由 V221（OBS-05）提前建好，本迁移不重复建。
-- 列宽口径：以 V221 为准（grant_id VARCHAR(64)），M3-01 草稿的 VARCHAR(32) 已过时。

CREATE TABLE rt_grant (
    id             VARCHAR(32)  PRIMARY KEY,               -- Ids.newId("grt")
    parent_id      VARCHAR(32),                            -- 根授予为空
    root_principal VARCHAR(64)  NOT NULL,                  -- 链根 Principal（run_as），全链不变
    on_behalf_of   VARCHAR(64),                            -- SERVICE 模式代表经办人
    chain_json     TEXT         NOT NULL,                  -- ChainHop[]（agentVersionId + taskId）
    depth          INT          NOT NULL,                  -- 委派深度，根 = 1
    body_json      TEXT         NOT NULL,                  -- GrantBody（DelegationGrant + used_tokens + version，乐观锁 CAS）
    hash           VARCHAR(64)  NOT NULL,                  -- body 内容哈希
    status         VARCHAR(16)  NOT NULL,                  -- ACTIVE / REAPED / REVOKED
    expires_at     TIMESTAMPTZ  NOT NULL,
    create_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ended_at       TIMESTAMPTZ
);

CREATE INDEX idx_rt_grant_parent ON rt_grant (parent_id);

COMMENT ON TABLE rt_grant IS 'MAS-01 委派授予：权限只随委派收窄（ADR-039）。会话级内部对象，经 runtime_thread.grant_id 关联，无 workspace 列（按 thread 归属隔离）';

ALTER TABLE runtime_thread ADD COLUMN grant_id VARCHAR(64);

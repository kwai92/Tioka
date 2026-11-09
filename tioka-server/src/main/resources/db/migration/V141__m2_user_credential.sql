-- FND-08 代用户令牌保管（ADR-021 / 施工设计 M2-03）。
-- 契约源：docs/contracts/db/V141__m2_user_credential.sql（列定义照抄契约，不得漂移）。
-- flyway out-of-order: true（ADR-029 第 33 条）：V141 乱序合入，当前最高 V221。
-- 注：sys_user_credential 为用户个人凭证，非空间资源，无空间隔离列（契约权威），
--     须登记 tioka.persistence.workspace-scope-exempt-tables（PersistenceProperties deny-by-default）。
CREATE TABLE sys_user_credential (
    id                 VARCHAR(32)   PRIMARY KEY,
    user_id            VARCHAR(64)   NOT NULL,
    provider           VARCHAR(64)   NOT NULL,
    access_token_enc   TEXT          NOT NULL,
    refresh_token_enc  TEXT,
    access_expires_at  TIMESTAMPTZ   NOT NULL,
    refresh_expires_at TIMESTAMPTZ,
    scopes             VARCHAR(1024),
    key_version        INT           NOT NULL,
    status             VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED','REFRESH_FAILED')),
    create_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    update_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (user_id, provider)
);

-- (user_id, provider) 唯一约束已由 UNIQUE 提供查找索引；此处登记点查辅助索引命名惯例
CREATE INDEX idx_sys_user_credential_user_provider ON sys_user_credential (user_id, provider);

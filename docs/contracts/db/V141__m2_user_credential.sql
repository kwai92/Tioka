-- M2：代用户 OAuth 令牌保管（FND-08）
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

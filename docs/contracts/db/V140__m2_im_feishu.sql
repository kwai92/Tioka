-- M2：IM 绑定、会话映射、入站去重、出站引用、外部身份（CON-02、CON-03）。施工设计：M2-03-飞书入站与卡片.md
CREATE TABLE im_binding (
    id                     VARCHAR(32)  PRIMARY KEY,
    workspace_id           VARCHAR(64)  NOT NULL,
    channel                VARCHAR(32)  NOT NULL,
    app_id                 VARCHAR(128) NOT NULL,
    secret_ref             VARCHAR(255) NOT NULL,
    encrypt_key_ref        VARCHAR(255),
    verification_token_ref VARCHAR(255),
    card_sign_key_ref      VARCHAR(255) NOT NULL,
    default_agent_id       VARCHAR(64),
    orch_enabled           BOOLEAN      NOT NULL DEFAULT FALSE,
    identity_match         VARCHAR(16)  NOT NULL DEFAULT 'link' CHECK (identity_match IN ('link','email')),
    status                 VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    create_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by              VARCHAR(64)  NOT NULL DEFAULT 'system',
    update_by              VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted                BOOLEAN      NOT NULL DEFAULT FALSE,
    UNIQUE (channel, app_id)
);

CREATE TABLE im_conversation_map (
    binding_id         VARCHAR(32)  NOT NULL,
    external_chat_id   VARCHAR(128) NOT NULL,
    external_thread_id VARCHAR(128) NOT NULL DEFAULT '',
    conversation_id    VARCHAR(64)  NOT NULL,
    owner_user_id      VARCHAR(64)  NOT NULL,
    create_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (binding_id, external_chat_id, external_thread_id)
);

CREATE TABLE im_inbound_dedupe (
    binding_id  VARCHAR(32)  NOT NULL,
    event_id    VARCHAR(128) NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (binding_id, event_id)
);
CREATE INDEX idx_inbound_dedupe_ts ON im_inbound_dedupe (received_at);

CREATE TABLE im_outbound_ref (
    id                  VARCHAR(32)  PRIMARY KEY,
    binding_id          VARCHAR(32)  NOT NULL,
    external_message_id VARCHAR(128) NOT NULL,
    purpose             VARCHAR(16)  NOT NULL CHECK (purpose IN ('answer','approval','notice','bind')),
    ref_type            VARCHAR(16),
    ref_id              VARCHAR(64),
    last_render_hash    VARCHAR(64),
    create_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbound_ref ON im_outbound_ref (ref_type, ref_id);

CREATE TABLE sys_external_identity (
    source      VARCHAR(64)  NOT NULL,
    external_id VARCHAR(255) NOT NULL,
    union_id    VARCHAR(255),
    user_id     VARCHAR(64)  NOT NULL,
    create_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by   VARCHAR(64)  NOT NULL DEFAULT 'system',
    PRIMARY KEY (source, external_id)
);
CREATE INDEX idx_ext_identity_user ON sys_external_identity (user_id);

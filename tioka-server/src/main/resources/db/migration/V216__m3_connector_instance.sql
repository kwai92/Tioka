-- V216__m3_connector_instance.sql
-- CON-SDK：连接器实例登记、凭证引用、同步游标、健康状态（去掉 tenant_id）

CREATE TABLE con_connector_instance (
    id                     VARCHAR(32)  PRIMARY KEY,
    workspace_id           VARCHAR(64)  NOT NULL,
    connector_type         VARCHAR(64)  NOT NULL,
    name                   VARCHAR(128) NOT NULL,
    config_json            JSONB        NOT NULL DEFAULT '{}',
    credential_ref         VARCHAR(255),
    sync_cursor            TEXT,
    health_status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    last_success_at        TIMESTAMPTZ,
    last_error             TEXT,
    consecutive_failures   INT          NOT NULL DEFAULT 0,
    rate_limit_qps         DOUBLE PRECISION NOT NULL DEFAULT 5,
    status                 VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    create_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by              VARCHAR(64)  NOT NULL DEFAULT 'system',
    update_by              VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted                BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_con_instance_ws ON con_connector_instance (workspace_id);
CREATE INDEX idx_con_instance_type ON con_connector_instance (connector_type);

-- V210__m3_plugin_registry.sql
-- FND-12 插件与适配器登记（去掉 tenant_id）

CREATE TABLE plugin_registry (
    plugin_key      VARCHAR(96)  NOT NULL,
    version         VARCHAR(32)  NOT NULL,
    spi_type        VARCHAR(32)  NOT NULL,
    spi_version     VARCHAR(16)  NOT NULL,
    permissions     JSONB        NOT NULL DEFAULT '[]',
    entry_class     VARCHAR(255) NOT NULL,
    content_hash    CHAR(64)     NOT NULL,
    signature_status VARCHAR(16) NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT false,
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (plugin_key, version)
);

CREATE TABLE plugin_adapter (
    adapter_id      VARCHAR(26)  PRIMARY KEY,
    plugin_key      VARCHAR(96)  NOT NULL,
    plugin_version  VARCHAR(32)  NOT NULL,
    adapter_key     VARCHAR(96)  NOT NULL,
    config_json     JSONB        NOT NULL DEFAULT '{}',
    credential_ref  VARCHAR(128),
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (plugin_key, plugin_version, adapter_key)
);

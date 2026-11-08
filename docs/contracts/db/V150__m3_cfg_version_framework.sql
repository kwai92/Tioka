-- V150__m3_cfg_version_framework.sql
-- GOV-12 配置版本框架（ADR-041 §2 / §9）；无 tenant_id
-- model_logical → model_alias 重命名与用途列留给 MH-08（避免本契约 PR 改动 M2 正在使用的投影表语义）

CREATE TABLE cfg_draft (
    object_type     VARCHAR(64)  NOT NULL,
    object_id       VARCHAR(64)  NOT NULL,
    spec            JSONB        NOT NULL,
    revision        BIGINT       NOT NULL DEFAULT 0,
    based_on_version BIGINT,
    update_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_by       VARCHAR(64)  NOT NULL DEFAULT 'system',
    PRIMARY KEY (object_type, object_id)
);

CREATE TABLE cfg_version (
    version_id       VARCHAR(64)  PRIMARY KEY,
    object_type      VARCHAR(64)  NOT NULL,
    object_id        VARCHAR(64)  NOT NULL,
    version_no       INT          NOT NULL,
    spec             JSONB        NOT NULL,
    content_hash     CHAR(64)     NOT NULL,
    status           VARCHAR(32)  NOT NULL,
    publish_note     VARCHAR(512),
    published_by     VARCHAR(64),
    published_at     TIMESTAMPTZ,
    previous_version_id VARCHAR(64),
    UNIQUE (object_type, object_id, version_no)
);

CREATE INDEX idx_cfg_version_object ON cfg_version (object_type, object_id, version_no DESC);

CREATE TABLE cfg_change (
    id           BIGSERIAL PRIMARY KEY,
    object_type  VARCHAR(64)  NOT NULL,
    target_key   VARCHAR(128) NOT NULL,
    before_json  JSONB,
    after_json   JSONB,
    actor_id     VARCHAR(64)  NOT NULL,
    reason       VARCHAR(512),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    trace_id     VARCHAR(64)
);

CREATE INDEX idx_cfg_change_target ON cfg_change (object_type, target_key, created_at DESC);

-- 投影表：当前生效版本指针（可空；MH-08/V151 回填）
ALTER TABLE model_provider
    ADD COLUMN IF NOT EXISTS current_version_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS current_version_no INT;

ALTER TABLE model_binding
    ADD COLUMN IF NOT EXISTS current_version_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS current_version_no INT;

ALTER TABLE model_logical
    ADD COLUMN IF NOT EXISTS current_version_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS current_version_no INT;

ALTER TABLE model_routing_plan
    ADD COLUMN IF NOT EXISTS current_version_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS current_version_no INT;

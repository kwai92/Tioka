-- V171__m3_schema_registry.sql
-- SCH-01 Schema 登记表（M3-03）；无 tenant_id

CREATE TABLE sch_schema (
    schema_key     VARCHAR(96) NOT NULL,
    version        INT         NOT NULL,
    json_schema    JSONB       NOT NULL,
    owner_pack     VARCHAR(64) NOT NULL,
    default_label  VARCHAR(16) NOT NULL DEFAULT 'INTERNAL',
    content_hash   CHAR(64)    NOT NULL,
    status         VARCHAR(16) NOT NULL,
    published_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (schema_key, version)
);

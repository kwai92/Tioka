-- M1：AgentSpec 草稿与不可变版本（AST-01）
-- 施工设计：M1-05-Agent资产与G1闸门基座.md。ADR-029 第 60 条：不带 tenant_id。
-- 旧数据由 V124（Java 迁移 V124__BackfillAgentSpec）回填，旧列与旧表由 V125 删除。

CREATE TABLE agent_draft (
    agent_id        VARCHAR(64)  PRIMARY KEY,
    spec_json       TEXT         NOT NULL,                   -- AgentSpec（agent-spec-v1.json）
    schema_version  INT          NOT NULL DEFAULT 1,
    revision        INT          NOT NULL DEFAULT 0,         -- 乐观锁
    base_version_id VARCHAR(64),                             -- 草稿基于哪个已发布版本
    update_at       TIMESTAMP    NOT NULL DEFAULT now(),  -- UTC，对齐 BaseDO LocalDateTime
    update_by       VARCHAR(64)  NOT NULL DEFAULT 'system'
);

ALTER TABLE agent_version ADD COLUMN version_no     INT;
ALTER TABLE agent_version ADD COLUMN spec_json      TEXT;
ALTER TABLE agent_version ADD COLUMN schema_version INT NOT NULL DEFAULT 1;
ALTER TABLE agent_version ADD COLUMN status         VARCHAR(16) NOT NULL DEFAULT 'PUBLISHED'
    CHECK (status IN ('PUBLISHED','PENDING_APPROVAL','RETIRED'));
ALTER TABLE agent_version ADD COLUMN note           VARCHAR(500);
ALTER TABLE agent_version ADD COLUMN published_at   TIMESTAMP;
ALTER TABLE agent_version ADD COLUMN published_by   VARCHAR(64);
-- snapshot_hash 沿用，改存 RFC 8785 规范化 JSON 的 SHA-256（十六进制 64 位）
CREATE UNIQUE INDEX uk_agent_version_no ON agent_version (agent_id, version_no) WHERE version_no IS NOT NULL;
CREATE INDEX idx_agent_version_plan ON agent_version (routing_plan_id);

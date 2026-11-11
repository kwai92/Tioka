-- V211__m3_knw05_ingest_job.sql
-- KNW-05 ingest 异步化与事务（M3-06 §3.1 / ADR-018 数据模型；F40：去掉 tenant_id）。
-- 1) kb_ingest_job：五阶段任务表（stage=PARSE/CHUNK/EMBED/SWAP，status=QUEUED/RUNNING/DONE/FAILED，
--    按阶段重试最多 3 次）。无 workspace_id 列（ADR-018 设计原文如此，doc_id 关联 kb_document），
--    登记 workspace-scope-exempt（PersistenceProperties，同 V174/V175 先例）。
--    source_type/source_ref/acl_principals_json 为约束 F39 增量：入库任务接受 ChangeEvent 作为来源，
--    aclPrincipals 可选；payload_json 携带提交载荷（上传字节/文本/事件内容）。
-- 2) kb_document 版本列：content_hash 去重（内容未变跳过）、active_version 原子切换（ADR-018 第 5 步）。
-- 3) kb_chunk.doc_version：新版本分块带版本写入，SWAP 单事务切 active_version 并删旧版本分块；
--    检索 SQL 过滤 c.doc_version = d.active_version，存量数据默认 0/0 行为不变。

CREATE TABLE kb_ingest_job (
    id                   VARCHAR(32) PRIMARY KEY,
    doc_id               VARCHAR(64) NOT NULL,
    doc_version          INT         NOT NULL,
    stage                VARCHAR(16) NOT NULL,             -- PARSE / CHUNK / EMBED / SWAP
    status               VARCHAR(16) NOT NULL,             -- QUEUED / RUNNING / DONE / FAILED
    attempts             INT         NOT NULL DEFAULT 0,
    run_as_json          TEXT        NOT NULL DEFAULT '{}',
    error                TEXT,
    lease_until          TIMESTAMP,
    source_type          VARCHAR(16) NOT NULL,             -- UPLOAD / TEXT / CHANGE_EVENT（F39）
    source_ref           VARCHAR(128),
    acl_principals_json  TEXT,                             -- F39：可选，源权限主体
    payload_json         TEXT        NOT NULL DEFAULT '{}',
    create_at            TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_at            TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by            VARCHAR(64) NOT NULL DEFAULT 'system',
    update_by            VARCHAR(64) NOT NULL DEFAULT 'system',
    deleted              BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_kb_ingest_job_status ON kb_ingest_job (status, update_at);

ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS datasource_id   VARCHAR(64);
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS external_id     VARCHAR(255);
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS external_version VARCHAR(128);
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS content_hash    VARCHAR(64);
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS active_version  INT NOT NULL DEFAULT 0;

ALTER TABLE kb_chunk ADD COLUMN IF NOT EXISTS doc_version INT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_kb_document_kb_hash ON kb_document (kb_id, content_hash);

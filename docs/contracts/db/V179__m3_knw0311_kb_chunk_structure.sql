-- V179__m3_knw0311_kb_chunk_structure.sql
-- KNW-11（共用解析服务）+ KNW-03（结构感知切块与父子块）表变更合并一次 DDL（ADR-055 §5）。
-- KNW-11：kb_document 增解析产物溯源列（parseId / 解析方案键与解析器版本 / 解析状态 /
--   质量指标 JSON / 警告 JSON）。解析经 DOC 域共用服务（DocumentApi.ingest + units），
--   「同一文件只解析一次」由 DocumentService.findByDedupKey(sha256, parser_version, space_id) 承载。
-- KNW-03：kb_base 增切块策略与上下文增强开关；kb_chunk 增父子块结构列
--   （块类型 PARENT/CHILD、父块引用、起止页、来源 blockId 列表、标题路径上下文前缀）。
--   坐标不落列：经 blockId 从解析结果（doc_unit）取，本批存 blockIds 列表。
-- 三表均已有 workspace_id（kb_base 直接携带；kb_document/kb_chunk 为既有冗余/关联列），
-- 无新增表，PersistenceProperties 白名单不动。

-- ---- KNW-03：kb_base 解析/切块策略（缺省「自动」= chunk_policy_json 为 NULL）----
ALTER TABLE kb_base ADD COLUMN IF NOT EXISTS chunk_policy_json    TEXT;
ALTER TABLE kb_base ADD COLUMN IF NOT EXISTS context_enhancement  BOOLEAN NOT NULL DEFAULT TRUE;

-- ---- KNW-11：kb_document 解析溯源 ----
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS parse_id            VARCHAR(64);
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS parse_plan          VARCHAR(64);
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS parse_plan_version  VARCHAR(32);
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS parse_status        VARCHAR(16);
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS quality_metrics_json TEXT;
ALTER TABLE kb_document ADD COLUMN IF NOT EXISTS parse_warnings       TEXT;

-- ---- KNW-03：kb_chunk 父子块结构 ----
ALTER TABLE kb_chunk ADD COLUMN IF NOT EXISTS chunk_type      VARCHAR(16) NOT NULL DEFAULT 'CHILD';
ALTER TABLE kb_chunk ADD COLUMN IF NOT EXISTS parent_chunk_id VARCHAR(64);
ALTER TABLE kb_chunk ADD COLUMN IF NOT EXISTS page_start      INT;
ALTER TABLE kb_chunk ADD COLUMN IF NOT EXISTS page_end        INT;
ALTER TABLE kb_chunk ADD COLUMN IF NOT EXISTS block_ids_json  TEXT;
ALTER TABLE kb_chunk ADD COLUMN IF NOT EXISTS context_prefix  TEXT;

CREATE INDEX IF NOT EXISTS idx_kb_chunk_parent ON kb_chunk (parent_chunk_id);

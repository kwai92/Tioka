-- V227__m3_knw12_knw04_knw14.sql
-- C-22 / KNW-12 两路独立召回 + KNW-04 Rerank 绑定 + KNW-14 知识使用模式。
-- 规格：M3-06 §3.3/§3.4、ADR-056 §2/§3、ADR-048 §1。
-- 号段纪律：顺序取号（V223 C-16、V224 C-17、V225 C-18、V226 C-21 已占用）。

-- KNW-04：知识库级 rerank 绑定与阈值。NULL = 未绑定：跳过 rerank 且「不设阈值」（ADR-056 §2 步 7）。
ALTER TABLE kb_base ADD COLUMN rerank_binding_id VARCHAR(64);
ALTER TABLE kb_base ADD COLUMN rerank_threshold  NUMERIC(5,4);

-- KNW-12：V118 已建 kb_document.acl_principals（M1 建列不启用），本批启用为文档级权限下推条件。
-- 空 / NULL / '[]' = 继承知识库权限（ADR-029 第 23 条）。检索 SQL 在 mapper/KbChunkMapper.xml。
CREATE INDEX IF NOT EXISTS idx_kb_doc_acl ON kb_document (kb_id) WHERE acl_principals IS NOT NULL;

-- KNW-12：全文一路为应用层中文分词 + strpos(lower(...), token) > 0（CjkTokenizer 产出 bigram token）。
-- strpos 是普通子串函数，token 中的 % / _ 不会被 LIKE 当作通配符（安全口径）。
-- pg_trgm 的 gin_trgm_ops 只加速 LIKE / ILIKE / ~，对 strpos 无效，故本批不建该无效索引；
-- 全文一路当前为顺序扫描，M4 上 tsvector / pg_jieba 时再补真正的全文索引（登记为偏离）。

-- KNW-14：自动注入预算落在 AgentSpec.knowledge 段，不新建表；
-- 检索轨迹（KNW-13 / M4）与本批的召回明细复用 kb_citation，不新增表。

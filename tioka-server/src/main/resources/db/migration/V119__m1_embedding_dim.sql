-- M1：统一向量维度（KNW-01、ADR-029 第 23 条）。${embeddingDim} 为 Flyway 占位符（spring.flyway.placeholders.embeddingDim，默认 1024）
-- 已有向量全部作废，文档置 REINDEX_PENDING，由 ReindexJob 经 ModelGateway 重嵌入
-- embedding_binding_id：M1-03「新建时选嵌入 binding」+ ADR-018；契约原稿漏列，随本工作项补齐
ALTER TABLE kb_base ADD COLUMN IF NOT EXISTS embedding_binding_id VARCHAR(64);

ALTER TABLE kb_chunk DROP COLUMN IF EXISTS embedding;
ALTER TABLE kb_chunk DROP COLUMN IF EXISTS embedding_vec;
ALTER TABLE kb_chunk ADD COLUMN embedding_vec vector(${embeddingDim});
UPDATE kb_document SET status = 'REINDEX_PENDING' WHERE deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_chunk_vec ON kb_chunk USING hnsw (embedding_vec vector_cosine_ops);

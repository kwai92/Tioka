-- M1：统一向量维度（KNW-01、ADR-029 第 23 条）。${embeddingDim} 为 Flyway 占位符（spring.flyway.placeholders.embeddingDim，默认 1024）
-- 已有向量全部作废，文档置 REINDEX_PENDING，由 ReindexJob 经 ModelGateway 重嵌入
ALTER TABLE kb_chunk DROP COLUMN IF EXISTS embedding;
ALTER TABLE kb_chunk DROP COLUMN IF EXISTS embedding_vec;
ALTER TABLE kb_chunk ADD COLUMN embedding_vec vector(${embeddingDim});
UPDATE kb_document SET status = 'REINDEX_PENDING' WHERE deleted = FALSE;
CREATE INDEX idx_chunk_vec ON kb_chunk USING hnsw (embedding_vec vector_cosine_ops);

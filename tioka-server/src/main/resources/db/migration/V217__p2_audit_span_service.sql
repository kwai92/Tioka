-- P2 / L6：审计 span_id / service 落列（traceId 全链贯通）
-- X7 单源口径：迁移仅落 tioka-server/src/main/resources/db/migration/（docs/contracts/db/ 已冻结为历史契约快照，不加副本）。
-- 保持轻量：不加索引；span_id / service 可空（无 traceparent 入口或 Span 无效时为 NULL）。
ALTER TABLE audit_event ADD COLUMN IF NOT EXISTS span_id VARCHAR(64);
ALTER TABLE audit_event ADD COLUMN IF NOT EXISTS service VARCHAR(64);

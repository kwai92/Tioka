-- 47-GL §1.6：物理表对齐 FlowPublication 名词（类名已迁；本迁移仅 RENAME，不做 V143 重建）
ALTER TABLE IF EXISTS flow_tool_pub RENAME TO flow_publication;

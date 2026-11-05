-- M1：Chat 只存索引（CHT-03）。历史消息已在 V104 回填为 runtime_item。
ALTER TABLE chat_conversation ADD COLUMN last_active_at TIMESTAMPTZ;
ALTER TABLE chat_conversation ADD COLUMN last_preview   VARCHAR(200);
UPDATE chat_conversation c SET last_active_at = COALESCE(
    (SELECT max(m.create_at) FROM chat_message m WHERE m.conversation_id = c.id), c.update_at);
CREATE INDEX idx_conv_owner_active ON chat_conversation (owner_id, last_active_at DESC);
-- 迁移前校验（V115 的 Java 回调 beforeEachMigrate 执行）：每个会话的 runtime_item 数 ≥ chat_message 数，否则失败并输出会话 ID
DROP TABLE chat_message;

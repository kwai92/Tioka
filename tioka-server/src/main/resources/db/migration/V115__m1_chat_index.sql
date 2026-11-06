-- CHT-02 前置：会话索引字段（last_active_at / last_preview）。
-- CHT-03 将补：迁移前校验 + DROP TABLE chat_message（见 docs/contracts/db/V115 注释）。
ALTER TABLE chat_conversation ADD COLUMN IF NOT EXISTS last_active_at TIMESTAMPTZ;
ALTER TABLE chat_conversation ADD COLUMN IF NOT EXISTS last_preview   VARCHAR(200);
UPDATE chat_conversation c SET last_active_at = COALESCE(
    (SELECT max(m.create_at) FROM chat_message m WHERE m.conversation_id = c.id), c.update_at)
WHERE last_active_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_conv_owner_active ON chat_conversation (owner_id, last_active_at DESC);

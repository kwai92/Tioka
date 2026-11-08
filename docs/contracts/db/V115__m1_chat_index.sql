-- M1：Chat 会话索引。
-- CHT-02：合入 ADD COLUMN + 回填 + 索引（server 已落地；不含 DROP）。
-- CHT-03：DROP chat_message 见 V126（Java 校验 + DROP；勿再改本已合并文件）。
ALTER TABLE chat_conversation ADD COLUMN IF NOT EXISTS last_active_at TIMESTAMPTZ;
ALTER TABLE chat_conversation ADD COLUMN IF NOT EXISTS last_preview   VARCHAR(200);
UPDATE chat_conversation c SET last_active_at = COALESCE(
    (SELECT max(m.create_at) FROM chat_message m WHERE m.conversation_id = c.id), c.update_at)
WHERE last_active_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_conv_owner_active ON chat_conversation (owner_id, last_active_at DESC);

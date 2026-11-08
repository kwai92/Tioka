-- Runtime 名词对齐：runtime_session → runtime_thread；session_id → thread_id（47-GL / ADR-016）
-- 历史脚本保留旧名；本迁移在运行时改写。

ALTER TABLE IF EXISTS runtime_session RENAME TO runtime_thread;

ALTER TABLE IF EXISTS runtime_turn RENAME COLUMN session_id TO thread_id;
ALTER TABLE IF EXISTS runtime_item RENAME COLUMN session_id TO thread_id;

-- 模型冻结 / 用量：语义同 Thread
ALTER TABLE IF EXISTS model_turn_freeze RENAME COLUMN session_id TO thread_id;
ALTER TABLE IF EXISTS model_usage RENAME COLUMN session_id TO thread_id;

ALTER TABLE IF EXISTS chat_conversation RENAME COLUMN runtime_session_id TO runtime_thread_id;

-- 索引名随列语义更新（PostgreSQL 不自动改索引名）
ALTER INDEX IF EXISTS idx_turn_queued RENAME TO idx_turn_queued_thread;
ALTER INDEX IF EXISTS idx_turn_waiting RENAME TO idx_turn_waiting_thread;

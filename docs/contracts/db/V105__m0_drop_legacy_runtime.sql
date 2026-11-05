-- M0：在 V104__BackfillRuntimeItems（Java 迁移，tioka-server/src/main/java/db/migration/）把旧 parts 回填为 runtime_item 之后执行
-- 施工设计：M0-03-Runtime内核.md（RT-02）；ADR-029 第 33 条
ALTER TABLE runtime_session DROP COLUMN parts_json;
ALTER TABLE runtime_session DROP COLUMN cancelled;          -- 取消信号改走 Redis rt:signal:{turnId}:cancel
ALTER TABLE runtime_session DROP COLUMN steer_text;         -- 插话改为 user item
DROP TABLE IF EXISTS runtime_idem;                          -- 幂等改由 runtime_turn (session_id, client_msg_id) 唯一键保证

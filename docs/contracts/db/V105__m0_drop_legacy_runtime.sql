-- RT-02：回填完成后删除 runtime_session 旧列与 runtime_idem（ADR-029 第 33 条；施工 M0-03）
ALTER TABLE runtime_session DROP COLUMN IF EXISTS parts_json;
ALTER TABLE runtime_session DROP COLUMN IF EXISTS cancelled;
ALTER TABLE runtime_session DROP COLUMN IF EXISTS steer_text;
DROP TABLE IF EXISTS runtime_idem;

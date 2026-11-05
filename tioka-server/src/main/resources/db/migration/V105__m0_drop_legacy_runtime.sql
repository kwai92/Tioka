-- M0：删除 runtime_session 旧列与 runtime_idem（ADR-029 第 33 条）
-- ENG-11：占位版本，保证 Flyway 版本链完整；真正 DROP 由 RT-02 在 V104 回填完成后写入本文件。
-- 施工设计：M0-03-Runtime内核.md（RT-02）
SELECT 1;

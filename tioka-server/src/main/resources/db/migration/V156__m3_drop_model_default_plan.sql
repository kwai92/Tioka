-- V156__m3_drop_model_default_plan.sql
-- MH-08 删除 model_default_plan（ADR-041 §9 / §1；破坏性迁移，需演练登记）
-- 前置：V151 已把各行迁移为 sys.* 系统别名（sys.chat / sys.flow / sys.embedding / sys.rerank / sys.decision）；
--       ModelBindingResolver 已改为按 purpose 解析 sys.* 别名。
-- 演练待办：合入前须在脱敏现网副本演练（见 docs/architecture/design-notes/M3-C1a-mh08-versioning.md）。

DROP TABLE IF EXISTS model_default_plan;

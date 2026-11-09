-- V222__m3_mh08_usage_version_columns.sql
-- MH-08 调用记录带版本（ADR-041 §2 / §6；无 tenant_id）
-- 按对象当前生效版本号记用量，统计可按版本切片。
-- 注：ADR-041 §9 把 model_usage 全量新列（供应商 ID、别名 ID、Prompt 版本、首 token 延迟、
--     缓存 token、错误类别、model_usage_daily）登记在 V153（MH-11）；本批 MH-08 仅需版本列，
--     拆出 V222 顺延号，V153 号位仍留给 MH-11 剩余列。

ALTER TABLE model_usage ADD COLUMN IF NOT EXISTS binding_version_no INT;
ALTER TABLE model_usage ADD COLUMN IF NOT EXISTS alias_version_no INT;
ALTER TABLE model_usage ADD COLUMN IF NOT EXISTS plan_version_no INT;

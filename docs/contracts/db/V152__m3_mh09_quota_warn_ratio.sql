-- V152__m3_mh09_quota_warn_ratio.sql
-- MH-09（ADR-041 第 3 节 / GOV-03）：配额预警比例列，缺省 80%
-- 供给侧作用对象（provider / alias）由应用层枚举放开：gov_quota.scope_type 为 VARCHAR(16) 且无 CHECK 约束，
--   'provider'(8) / 'alias'(5) 均在其容量内，无需 DDL 变更。
-- metric 允许集已在 V218 扩展（含 cost_month）；维度作用域见 QuotaMetrics.ALL_SCOPES。

ALTER TABLE gov_quota ADD COLUMN IF NOT EXISTS warn_ratio INT NOT NULL DEFAULT 80;

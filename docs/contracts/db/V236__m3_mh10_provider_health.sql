-- V236__m3_mh10_provider_health.sql
-- MH-10（ADR-041 第 5.1 节 / ADR-024 第「ProviderConnection」）：供应商健康状态列。
-- 主动探测与运行状态判定的最终结论回写本列（DISABLED / BUDGET_EXHAUSTED / COOLDOWN / DOWN / DEGRADED / UP）；
-- 明细（60 分钟 / 24 小时滚动窗口统计、最近一次错误样本）存 Redis，不落库。
-- 原登记号 V155 已被 GOV-13（cfg 告警规则）占用，本项按顺序取号落 V236。
ALTER TABLE model_provider ADD COLUMN IF NOT EXISTS health VARCHAR(16);

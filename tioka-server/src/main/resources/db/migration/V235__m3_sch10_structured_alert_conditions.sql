-- V235__m3_sch10_structured_alert_conditions.sql
-- SCH-10（ADR-066 §10.1）：结构化输出指标并入 ADR-041 §5.3 内置告警，新增两个指标条件。
--   §10.1 告警缺省：「某（Schema、模型）首次通过率 7 日均值下降超过 5 个百分点，或失败率超过 1%」。
--     · STRUCTURED_FIRST_PASS_DROP —— 首次通过率降幅（百分点），观测值由信号源给出窗口均值之差；
--     · STRUCTURED_FAILURE_RATE     —— 失败率（比例 0〜1）。
--   condition_type 由 VARCHAR(24) 放宽到 VARCHAR(32) 以容纳新条件值（两张表同步；varchar 加长在
--   PostgreSQL 下为仅元数据变更，不重写表）。
-- 号段纪律：M3 合同链之外收尾项顺延自 V234 起（顺序取号）。

ALTER TABLE ops_alert_rule  ALTER COLUMN condition_type TYPE VARCHAR(32);
ALTER TABLE ops_alert_event ALTER COLUMN condition_type TYPE VARCHAR(32);

ALTER TABLE ops_alert_rule DROP CONSTRAINT ops_alert_rule_condition_type_check;
ALTER TABLE ops_alert_rule ADD CONSTRAINT ops_alert_rule_condition_type_check
    CHECK (condition_type IN ('HEALTH_STATUS','ERROR_RATE','P95_LATENCY','FAILOVER_RATE',
                              'QUOTA_WARN','QUOTA_EXHAUSTED','CONFIG_PUBLISHED','CONFIG_ROLLED_BACK',
                              'STRUCTURED_FIRST_PASS_DROP','STRUCTURED_FAILURE_RATE'));

COMMENT ON COLUMN ops_alert_rule.condition_type IS
    'GOV-13 / ADR-041 §5.3 + SCH-10 / ADR-066 §10.1：告警条件';
COMMENT ON COLUMN ops_alert_event.condition_type IS
    'GOV-13 / ADR-041 §5.3：事件条件快照（同规则 condition_type）';

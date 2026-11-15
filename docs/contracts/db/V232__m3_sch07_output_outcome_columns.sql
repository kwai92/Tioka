-- SCH-07（ADR-066 §1.1、§1.2、§10.1）：结构化输出结局与原因码列。
--   runtime_item 写入 data item 时同步落 schema_ref / outcome / reason_code 三列，
--   供 §10.1「按（schemaRef、模型、生成方式）统计」直接聚合，无需解析 content_json。
--   三列仅 data item 有值，其余 item 为 NULL；content_json 仍为唯一事实来源（列是其投影）。
-- 号段纪律：顺序取号（M3 收尾项自 V232 起；M3-07 原预登记号 V178 作废——人工补录任务表归 SCH-09 另起号）。

ALTER TABLE runtime_item ADD COLUMN IF NOT EXISTS schema_ref  VARCHAR(160);
ALTER TABLE runtime_item ADD COLUMN IF NOT EXISTS outcome     VARCHAR(24);
ALTER TABLE runtime_item ADD COLUMN IF NOT EXISTS reason_code VARCHAR(32);

CREATE INDEX IF NOT EXISTS idx_item_output_outcome
    ON runtime_item (schema_ref, outcome) WHERE type = 'data';

COMMENT ON COLUMN runtime_item.schema_ref IS 'SCH-07 / ADR-066 §1.1：data item 的权威 Schema 引用（精确到版本）；非 data item 为 NULL';
COMMENT ON COLUMN runtime_item.outcome IS 'SCH-07 / ADR-066 §1.1：四种结局（VALID / VALID_WITH_UNKNOWN / HUMAN_PENDING / FAILED）；非 data item 为 NULL';
COMMENT ON COLUMN runtime_item.reason_code IS 'SCH-07 / ADR-066 §1.2：失败原因码（TRUNCATED / REFUSED / SCHEMA / RULE …）；成功为 NULL';

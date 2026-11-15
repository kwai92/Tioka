-- V234__m3_sch10_output_dimensions.sql
-- SCH-10（ADR-066 §10.1）：结构化输出指标的「模型 / 生成方式 / 修复轮数」维度列。
--   §10.1 要求「按（schemaRef、模型、生成方式）统计」首次通过率、最终通过率、兜底率、
--   人工补录率、失败率、平均修复轮数等。schema_ref / outcome / reason_code 三列已由 V232 落地；
--   本迁移补齐其余聚合维度，使指标可直接 SQL 分组，无需解析 content_json。
--   三列仍为 content_json 的投影（仅 type='data' 有值），content_json 保持唯一事实来源。
-- 号段纪律：M3 合同链之外收尾项顺延自 V234 起（顺序取号）。

ALTER TABLE runtime_item ADD COLUMN IF NOT EXISTS model_ref     VARCHAR(160);
ALTER TABLE runtime_item ADD COLUMN IF NOT EXISTS output_mode   VARCHAR(24);
ALTER TABLE runtime_item ADD COLUMN IF NOT EXISTS repair_rounds INTEGER;

CREATE INDEX IF NOT EXISTS idx_item_output_dimensions
    ON runtime_item (schema_ref, model_ref, output_mode) WHERE type = 'data';

COMMENT ON COLUMN runtime_item.model_ref IS 'SCH-10 / ADR-066 §10.1：data item 产出模型 ref（producedBy.model）；非 data item 为 NULL';
COMMENT ON COLUMN runtime_item.output_mode IS 'SCH-10 / ADR-066 §10.1：结构化输出提交方式（OutputMode：FINAL / TOOL，ADR-037）；非 data item 为 NULL';
COMMENT ON COLUMN runtime_item.repair_rounds IS 'SCH-10 / ADR-066 §10.1：修复轮数（producedBy.repairRounds）；首次通过率与平均修复轮数据此聚合';

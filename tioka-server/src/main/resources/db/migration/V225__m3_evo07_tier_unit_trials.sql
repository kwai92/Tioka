-- V225__m3_evo07_tier_unit_trials.sql
-- C-18（EVO-07 数据集分层与材料单元键、密封集隔离、多次运行、聚类区间与配对差异）。
-- 设计出处 ADR-068（评测方法规范）：第 1 节五层 tier（SMOKE/DEV/SEALED/REGRESSION/SPECIAL）
-- 与材料单元键（L63：一个材料单元派生的所有用例必须在同一层）、第 2 节用例卡
-- （unit_key / label_status / must_pass）、第 3 节评分器版本（version / config_hash）、
-- 第 5 节多次运行（eval_run.trials，默认 1 可配 1~10）、L202 字段去向表。
-- 号段口径：原 V186 为 2026-11-07 预登记号（EVO-07/08a/13a 先合者带入），
-- 号段纪律已演进为顺序取号（C-16 用 V223、C-17 用 V224 先例），本批顺序取 V225；
-- EVO-08a（反馈原因码）与 EVO-13a（选型运行）另行取号，V186 号作废回写台账。
-- eval_case / eval_result 无 workspace_id（随数据集 / run 归属，V176 已登记 workspace-scope-exempt）。

-- 数据集分层（ADR-068 第 1 节）：SMOKE / DEV / SEALED / REGRESSION / SPECIAL；
-- 存量行缺省 DEV 保持既有行为。
ALTER TABLE eval_dataset ADD COLUMN IF NOT EXISTS tier VARCHAR(16) NOT NULL DEFAULT 'DEV';
ALTER TABLE eval_dataset ADD COLUMN IF NOT EXISTS unit_key_path VARCHAR(255);
COMMENT ON COLUMN eval_dataset.tier IS '评测集分层：SMOKE/DEV/SEALED/REGRESSION/SPECIAL（ADR-068 第 1 节）';
COMMENT ON COLUMN eval_dataset.unit_key_path IS '材料单元键路径（如 contract_key）；NULL=未声明（ADR-068 L63）';

-- 用例卡增量字段（ADR-068 第 2 节）：材料单元键、标注状态（VALID/STALE/DISPUTED，
-- != VALID 不参与门禁统计）、逐条必过（回归集缺省为是）。
ALTER TABLE eval_case ADD COLUMN IF NOT EXISTS unit_key VARCHAR(255);
ALTER TABLE eval_case ADD COLUMN IF NOT EXISTS label_status VARCHAR(16) NOT NULL DEFAULT 'VALID';
ALTER TABLE eval_case ADD COLUMN IF NOT EXISTS must_pass BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN eval_case.unit_key IS '材料单元键（合同链根 / 文档 / 会话）；NULL=未声明（ADR-068 第 1.2 节）';
COMMENT ON COLUMN eval_case.label_status IS '标注状态：VALID/STALE/DISPUTED；非 VALID 不参与门禁统计（ADR-068 第 2 节）';
COMMENT ON COLUMN eval_case.must_pass IS '逐条必须通过（回归集缺省为是；ADR-068 第 2 节 / L153-165）';

-- 多次运行（ADR-068 第 5 节）：准入/选型/双跑对照默认 3，可配 1~10；冒烟与日常开发 1。
ALTER TABLE eval_run ADD COLUMN IF NOT EXISTS trials INT NOT NULL DEFAULT 1;
COMMENT ON COLUMN eval_run.trials IS '每条用例运行次数（1~10，缺省 1；ADR-068 第 5 节）';

-- 评分器版本（ADR-068 第 3 节 L101）：评分器改版视为评测定义变化，新旧结果不直接对比。
-- 本批仅落列，登记/查询消费面后续批次（EVO-09 裁判校准）接入。
ALTER TABLE eval_evaluator ADD COLUMN IF NOT EXISTS version VARCHAR(32);
ALTER TABLE eval_evaluator ADD COLUMN IF NOT EXISTS config_hash VARCHAR(64);
COMMENT ON COLUMN eval_evaluator.version IS '评分器版本（ADR-068 第 3 节；改版视为评测定义变化）';
COMMENT ON COLUMN eval_evaluator.config_hash IS '评分器配置指纹（config_json 变化即新版本）';

-- 每 (case × evaluator × trial) 一条结果（ADR-068 第 5 节多次运行）；存量行 trial=1。
ALTER TABLE eval_result ADD COLUMN IF NOT EXISTS trial INT NOT NULL DEFAULT 1;
COMMENT ON COLUMN eval_result.trial IS '运行序号（1..trials，ADR-068 第 5 节）';

-- 索引：按 run 取逐条结果与 trial 口径（getRun / 指标统计高频路径）；
-- unit_key 支撑 UnitSplit 跨数据集同单元跨层校验（ADR-068 L63 一个材料单元只属一层）。
CREATE INDEX IF NOT EXISTS idx_eval_result_run_case ON eval_result (run_id, case_id, trial);
CREATE INDEX IF NOT EXISTS idx_eval_case_unit ON eval_case (unit_key);

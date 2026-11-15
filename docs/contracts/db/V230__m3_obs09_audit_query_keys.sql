-- OBS-09（ADR-072 §6 / ADR-073 §3）：审计的审计 —— 查询与导出留痕、权限点细分、
--   按关联键（trace_id / request_id / subject / turn_id / run_id）查询、分页改为 (ts, id) 游标。
-- 号段纪律：顺序取号（V228 OBS-06、V229 OBS-08 已占用），本批取 V230；M3-07 原预登记号 V187 作废。
-- 本迁移只补 OBS-09 查询条件所需的运行键列（turn_id / run_id）与 (request_id) 索引；
--   其余 ADR-073 §3 列（origin / user_agent / approval_id / agent_version_id / policy_ids / run_as）
--   属 OBS-03 关联传播范围，不在本批。
-- 已存在：request_id 列（V127）、subject 列与 idx_audit_subject_ts（V133）。

ALTER TABLE audit_event ADD COLUMN IF NOT EXISTS turn_id VARCHAR(64);
ALTER TABLE audit_event ADD COLUMN IF NOT EXISTS run_id  VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_audit_request_id ON audit_event (request_id);
CREATE INDEX IF NOT EXISTS idx_audit_turn_id ON audit_event (turn_id) WHERE turn_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_audit_run_id ON audit_event (run_id) WHERE run_id IS NOT NULL;

COMMENT ON COLUMN audit_event.turn_id IS 'OBS-09 / ADR-073 §1：所属 Turn 运行键（无则 NULL）';
COMMENT ON COLUMN audit_event.run_id IS 'OBS-09 / ADR-073 §1：所属 Run 运行键（编排 / Flow / 评测等；无则 NULL）';

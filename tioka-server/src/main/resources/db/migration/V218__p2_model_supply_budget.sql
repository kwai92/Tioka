-- P2 / L5 供给侧预算与 provider 分摊（GAP-L5-1 / GAP-L5-2，见 P1-L5-gap-ledger.md）
-- 预算口径：binding 维度（供给侧），月窗；金额单位微美分，与 model_usage.cost_micros 一致

-- GAP-L5-2：用量明细携带 provider_id，支撑成本按 provider 分摊；历史行 NULL（不改历史）
ALTER TABLE model_usage ADD COLUMN provider_id VARCHAR(64);
CREATE INDEX idx_usage_provider_ts ON model_usage (provider_id, create_at);

-- GAP-L5-1：binding 供给侧月度预算（NULL = 不限）
ALTER TABLE model_binding ADD COLUMN budget_token_month BIGINT;   -- 本月 token 上限
ALTER TABLE model_binding ADD COLUMN budget_cost_month BIGINT;    -- 本月成本上限（微美分）

-- 供给侧金额配额预留 metric（cost_month）；scope_type 沿用 workspace/agent/user/tool
ALTER TABLE gov_quota DROP CONSTRAINT gov_quota_metric_check;
ALTER TABLE gov_quota ADD CONSTRAINT gov_quota_metric_check
    CHECK (metric IN ('tokens_month','concurrent_turns','tool_rpm','cost_month'));

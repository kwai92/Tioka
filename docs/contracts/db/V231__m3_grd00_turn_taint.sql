-- GRD-00（ADR-065 §2、§3）：内容信任分级与 Turn 污点。
--   runtime_turn 记录本 Turn 是否读入过 EXTERNAL 段（taint）及其来源清单（taint_sources）。
-- 号段纪律：顺序取号（V230 OBS-09 已占用），本批取 V231；M3-07 原预登记号 V182 作废。

ALTER TABLE runtime_turn ADD COLUMN IF NOT EXISTS taint VARCHAR(16) NOT NULL DEFAULT 'CLEAN'
    CHECK (taint IN ('CLEAN','TAINTED'));
ALTER TABLE runtime_turn ADD COLUMN IF NOT EXISTS taint_sources TEXT;

COMMENT ON COLUMN runtime_turn.taint IS 'GRD-00 / ADR-065 §3：本 Turn 污点（CLEAN / TAINTED）';
COMMENT ON COLUMN runtime_turn.taint_sources IS 'GRD-00 / ADR-065 §3：污点来源清单（JSON 数组，元素形如 sourceType#sourceRef）';

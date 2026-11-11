-- C-17 (G3-1) / DAT-01：data_dataset 补 BaseDO 审计列。
-- DatasetDO 继承 WorkspaceAwareDO → BaseDO（create_at/update_at/create_by/update_by/deleted），
-- V163 建表遗漏；DatasetStoreDb（MyBatis-Plus）真实读路径 SELECT 全字段在 PG 上报
-- 「column "create_at" does not exist」。补齐审计五列（M0-01 ENG-11 口径）。
ALTER TABLE data_dataset ADD COLUMN IF NOT EXISTS create_at TIMESTAMPTZ;
ALTER TABLE data_dataset ADD COLUMN IF NOT EXISTS update_at TIMESTAMPTZ;
ALTER TABLE data_dataset ADD COLUMN IF NOT EXISTS create_by VARCHAR(32);
ALTER TABLE data_dataset ADD COLUMN IF NOT EXISTS update_by VARCHAR(32);
ALTER TABLE data_dataset ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN data_dataset.create_at IS 'BaseDO 审计列（V224 补齐，M0-01 ENG-11）';
COMMENT ON COLUMN data_dataset.update_at IS 'BaseDO 审计列（V224 补齐）';

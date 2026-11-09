-- OBS-05（ADR-072 / M2 残余收口批次 B-2）四子项的数据库部分：
--   1) 委派链列提前建（ADR-039 §审计：root_principal / on_behalf_of / chain_json / grant_id / data_label，
--      列名沿用 ADR-039；MAS-01 到 M3 只负责填值）；
--   2) 审计转发进度表（SIEM 外送水位，双实例配合 advisory lock 保证 SIEM 不收重复记录）；
--      动作目录为契约文件 docs/contracts/audit/actions.yaml（无库表）；定时任务互斥清单为 ops 文档。
-- 哈希链列 prev_hash/hash（V102 已预留）不变。

ALTER TABLE audit_event ADD COLUMN IF NOT EXISTS root_principal VARCHAR(64);
ALTER TABLE audit_event ADD COLUMN IF NOT EXISTS on_behalf_of  VARCHAR(64);
ALTER TABLE audit_event ADD COLUMN IF NOT EXISTS chain_json    TEXT;
ALTER TABLE audit_event ADD COLUMN IF NOT EXISTS grant_id      VARCHAR(64);
ALTER TABLE audit_event ADD COLUMN IF NOT EXISTS data_label    VARCHAR(32);

CREATE TABLE IF NOT EXISTS audit_forward_progress (
    channel           VARCHAR(32)  PRIMARY KEY,     -- 外送通道：默认 'siem'
    last_forwarded_id VARCHAR(32),                  -- 本通道已外送的最后一条 audit_event ID（Monotonic ULID 水位）
    batches           BIGINT       NOT NULL DEFAULT 0,
    last_forwarded_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE audit_forward_progress IS 'OBS-05 审计转发水位表：AuditForwarder 外送进度（配合 pg advisory lock 保证双实例不重复外送）';

-- GOV-09（ADR-025 §审计防篡改与外送）：审计哈希链封印。
-- prev_hash / hash 两列 V102 建表时已预留，此处只补封印链所需的结构：
--   1) 部分索引：封印任务按 ID 序取「未封印」记录，hash IS NULL 的行集很小，
--      部分索引比全表索引更省且随封印推进自动收缩；
--   2) audit_seal 封印锚表：每租户一条链，链末哈希落锚（可外送 SIEM 作外部锚点）。
-- 口径（ADR-025）：写审计时不算哈希（避免写入串行化）；封印任务异步按 ID 顺序
-- 计算 hash = sha256(prev_hash + 规范化后的记录)，workspace_id 为空的系统级记录
-- 归入 '_system' 链。

CREATE INDEX IF NOT EXISTS idx_audit_unsealed
    ON audit_event (id) WHERE hash IS NULL;

CREATE TABLE IF NOT EXISTS audit_seal (
    workspace_key VARCHAR(65)  PRIMARY KEY,          -- COALESCE(workspace_id,'_system')，65 = 64 + '_system' 上限余量
    last_id       VARCHAR(32)  NOT NULL,             -- 链内最后一条已封印记录 ID（Monotonic ULID）
    last_hash     VARCHAR(64)  NOT NULL,             -- 链末哈希（外部锚点）
    events_sealed BIGINT       NOT NULL DEFAULT 0,   -- 累计封印条数（校验口径参考）
    sealed_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE audit_seal IS 'GOV-09 审计哈希链封印锚：每租户一条链，链末哈希可外送 SIEM 作外部锚点';

-- OBS-04a（ADR-074 外部交互台账）：一张台账记录「跨边界发生了什么」。
-- 与审计的分工：台账记跨边界往来（对方回执号、内容哈希、时延），审计记「谁决定做这件事」。
-- M2 覆盖范围（ADR-074 第 2 节）：im / webhook / http（EgressClient 出网）；
--   im 入站明细随 CON-02 入站回调落地，webhook 入口随 Flow Webhook 落地（接线点当前不存在，本表先行）。
-- 保留类别（ADR-074 §1）：带 subject 的行 retention_class=BIZ，其余 SEC（纳入 ADR-072 哈希链与保留管理）；
--   prev_hash / hash 预留给台账哈希链封印（GOV-09 AuditChainService 扩展，另行接入，本批不启用）。

CREATE TABLE IF NOT EXISTS ext_interaction (
    id              VARCHAR(32)  PRIMARY KEY,
    ts              TIMESTAMPTZ  NOT NULL,
    direction       VARCHAR(4)   NOT NULL CHECK (direction IN ('IN', 'OUT')),
    kind            VARCHAR(16)  NOT NULL,                          -- im / webhook / http / notify / biz / ext_kb / mcp / model / parse / a2a / datasource
    counterparty    VARCHAR(128),                                   -- 连接器或渠道实例 ID（出网兜底为主机名）
    endpoint        VARCHAR(255),                                   -- 主机 + 路径模板，不含查询串
    operation       VARCHAR(64),                                    -- 如 im.message.send / GET
    auth_mode       VARCHAR(16)  NOT NULL DEFAULT 'NONE',           -- DELEGATED / SERVICE / SIGNED / NONE
    credential_id   VARCHAR(64),                                    -- 凭证 ID，不存凭证值
    actor_id        VARCHAR(64),
    run_as          VARCHAR(64),
    trace_id        VARCHAR(64),
    request_id      VARCHAR(64),
    subject         VARCHAR(255),
    turn_id         VARCHAR(64),
    run_id          VARCHAR(64),
    change_set_id   VARCHAR(64),
    tool_call_id    VARCHAR(64),
    idempotency_key VARCHAR(128),                                   -- 我方幂等键
    external_ref    VARCHAR(255),                                   -- 对方回执号：飞书 message_id / 业务单号等
    dedupe_key      VARCHAR(128),                                   -- 入站去重键
    request_digest  VARCHAR(64),                                    -- SHA-256
    response_digest VARCHAR(64),                                    -- SHA-256
    request_size    BIGINT,
    response_size   BIGINT,
    payload_ref     VARCHAR(255),                                   -- 可选对象存储引用（按连接器配置保存载荷时）
    data_label      VARCHAR(32),
    status          VARCHAR(16)  NOT NULL CHECK (status IN ('OK','ERROR','TIMEOUT','REJECTED','DUPLICATE')),
    http_status     INTEGER,
    error_code      VARCHAR(64),
    attempt         INTEGER     NOT NULL DEFAULT 1,                 -- 每次重试一条，attempt 递增
    latency_ms      BIGINT,
    retention_class VARCHAR(8)   NOT NULL DEFAULT 'SEC' CHECK (retention_class IN ('BIZ', 'SEC')),
    attrs_json      TEXT,                                            -- 其余附加关联键（超出固定列的）
    prev_hash       VARCHAR(64),
    hash            VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_ext_int_ts           ON ext_interaction (ts);
CREATE INDEX IF NOT EXISTS idx_ext_int_counterparty ON ext_interaction (counterparty, ts);
CREATE INDEX IF NOT EXISTS idx_ext_int_external_ref ON ext_interaction (external_ref) WHERE external_ref IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ext_int_request_id   ON ext_interaction (request_id) WHERE request_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ext_int_subject      ON ext_interaction (subject, ts) WHERE subject IS NOT NULL;

COMMENT ON TABLE ext_interaction IS 'OBS-04a 外部交互台账（ADR-074）：入站与出站统一记录，纠纷时以 external_ref + 内容哈希反查全过程';

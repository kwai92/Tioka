-- OBS-12（M4-06 §7 / ADR-075 §5）：证据包登记表。
--   每次导出登记一条：范围、导出人、导出行数、包哈希与平台密钥签名，供事后审计与离线独立校验回溯。
-- 号段纪律：M4 规划号 V249 顺延 +12 落 V261（V232..V260 为 M4 其余工作项预留，见 M4-00 第 9 节）。

CREATE TABLE IF NOT EXISTS evidence_pack (
    id VARCHAR(64) NOT NULL,
    scope_type VARCHAR(16) NOT NULL CHECK (scope_type IN ('SUBJECT', 'REQUEST')),
    scope_ref VARCHAR(256) NOT NULL,
    workspace_id VARCHAR(64),
    exported_by VARCHAR(128) NOT NULL,
    exported_at TIMESTAMP NOT NULL,
    clearance VARCHAR(16) NOT NULL,
    include_confidential BOOLEAN NOT NULL DEFAULT FALSE,
    omitted_sections TEXT,
    file_count INT NOT NULL DEFAULT 0,
    row_count INT NOT NULL DEFAULT 0,
    package_hash VARCHAR(128) NOT NULL,
    signature_alg VARCHAR(32) NOT NULL DEFAULT 'SHA256withRSA',
    key_id VARCHAR(128),
    signature TEXT NOT NULL,
    create_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by VARCHAR(64) NOT NULL DEFAULT 'system',
    update_by VARCHAR(64) NOT NULL DEFAULT 'system',
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
        PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_evidence_pack_scope ON evidence_pack (scope_type, scope_ref);
CREATE INDEX IF NOT EXISTS idx_evidence_pack_workspace ON evidence_pack (workspace_id, exported_at DESC);

COMMENT ON TABLE evidence_pack IS 'OBS-12 / ADR-075 §5：证据包登记（导出留痕，含包哈希与平台密钥签名）';
COMMENT ON COLUMN evidence_pack.scope_type IS '导出范围：SUBJECT（主体）或 REQUEST（请求）';
COMMENT ON COLUMN evidence_pack.scope_ref IS '范围引用：REQUEST 为 request_id；SUBJECT 形如 subjectType:subjectId';
COMMENT ON COLUMN evidence_pack.workspace_id IS '导出所限空间；平台级导出可为空（不限空间）';
COMMENT ON COLUMN evidence_pack.clearance IS '导出人数据标签上限：SECRET 含 CONFIDENTIAL 原文，INTERNAL 省略';
COMMENT ON COLUMN evidence_pack.omitted_sections IS '因权限被省略的段清单（JSON 数组），无省略为 NULL';
COMMENT ON COLUMN evidence_pack.package_hash IS '包整体哈希（各文件 sha256 规范串的 sha256）';
COMMENT ON COLUMN evidence_pack.signature IS '平台签名密钥对包整体哈希的签名（SHA256withRSA，十六进制）';
COMMENT ON COLUMN evidence_pack.key_id IS '签名密钥标识（平台公钥 DER 的 sha256 前 16 位十六进制）';

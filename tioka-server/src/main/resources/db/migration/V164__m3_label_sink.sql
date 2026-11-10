-- V164__m3_label_sink.sql
-- DAT-02（ADR-040 数据模型 / M3-02）：去向许可 gov_label_sink + 五表 data_label。
-- 表名漂移更正：M3-02/ADR-040 写 runtime_session，实际 V128 已改名 runtime_thread，
-- 本迁移一律写 runtime_thread（item 的 session_id 亦已改 thread_id）。

CREATE TABLE gov_label_sink (
    workspace_id VARCHAR(64),
    sink_kind    VARCHAR(16),
    sink_ref     VARCHAR(128),                          -- MODEL_PLAN / IM / MAIL / SMS / MCP / '*'
    max_label    VARCHAR(16) NOT NULL,
    on_exceed    VARCHAR(16) NOT NULL,                  -- REDACT_THEN_DENY / REDACT_THEN_APPROVE / DENY
    PRIMARY KEY (workspace_id, sink_kind, sink_ref)
);

COMMENT ON TABLE gov_label_sink IS 'DAT-02 去向许可：空间管理员按去向配置最高标签与超限处理（ADR-040）';

ALTER TABLE kb_base       ADD COLUMN data_label VARCHAR(16) NOT NULL DEFAULT 'INTERNAL';
ALTER TABLE kb_document   ADD COLUMN data_label VARCHAR(16);
ALTER TABLE mcp_server    ADD COLUMN data_label VARCHAR(16) NOT NULL DEFAULT 'INTERNAL';
ALTER TABLE runtime_item  ADD COLUMN data_label VARCHAR(16);
ALTER TABLE runtime_thread ADD COLUMN data_label VARCHAR(16) NOT NULL DEFAULT 'PUBLIC';

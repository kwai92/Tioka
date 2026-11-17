-- OBS-08（ADR-075 §2 统一时间线投影 `timeline_entry`）：一份合同 / 一次请求从头到尾的条目视图。
-- 投影不是事实源：由投影消费者从各来源领域事件（经 outbox）异步生成；可按时间段重建；
--   条目本身不进 ADR-072 哈希链（校验时回到来源记录）。
-- 号段纪律：顺序取号（V223 C-16、V224 C-17、V225 C-18、V226 C-21、V227 C-22、V228 OBS-06 已占用），
--   本批取 V229；原 2026-11-07 预登记号 V185 作废（M4 起号随之顺延，见 M3-00 第 9 节）。
-- 主体可多值，另见关联表 timeline_entry_subject；重建游标见 timeline_projection_cursor。
-- 关联键 request_id / parent_request_id / trace_id / workspace_id 遵循 ADR-073。

CREATE TABLE IF NOT EXISTS timeline_entry (
    id                VARCHAR(32)  PRIMARY KEY,
    occurred_at       TIMESTAMPTZ  NOT NULL,
    seq               INT          NOT NULL DEFAULT 0,     -- 来源内序号，同一毫秒内排序提示
    request_id        VARCHAR(64),
    parent_request_id VARCHAR(64),
    category          VARCHAR(16)  NOT NULL CHECK (category IN
                          ('INBOUND','RUN','MODEL','TOOL','DECISION','APPROVAL','CHANGE',
                           'OUTBOUND','NOTIFY','FEEDBACK','CONFIG','SECURITY')),
    title             VARCHAR(512),
    summary           TEXT,
    actor             VARCHAR(255),
    outcome           VARCHAR(16)  CHECK (outcome IN ('OK','DENIED','ERROR','WAITING')),
    source_type       VARCHAR(32),                         -- audit_event / ext_interaction / runtime_item / ...
    source_id         VARCHAR(64),
    trace_id          VARCHAR(64),
    workspace_id      VARCHAR(64),
    data_label        VARCHAR(16),                         -- PUBLIC / INTERNAL / CONFIDENTIAL / SECRET
    source_event_id   VARCHAR(64),                         -- 来源事件 eventId（投影幂等键）
    create_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by         VARCHAR(64)  NOT NULL DEFAULT 'system'
);

-- 投影幂等：同一来源事件只落一条（ADR-075 §2）。非部分唯一索引：PostgreSQL 唯一索引允许多个 NULL，
-- 便于 `ON CONFLICT (source_event_id)` 推断。
CREATE UNIQUE INDEX IF NOT EXISTS uq_timeline_entry_source_event
    ON timeline_entry (source_event_id);
CREATE INDEX IF NOT EXISTS idx_timeline_entry_request
    ON timeline_entry (request_id, occurred_at, id) WHERE request_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_timeline_entry_category
    ON timeline_entry (category, occurred_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_timeline_entry_source
    ON timeline_entry (source_type, source_id) WHERE source_type IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_timeline_entry_trace
    ON timeline_entry (trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_timeline_entry_workspace
    ON timeline_entry (workspace_id, occurred_at DESC);

-- 主体多值关联（ADR-075 §2「subject 可多值，另有关联表」）：一条条目可关联多个主体
--   （如一份合同 + 一个知识文档 + 一个 Agent）；主体时间线按 (subject_type, subject_id) 取条目。
CREATE TABLE IF NOT EXISTS timeline_entry_subject (
    entry_id     VARCHAR(32)  NOT NULL,
    subject_type VARCHAR(32)  NOT NULL,
    subject_id   VARCHAR(255) NOT NULL,
    workspace_id VARCHAR(64),
    create_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by    VARCHAR(64)  NOT NULL DEFAULT 'system'
);
CREATE INDEX IF NOT EXISTS idx_timeline_subject_lookup
    ON timeline_entry_subject (subject_type, subject_id, create_at DESC);
CREATE INDEX IF NOT EXISTS idx_timeline_subject_entry
    ON timeline_entry_subject (entry_id);

-- 重建游标（ADR-075 §2「可按时间段重建」）：按来源类型记录上次投影进度。
-- 无 workspace_id 列（投影进度是平台级技术状态），登记 tioka.persistence.workspace-scope-exempt-tables。
CREATE TABLE IF NOT EXISTS timeline_projection_cursor (
    source_type   VARCHAR(32)  PRIMARY KEY,
    cursor_ts     TIMESTAMPTZ,
    cursor_id     VARCHAR(64),
    last_event_id VARCHAR(64),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

COMMENT ON TABLE timeline_entry IS 'OBS-08 统一时间线投影（ADR-075 §2）：投影不是事实源，可由消费者从领域事件异步生成、可按时间段重建、不进哈希链';
COMMENT ON TABLE timeline_entry_subject IS 'OBS-08 时间线条目主体多值关联（ADR-075 §2）';
COMMENT ON TABLE timeline_projection_cursor IS 'OBS-08 时间线重建游标（ADR-075 §2）';

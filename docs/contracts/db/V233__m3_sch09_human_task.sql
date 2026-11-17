-- SCH-09（ADR-066 §7.4）：结构化输出人工补录任务表。
--   自动链路（含修复与降级阶梯）仍无法产出合法对象、且降级到 HUMAN 步骤时，生成一条
--   data.complete 任务：审批台按 Schema 渲染表单，预填 lastCandidate 中通过校验的字段，
--   出错字段高亮并附错误说明与证据定位；人提交的数据同样经流水线第 3〜8 步校验，通过后写
--   新的 data item（outcome=VALID，producedBy.human），原 Turn 从 WAITING 续跑；
--   超时（缺省 3 个工作日，工作日历由 Foundation BAS-18 提供）结局 FAILED(reason=HUMAN_TIMEOUT)。
--   号段纪律：顺序取号，SCH-09 自 V233 起（M3-07 P3-C06 预登记 V233+）。
--   本表带 workspace_id，ScopeLine deny-by-default 下按空间过滤（不进 exempt 名单）。

CREATE TABLE runtime_human_task (
    id               VARCHAR(32)  PRIMARY KEY,
    workspace_id     VARCHAR(64),
    subject_type     VARCHAR(16)  NOT NULL CHECK (subject_type IN ('turn','flow_node_run','orch_run')),
    subject_id       VARCHAR(64)  NOT NULL,
    thread_id        VARCHAR(64)  NOT NULL,
    turn_id          VARCHAR(32)  NOT NULL,
    item_id          VARCHAR(32),
    kind             VARCHAR(32)  NOT NULL DEFAULT 'data.complete',
    schema_ref       VARCHAR(160) NOT NULL,
    candidate_json   TEXT,
    pending_pointers TEXT,
    assignee_json    TEXT,
    status           VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING','SUBMITTED','EXPIRED','CANCELLED')),
    decided_by       VARCHAR(64),
    decided_at       TIMESTAMPTZ,
    answer_json      TEXT,
    expires_at       TIMESTAMPTZ,
    create_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by        VARCHAR(64)  NOT NULL DEFAULT 'system',
    update_by        VARCHAR(64)  NOT NULL DEFAULT 'system'
);
CREATE INDEX idx_human_task_subject ON runtime_human_task (subject_type, subject_id);
CREATE INDEX idx_human_task_pending ON runtime_human_task (workspace_id, status, create_at) WHERE status = 'PENDING';
CREATE INDEX idx_human_task_expiry  ON runtime_human_task (expires_at) WHERE status = 'PENDING';

COMMENT ON TABLE  runtime_human_task IS 'SCH-09 / ADR-066 §7.4：结构化输出人工补录任务（kind=data.complete）';
COMMENT ON COLUMN runtime_human_task.schema_ref IS '权威 Schema 引用（精确到版本）；审批台据此渲染表单';
COMMENT ON COLUMN runtime_human_task.candidate_json IS '预填候选（最后一次候选输出 lastCandidate），不可消费';
COMMENT ON COLUMN runtime_human_task.pending_pointers IS '待补全字段的 JSON Pointer 数组（出错字段）';
COMMENT ON COLUMN runtime_human_task.answer_json IS '人提交的数据，经第 3〜8 步校验后写 data item';
COMMENT ON COLUMN runtime_human_task.expires_at IS '补录超时截止时刻；到期结局 HUMAN_TIMEOUT';

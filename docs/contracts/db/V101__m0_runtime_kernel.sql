-- M0 契约迁移 2/3：Runtime 内核 thread / turn / item（ADR-016、ADR-027；定案 ADR-029 第 1、4、9、10、13、16 条）
-- runtime_session 即 thread。仓库无生产数据，parts_json 直接删除。

-- parts_json、cancelled、steer_text 不在此删除：先由 V104 回填为 runtime_item，再在 V105 删除（ADR-029 第 33 条）
ALTER TABLE runtime_session ADD COLUMN last_item_seq  BIGINT      NOT NULL DEFAULT 0;
ALTER TABLE runtime_session ADD COLUMN origin         VARCHAR(16) NOT NULL DEFAULT 'chat';  -- chat / flow / orch / im / job
ALTER TABLE runtime_session ADD COLUMN origin_ref     VARCHAR(64);
ALTER TABLE runtime_session ADD COLUMN metadata_json  TEXT;
ALTER TABLE runtime_session ADD COLUMN parent_turn_id VARCHAR(64);                          -- 子 Agent（ADR-027）
ALTER TABLE runtime_session ADD COLUMN depth          INT         NOT NULL DEFAULT 0;

CREATE TABLE runtime_turn (
    id                VARCHAR(32)  PRIMARY KEY,
    session_id        VARCHAR(64)  NOT NULL,
    seq               INT          NOT NULL,
    status            VARCHAR(24)  NOT NULL
        CHECK (status IN ('QUEUED','RUNNING','WAITING_APPROVAL','WAITING_CHILD','INTERRUPTED','COMPLETED','FAILED','CANCELLED')),
    origin            VARCHAR(16)  NOT NULL,
    origin_ref        VARCHAR(64),
    client_msg_id     VARCHAR(128),
    run_as_json       TEXT         NOT NULL,                -- Principal.identityOnly()
    options_json      TEXT,
    step_count        INT          NOT NULL DEFAULT 0,
    worker_id         VARCHAR(64),
    lease_version     BIGINT       NOT NULL DEFAULT 0,       -- 所有写入带此条件（定案 4）
    lease_until       TIMESTAMPTZ,
    error_code        VARCHAR(64),
    error_message     TEXT,
    prompt_tokens     INT,
    completion_tokens INT,
    started_at        TIMESTAMPTZ,
    ended_at          TIMESTAMPTZ,
    create_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by         VARCHAR(64)  NOT NULL DEFAULT 'system',
    update_by         VARCHAR(64)  NOT NULL DEFAULT 'system',
    UNIQUE (session_id, seq),
    UNIQUE (session_id, client_msg_id)
);
CREATE INDEX idx_turn_lease  ON runtime_turn (lease_until) WHERE status = 'RUNNING';
CREATE INDEX idx_turn_queued ON runtime_turn (session_id, seq) WHERE status = 'QUEUED';

-- 只增不改（定案 13）；seq 即 SSE 事件 ID
CREATE TABLE runtime_item (
    session_id   VARCHAR(64) NOT NULL,
    seq          BIGINT      NOT NULL,
    id           VARCHAR(32) NOT NULL UNIQUE,
    turn_id      VARCHAR(32) NOT NULL,
    step         INT         NOT NULL,
    type         VARCHAR(32) NOT NULL,                       -- ItemType.wire()
    role         VARCHAR(16),
    content_json TEXT        NOT NULL,
    token_count  INT,                                        -- 上下文压缩用（ADR-027）
    discarded    BOOLEAN     NOT NULL DEFAULT FALSE,         -- 故障接力作废（定案 2），唯一允许的更新
    create_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    create_by    VARCHAR(64) NOT NULL DEFAULT 'system',
    PRIMARY KEY (session_id, seq)
);
CREATE INDEX idx_item_turn ON runtime_item (turn_id, seq);

-- 通用审批表（定案 1）
CREATE TABLE runtime_approval (
    id           VARCHAR(32)  PRIMARY KEY,
    workspace_id VARCHAR(64),
    subject_type VARCHAR(16)  NOT NULL CHECK (subject_type IN ('turn','flow_node_run','orch_run')),
    subject_id   VARCHAR(64)  NOT NULL,
    item_id      VARCHAR(32),
    kind         VARCHAR(16)  NOT NULL CHECK (kind IN ('tool','card')),
    tool_name    VARCHAR(255),
    args_json    TEXT,
    card_json    TEXT,
    policy       VARCHAR(64),
    assignee_json TEXT,                                      -- 审批人：用户 / 角色
    status       VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED','EXPIRED')),
    decided_by   VARCHAR(64),
    decided_at   TIMESTAMPTZ,
    answer_json  TEXT,
    expires_at   TIMESTAMPTZ,
    create_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by    VARCHAR(64)  NOT NULL DEFAULT 'system',
    update_by    VARCHAR(64)  NOT NULL DEFAULT 'system'
);
CREATE INDEX idx_approval_subject ON runtime_approval (subject_type, subject_id);
CREATE INDEX idx_approval_pending ON runtime_approval (workspace_id, status, create_at) WHERE status = 'PENDING';

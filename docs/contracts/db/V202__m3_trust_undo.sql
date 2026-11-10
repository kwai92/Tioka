-- V202__m3_trust_undo.sql
-- TRU-07 撤销（M3-05 §5/§11；ADR-033「撤销」）：撤销期空间默认值、撤销请求记录、熔断计数。
-- trust_action 本体在 V144；本迁移加撤销范围 / 补偿顺序 / 重试列。

CREATE TABLE trust_undo_window (
    workspace_id  VARCHAR(64)  PRIMARY KEY,
    window_hours  INT          NOT NULL DEFAULT 24,
    update_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE trust_undo_request (
    id                VARCHAR(32)  PRIMARY KEY,
    workspace_id      VARCHAR(64)  NOT NULL,
    scope_type        VARCHAR(16)  NOT NULL CHECK (scope_type IN ('turn','run','action')),
    scope_id          VARCHAR(64)  NOT NULL,
    requester         VARCHAR(64)  NOT NULL,
    status            VARCHAR(16)  NOT NULL DEFAULT 'OPEN'
                      CHECK (status IN ('OPEN','COMPLETED','FAILED')),
    compensated_json  TEXT,
    irreversible_json TEXT,
    failed_action_id  VARCHAR(32),
    create_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_trust_undo_request_scope UNIQUE (scope_type, scope_id)
);

-- 熔断计数（TRU-07/08 共用）：同 Agent 1h 内撤销 3 次或补偿失败 1 次 → 全类别降 L1。
-- 降级联动归 TRU-10（M3 只写计数与事件，登记）；与 B-6a 编排规则暂停不是同一机制，不复用 orch_rule 列。
CREATE TABLE trust_undo_circuit (
    agent_id        VARCHAR(64)  PRIMARY KEY,
    workspace_id    VARCHAR(64)  NOT NULL,
    undo_count      INT          NOT NULL DEFAULT 0,
    comp_fail_count INT          NOT NULL DEFAULT 0,
    window_start    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

ALTER TABLE trust_action ADD COLUMN undo_scope_type VARCHAR(16);
ALTER TABLE trust_action ADD COLUMN undo_scope_id   VARCHAR(64);
ALTER TABLE trust_action ADD COLUMN compensate_seq  INT;
ALTER TABLE trust_action ADD COLUMN undo_attempts   INT NOT NULL DEFAULT 0;
CREATE INDEX idx_trust_action_scope ON trust_action (undo_scope_type, undo_scope_id);

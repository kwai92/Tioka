-- M2：编排表重建（ADR-020 及其对齐修订；ADR-029 第 35 条：无生产数据，直接重建）
-- 施工设计：M2-02-编排.md
DROP TABLE IF EXISTS orch_attention;
DROP TABLE IF EXISTS orch_why;
DROP TABLE IF EXISTS orch_idem;
DROP TABLE IF EXISTS orch_run;

ALTER TABLE orch_rule DROP COLUMN event_type;
ALTER TABLE orch_rule DROP COLUMN version;
ALTER TABLE orch_rule DROP COLUMN published;
ALTER TABLE orch_rule DROP COLUMN action_json;
ALTER TABLE orch_rule ADD COLUMN workspace_id VARCHAR(64);
ALTER TABLE orch_rule ADD COLUMN owner_id VARCHAR(64);
ALTER TABLE orch_rule ADD COLUMN stable_version_id VARCHAR(32);
ALTER TABLE orch_rule ADD COLUMN canary_version_id VARCHAR(32);

CREATE TABLE orch_event (
    id              VARCHAR(32)  PRIMARY KEY,
    workspace_id    VARCHAR(64),
    source          VARCHAR(64)  NOT NULL,
    type            VARCHAR(128) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    subject_key     VARCHAR(255),
    attrs_json      TEXT,
    body_json       TEXT,
    hops            INT          NOT NULL DEFAULT 0,
    disposition     VARCHAR(16)  NOT NULL CHECK (disposition IN ('STORED','DUPLICATE','FILTERED')),
    strength        VARCHAR(16)  NOT NULL CHECK (strength IN ('EXPLICIT','INFERRED')),
    received_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (idempotency_key)
);
CREATE INDEX idx_orch_event_type_ts ON orch_event (type, received_at);

CREATE TABLE orch_rule_version (
    id                 VARCHAR(32)  PRIMARY KEY,
    rule_id            VARCHAR(64)  NOT NULL,
    version            INT          NOT NULL,
    event_type         VARCHAR(128) NOT NULL,
    condition_cel      TEXT,
    actions_json       TEXT         NOT NULL,
    concurrency        VARCHAR(16)  NOT NULL CHECK (concurrency IN ('serial','merge','parallel')),
    accept_mode        VARCHAR(16)  NOT NULL DEFAULT 'observe' CHECK (accept_mode IN ('off','observe','auto')),
    tell               VARCHAR(16)  NOT NULL DEFAULT 'needed' CHECK (tell IN ('always','needed','never')),
    emits_json         TEXT,
    schedule_json      TEXT,                                   -- 可选：{cron, tz, misfire}
    escalate_json      TEXT,                                   -- 可选：人审超时升级
    service_account_id VARCHAR(64)  NOT NULL,
    stage              VARCHAR(16)  NOT NULL CHECK (stage IN ('DRAFT','CANARY','STABLE','RETIRED')),
    canary_percent     INT          CHECK (canary_percent BETWEEN 0 AND 100),
    create_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by          VARCHAR(64)  NOT NULL DEFAULT 'system',
    UNIQUE (rule_id, version)
);
CREATE INDEX idx_rule_version_event ON orch_rule_version (event_type) WHERE stage IN ('CANARY','STABLE');

CREATE TABLE orch_decision (
    id              VARCHAR(32) PRIMARY KEY,
    event_id        VARCHAR(32) NOT NULL,
    rule_version_id VARCHAR(32) NOT NULL,
    strength        VARCHAR(16) NOT NULL CHECK (strength IN ('EXPLICIT','INFERRED')),
    decision        VARCHAR(16) NOT NULL CHECK (decision IN ('ACCEPT','IGNORE','STOP','UNAVAILABLE','WOULD_ACCEPT')),
    reason          TEXT,
    work_item_id    VARCHAR(32),
    create_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_decision_event ON orch_decision (event_id);
CREATE INDEX idx_decision_rule ON orch_decision (rule_version_id, decision, create_at);

CREATE TABLE orch_work_item (
    id                  VARCHAR(32)  PRIMARY KEY,
    workspace_id        VARCHAR(64),
    rule_id             VARCHAR(64)  NOT NULL,
    subject_key         VARCHAR(255) NOT NULL,
    status              VARCHAR(16)  NOT NULL CHECK (status IN ('OPEN','CLOSED','STOPPED')),
    active_run_id       VARCHAR(32),
    thread_id           VARCHAR(64),
    pending_events_json TEXT,
    last_event_at       TIMESTAMPTZ,
    create_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (rule_id, subject_key)
);

CREATE TABLE orch_mandate (
    id                    VARCHAR(32)  PRIMARY KEY,
    work_item_id          VARCHAR(32)  NOT NULL,
    rule_version_id       VARCHAR(32)  NOT NULL,
    service_account_id    VARCHAR(64)  NOT NULL,
    strength              VARCHAR(16)  NOT NULL CHECK (strength IN ('EXPLICIT','INFERRED')),
    via                   VARCHAR(16)  NOT NULL CHECK (via IN ('ADDRESSED','FOLLOWUP','ADMISSION','BOT')),
    requester_source      VARCHAR(64),
    requester_external_id VARCHAR(255),
    requester_user_id     VARCHAR(64),
    scope_json            TEXT         NOT NULL,
    status                VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE','REVOKED')),
    revoked_by            VARCHAR(64),
    revoked_reason        TEXT,
    create_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    revoked_at            TIMESTAMPTZ
);
CREATE INDEX idx_mandate_work_item ON orch_mandate (work_item_id);

CREATE TABLE orch_subscription (
    work_item_id       VARCHAR(32)  NOT NULL,
    source             VARCHAR(64)  NOT NULL,
    external_thread_id VARCHAR(255) NOT NULL,
    until_at           TIMESTAMPTZ,
    create_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (source, external_thread_id)
);

CREATE TABLE orch_run (
    id              VARCHAR(32) PRIMARY KEY,
    workspace_id    VARCHAR(64),
    work_item_id    VARCHAR(32) NOT NULL,
    event_id        VARCHAR(32) NOT NULL,
    rule_version_id VARCHAR(32) NOT NULL,
    status          VARCHAR(24) NOT NULL CHECK (status IN
        ('PENDING','DISPATCHED','RUNNING','WAITING_ATTENTION','UNCERTAIN','SUCCEEDED','FAILED','CANCELLED')),
    attempt         INT         NOT NULL DEFAULT 1,
    rewrite_count   INT         NOT NULL DEFAULT 0,
    input_json      TEXT,
    thread_id       VARCHAR(64),
    current_turn_id VARCHAR(32),
    receipt_json    TEXT,
    run_as_json     TEXT        NOT NULL,
    error_code      VARCHAR(64),
    error_message   TEXT,
    create_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at        TIMESTAMPTZ
);
CREATE INDEX idx_orch_run_work_item ON orch_run (work_item_id, create_at);
CREATE INDEX idx_orch_run_turn ON orch_run (current_turn_id);
CREATE INDEX idx_orch_run_active ON orch_run (status) WHERE status NOT IN ('SUCCEEDED','FAILED','CANCELLED');

CREATE TABLE orch_timer (
    id           VARCHAR(32)  PRIMARY KEY,
    workspace_id VARCHAR(64),
    kind         VARCHAR(8)   NOT NULL CHECK (kind IN ('CRON','LATER')),
    rule_id      VARCHAR(64),
    work_item_id VARCHAR(32),
    timer_key    VARCHAR(255),
    cron         VARCHAR(64),
    tz           VARCHAR(64),
    next_fire_at TIMESTAMPTZ  NOT NULL,
    misfire      VARCHAR(16)  NOT NULL DEFAULT 'LATEST_ONLY' CHECK (misfire IN ('LATEST_ONLY','ALL','SKIP')),
    target_json  TEXT         NOT NULL,
    status       VARCHAR(16)  NOT NULL CHECK (status IN ('ACTIVE','DONE','CANCELLED')),
    create_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (work_item_id, timer_key)
);
CREATE INDEX idx_timer_due ON orch_timer (status, next_fire_at);

CREATE TABLE orch_fact (
    run_id      VARCHAR(32) NOT NULL,
    seq         INT         NOT NULL,
    kind        VARCHAR(32) NOT NULL,
    detail_json TEXT,
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, seq)
);

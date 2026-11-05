-- M2：事务 outbox、消费去重、死信（ORC-02、ORC-07）。施工设计：M2-01-消息与Outbox.md
CREATE TABLE sys_outbox (
    id           BIGSERIAL    PRIMARY KEY,
    event_id     VARCHAR(32)  NOT NULL UNIQUE,
    topic        VARCHAR(128) NOT NULL,
    tag          VARCHAR(128) NOT NULL,
    msg_key      VARCHAR(255),
    payload_json TEXT         NOT NULL,
    attempts     INT          NOT NULL DEFAULT 0,
    last_error   TEXT,
    create_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at      TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unsent ON sys_outbox (id) WHERE sent_at IS NULL;

CREATE TABLE sys_consumed (
    consumer    VARCHAR(64) NOT NULL,
    event_id    VARCHAR(32) NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

CREATE TABLE sys_dead_letter (
    id           VARCHAR(32)  PRIMARY KEY,
    consumer     VARCHAR(64)  NOT NULL,
    topic        VARCHAR(128) NOT NULL,
    tag          VARCHAR(128) NOT NULL,
    event_id     VARCHAR(32)  NOT NULL,
    payload_json TEXT         NOT NULL,
    error        TEXT,
    attempts     INT          NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','REPLAYED','DISCARDED')),
    create_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    handled_by   VARCHAR(64),
    handled_at   TIMESTAMPTZ
);
CREATE INDEX idx_dead_letter_open ON sys_dead_letter (consumer, create_at) WHERE status = 'OPEN';

DROP TABLE IF EXISTS im_outbox;    -- 由 sys_outbox + IM 消费者取代（M2-03）

-- V174__m3_notification.sql
-- NTF-01 通知投递状态机（M3-03 §15 / V174）：按 dedup_key 去重，经 outbox 异步发送。
-- 无 workspace_id 列（设计原文如此）：收件人为用户/员工号维度，登记
-- workspace-scope-exempt（PersistenceProperties，同 V175 lease_review_round 先例）。

CREATE TABLE ntf_delivery (
    delivery_id   VARCHAR(26) PRIMARY KEY,
    dedup_key     VARCHAR(160) NOT NULL UNIQUE,        -- {noticeKey}:{channel}:{recipient}
    channel       VARCHAR(32) NOT NULL,                -- feishu / email
    recipient     VARCHAR(128) NOT NULL,
    notice_json   JSONB       NOT NULL,
    status        VARCHAR(16) NOT NULL,                -- PENDING / SENT / FAILED / UPDATED
    external_ref  VARCHAR(128),                        -- 飞书 message_id / 邮件 Message-ID
    attempts      INT         NOT NULL DEFAULT 0,
    last_error    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at       TIMESTAMPTZ
);

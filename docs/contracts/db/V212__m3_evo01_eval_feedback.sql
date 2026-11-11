-- V212__m3_evo01_eval_feedback.sql
-- EVO-01 用户反馈（M3-06 §4.1 / ADR-031 数据模型；F40：去掉 tenant_id）。
-- 口径：每条助手消息可点赞/点踩并写意见；撤销（TRU-07）与法务驳回（lease.review.feedback）
-- 同样写一条负向反馈，source 列区分 USER / UNDO / REVIEW（ADR-071 反馈分诊预留）。
-- UNIQUE (turn_id, user_id)：自动负向反馈与人工反馈冲突时保留人工反馈（ON CONFLICT DO NOTHING）。
-- kb_eval_* 不建表（M3-00 L530：ADR-018 的 kb_eval_* 并入评测批次口径后取消）。
-- EVO-02 的 eval_dataset/eval_case/eval_evaluator/eval_run/eval_result 由对应批次另起迁移号（V212 已占）。
-- 无 workspace_id 列（ADR-031 设计原文如此），登记 workspace-scope-exempt（PersistenceProperties，同 V174/V175 先例）。

CREATE TABLE eval_feedback (
    id        VARCHAR(32) PRIMARY KEY,
    turn_id   VARCHAR(64) NOT NULL,
    user_id   VARCHAR(64) NOT NULL,
    rating    SMALLINT    NOT NULL,              -- +1 点赞 / -1 点踩
    comment   TEXT,
    source    VARCHAR(16) NOT NULL DEFAULT 'USER', -- USER / UNDO / REVIEW
    create_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    create_by VARCHAR(64) NOT NULL DEFAULT 'system',
    update_by VARCHAR(64) NOT NULL DEFAULT 'system',
    deleted   BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_eval_feedback_turn_user UNIQUE (turn_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_eval_feedback_turn ON eval_feedback (turn_id);

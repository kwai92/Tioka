-- V175__m3_lease_review_round.sql
-- LSE-06 审查轮次（M3-04「LSE-06 Finding 与轮次」）：Tioka 侧只做幂等与重审判断；
-- 结论本身（lease.finding@1）只存业务系统，本表记录每轮运行元数据与统计。
-- 幂等键 (review_run_id, round)；无 workspace_id 列（门店/合同维度经 review_run_id 承载），
-- 登记 workspace-scope-exempt（PersistenceProperties）。

CREATE TABLE lease_review_round (
    review_run_id          VARCHAR(64)  NOT NULL,
    round                  INTEGER      NOT NULL,               -- 第几轮；首轮 1，局部重审 +1
    tioka_run_id           VARCHAR(64),                         -- 关联编排 Run（可空：首轮自动触发前）
    model_version          VARCHAR(64)  NOT NULL,               -- 审查所用模型版本
    checkpoint_set_version VARCHAR(64)  NOT NULL,               -- 要点库版本（新版本发布不自动重审）
    status                 VARCHAR(32)  NOT NULL,               -- RUNNING / COMPLETED / FAILED / SUPERSEDED
    stats_json             JSONB        NOT NULL,               -- 统计（必备不满足/不满足/缺失/待人工 等）
    PRIMARY KEY (review_run_id, round)
);

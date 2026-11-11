-- V176__m3_evo02_eval_dataset_run.sql
-- EVO-02 数据集、评估器与批次评测（M3-06 §4.2 / ADR-031 数据模型 L80-97，逐列照建）。
-- eval_feedback 已由 V212 建表；eval_experiment 为 M4（EVO-04）不建；
-- EVO-07 附加字段（tier/unit_key_path/trials/version/config_hash）按 ADR-068 增量留给 V186 预登记，本批不建。
-- eval_case / eval_result 无 workspace_id 列（ADR-031 设计原文如此），
-- 登记 workspace-scope-exempt（PersistenceProperties，同 eval_feedback / V212 先例）。
-- eval_dataset 的 create_by/update_by 按 V212 审计列口径补 DEFAULT 'system'（列集与原文一致）。
-- 偏离登记：仅增补 idx_eval_case_dataset（eval_run 按 dataset_id+dataset_version 取用例的高频路径），
-- 其余索引与 ADR-031 原文一致（idx_eval_result_turn）。

CREATE TABLE eval_dataset (
    id             VARCHAR(32) PRIMARY KEY,
    workspace_id   VARCHAR(64) NOT NULL,
    name           VARCHAR(255) NOT NULL,
    schema_json    TEXT        NOT NULL,
    latest_version INT         NOT NULL DEFAULT 0,
    create_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    create_by      VARCHAR(64) NOT NULL DEFAULT 'system',
    update_by      VARCHAR(64) NOT NULL DEFAULT 'system',
    deleted        BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE TABLE eval_case (
    id             VARCHAR(32) PRIMARY KEY,
    dataset_id     VARCHAR(32) NOT NULL,
    version        INT         NOT NULL,
    input_json     TEXT        NOT NULL,
    expected_json  TEXT,
    source         VARCHAR(16) NOT NULL,             -- MANUAL / IMPORT / ONLINE
    source_turn_id VARCHAR(64),
    create_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE eval_evaluator (
    id           VARCHAR(32) PRIMARY KEY,
    workspace_id VARCHAR(64),                      -- NULL = 平台内置
    type         VARCHAR(32) NOT NULL,             -- exact / contains / citation / llm_judge / 第三方 SPI 编码
    name         VARCHAR(255) NOT NULL,
    config_json  TEXT        NOT NULL,
    builtin      BOOLEAN     NOT NULL DEFAULT FALSE
);

CREATE TABLE eval_run (
    id              VARCHAR(32) PRIMARY KEY,
    workspace_id    VARCHAR(64) NOT NULL,
    kind            VARCHAR(16) NOT NULL,            -- BATCH / ONLINE / EXPERIMENT_ARM / PUBLISH_GATE
    experiment_id   VARCHAR(32),
    dataset_id      VARCHAR(32),
    dataset_version INT,
    subject_type    VARCHAR(16) NOT NULL,            -- AGENT_VERSION / FLOW_PUBLISH
    subject_ref     VARCHAR(64) NOT NULL,
    variables_json  TEXT,
    status          VARCHAR(16) NOT NULL,            -- RUNNING / COMPLETED / FAILED
    summary_json    TEXT,
    run_as_json     TEXT        NOT NULL,
    create_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    ended_at        TIMESTAMPTZ
);

CREATE TABLE eval_result (                                                        -- 只增
    id           VARCHAR(32) PRIMARY KEY,
    run_id       VARCHAR(32),
    turn_id      VARCHAR(64),
    case_id      VARCHAR(32),
    evaluator_id VARCHAR(32) NOT NULL,
    score        DOUBLE PRECISION,
    passed       BOOLEAN,
    reason       TEXT,
    create_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_eval_result_turn ON eval_result (turn_id);
CREATE INDEX IF NOT EXISTS idx_eval_case_dataset ON eval_case (dataset_id, version);

-- 内置评估器注册（固定 ID，SPI type 与 eval-biz 四个内置实现一一对应；
-- config_json 允许运行时按 row 覆写，builtin=TRUE 不可删除）。
INSERT INTO eval_evaluator (id, workspace_id, type, name, config_json, builtin)
VALUES
    ('evl_builtin_exact',    NULL, 'exact',    '内置精确匹配', '{}', TRUE),
    ('evl_builtin_contains', NULL, 'contains',  '内置包含匹配', '{}', TRUE),
    ('evl_builtin_citation', NULL, 'citation',  '内置引用命中', '{}', TRUE),
    ('evl_builtin_llm_judge', NULL, 'llm_judge', '内置模型打分', '{}', TRUE)
ON CONFLICT (id) DO NOTHING;

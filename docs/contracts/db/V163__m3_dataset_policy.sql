-- V163__m3_dataset_policy.sql
-- DAT-01（ADR-040 数据模型 / M3-02）：数据集登记与行列策略。ID 前缀 dset。

CREATE TABLE data_dataset (
    id            VARCHAR(32)  PRIMARY KEY,
    workspace_id  VARCHAR(64)  NOT NULL,
    connector_id  VARCHAR(64)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    source_json   TEXT         NOT NULL,               -- 表 / 视图 / 查询 / 接口
    columns_json  TEXT         NOT NULL,               -- 列名、类型、密级、缺省掩码
    owner_id      VARCHAR(32)  NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    UNIQUE (workspace_id, name)
);

CREATE TABLE data_dataset_policy (
    dataset_id   VARCHAR(32),
    version      INT,
    rows_json    TEXT         NOT NULL,                 -- RowRule[]（when + predicate，CEL 子集）
    cols_json    TEXT,                                  -- ColumnRule[]（column + when + mask）
    mode         VARCHAR(8)   NOT NULL,                 -- DRYRUN / ENFORCE
    status       VARCHAR(16)  NOT NULL,
    created_by   VARCHAR(32),
    created_at   TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (dataset_id, version)
);

CREATE INDEX idx_data_dataset_ws ON data_dataset (workspace_id);

COMMENT ON TABLE data_dataset IS 'DAT-01 数据集登记：Agent 取数唯一入口 data.query 的目标对象（ADR-040）';
COMMENT ON TABLE data_dataset_policy IS 'DAT-01 行列策略：行谓词 CEL 子集 + 列掩码，随数据集版本化（ADR-040）';

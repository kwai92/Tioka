-- V172__m3_ontology.sql
CREATE TABLE ont_def (
    def_key       VARCHAR(64) NOT NULL,                -- lease.contract
    version       INT         NOT NULL,
    pack_key      VARCHAR(64) NOT NULL,
    pack_version  VARCHAR(32) NOT NULL,
    def_json      JSONB       NOT NULL,
    path_catalog  JSONB       NOT NULL,                -- 派生：路径 → 类型、枚举、单位
    content_hash  CHAR(64)    NOT NULL,
    status        VARCHAR(16) NOT NULL,                -- DRAFT / PUBLISHED / RETIRED
    PRIMARY KEY (def_key, version)
);

CREATE TABLE ont_draft (
    instance_key    VARCHAR(160) PRIMARY KEY,          -- lease:{门店编码}:{合同号}
    def_key         VARCHAR(64)  NOT NULL,
    def_version     INT          NOT NULL,
    store_key       VARCHAR(96)  NOT NULL,
    doc_ids         VARCHAR(26)[] NOT NULL,
    model_json      JSONB        NOT NULL,
    validation_json JSONB        NOT NULL,             -- REJECT / REVIEW 清单
    build_run_id    VARCHAR(26)  NOT NULL,             -- Flow Run
    biz_draft_ref   VARCHAR(128),                      -- proposeModelDraft 的返回
    revision        INT          NOT NULL DEFAULT 1,   -- 乐观锁
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE ont_instance_version (
    instance_key   VARCHAR(160) NOT NULL,
    version        INT          NOT NULL,              -- 业务系统给的版本号
    status         VARCHAR(16)  NOT NULL,              -- CONFIRMED / SUPERSEDED
    def_key        VARCHAR(64)  NOT NULL,
    def_version    INT          NOT NULL,
    store_key      VARCHAR(96)  NOT NULL,
    model_json     JSONB        NOT NULL,
    content_hash   CHAR(64)     NOT NULL,
    diff_prev      JSONB,                              -- 与上一确认版的路径级差异
    diff_draft     JSONB,                              -- 与对应草稿的差异（= 人工更正）
    refs_json      JSONB,                              -- 跨合同引用：[{instanceKey, version}]
    confirmed_by   VARCHAR(64)  NOT NULL,
    confirmed_at   TIMESTAMPTZ  NOT NULL,
    acl_principals VARCHAR(96)[] NOT NULL,
    data_label     VARCHAR(16)  NOT NULL DEFAULT 'CONFIDENTIAL',
    PRIMARY KEY (instance_key, version)
);
CREATE INDEX idx_ont_iv_store ON ont_instance_version (store_key) WHERE status = 'CONFIRMED';

CREATE TABLE ont_value_index (                       -- 只为按路径查询与覆盖统计，可由 model_json 重建
    instance_key VARCHAR(160) NOT NULL,
    version      INT          NOT NULL,
    path         VARCHAR(256) NOT NULL,
    status       VARCHAR(12)  NOT NULL,
    value_text   TEXT,
    topics       VARCHAR(32)[],
    PRIMARY KEY (instance_key, version, path)
);

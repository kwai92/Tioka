-- M3-03 §1：V170 文档层（DOC-01）。四表 DDL 照 M3-03 权威定义抄录（docs/architecture/construction/M3-03-业务本体与文档层.md L44-104）。
-- 契约副本：docs/contracts/db/V170__m3_document_layer.sql
-- space_id 语义 = workspace_id（本仓库 workspaceId 权威；登记映射偏差）。
CREATE TABLE doc_document (
    doc_id          VARCHAR(26) PRIMARY KEY,           -- ULID
    file_hash       CHAR(64)     NOT NULL,             -- SHA-256
    parser_version  VARCHAR(16)  NOT NULL,
    mime            VARCHAR(100) NOT NULL,
    file_name       VARCHAR(255),
    source_system   VARCHAR(64),                       -- biz.lease
    source_ref      VARCHAR(128),                      -- 业务系统文件 ID
    object_key      VARCHAR(512) NOT NULL,             -- 对象存储里的原文件
    page_count      INT,
    parse_status    VARCHAR(16)  NOT NULL,             -- PENDING / PARSING / READY / FAILED
    parse_error     TEXT,
    data_label      VARCHAR(16)  NOT NULL DEFAULT 'CONFIDENTIAL',
    space_id        VARCHAR(26)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (file_hash, parser_version, space_id)
);

CREATE TABLE doc_page (
    doc_id       VARCHAR(26) NOT NULL REFERENCES doc_document,
    page_no      INT         NOT NULL,
    source       VARCHAR(8)  NOT NULL,                 -- TEXT / OCR / MERGED
    text_layer   TEXT,
    ocr_json     JSONB,                                -- OCR 原始结果（行、框、置信度）
    ocr_model    VARCHAR(64),
    char_density NUMERIC(6,2),
    PRIMARY KEY (doc_id, page_no)
);

CREATE TABLE doc_unit (
    unit_id      VARCHAR(80) PRIMARY KEY,              -- {docId}:{pageNo}:{seq}
    doc_id       VARCHAR(26) NOT NULL REFERENCES doc_document,
    page_no      INT         NOT NULL,
    seq          INT         NOT NULL,                 -- 文档内全局顺序
    kind         VARCHAR(16) NOT NULL,                 -- PARA / HEADING / CELL / SIGN / BLANK_FIELD
    bbox         JSONB,                                -- [x0,y0,x1,y1]，Word 文档为空
    text         TEXT        NOT NULL,
    norm_text    TEXT        NOT NULL,
    clause_path  VARCHAR(64),
    section      VARCHAR(8),                           -- DEF / SPECIAL / GENERAL / ANNEX
    table_ref    JSONB,                                -- 表格单元格：{tableId,row,col}
    ocr_conf     NUMERIC(4,3)
);
CREATE INDEX idx_doc_unit_doc_seq ON doc_unit (doc_id, seq);

CREATE TABLE doc_clause (
    doc_id       VARCHAR(26) NOT NULL REFERENCES doc_document,
    clause_path  VARCHAR(64) NOT NULL,
    parent_path  VARCHAR(64),
    title        VARCHAR(255),
    unit_from    INT NOT NULL,                         -- seq 闭区间
    unit_to      INT NOT NULL,
    status       VARCHAR(16) NOT NULL DEFAULT 'VALID', -- VALID / BLANK / MODIFIED
    topics       VARCHAR(32)[] NOT NULL DEFAULT '{}',
    PRIMARY KEY (doc_id, clause_path)
);

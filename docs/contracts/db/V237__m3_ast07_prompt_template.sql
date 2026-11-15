-- V237__m3_ast07_prompt_template.sql
-- AST-07（ADR-041 第 1 / 4 节）：Prompt 模板身份表。
-- 内容（分段正文 / 变量定义 / 用途 / 输出格式 / 建议别名 / 样例变量）存 cfg_*（发布型对象，objectType=prm_template）；
-- 本表只保留身份与作用域，作为当前生效版本投影（V150 投影模式，MH-08 四表同构）。
-- 无 tenant_id、无 workspace_id（系统级 / 包级 / 空间级 / 个人级作用域由 scope + scope_ref 表达）。
-- 原登记号 V154 已被 GOV-13 等占用，本项按顺序取号落 V237。

CREATE TABLE IF NOT EXISTS prm_template (
    id                 VARCHAR(64)  PRIMARY KEY,
    name               VARCHAR(255) NOT NULL,
    scope              VARCHAR(16)  NOT NULL,
    scope_ref          VARCHAR(64),
    current_version_id VARCHAR(64),
    current_version_no INT,
    create_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_by          VARCHAR(64)  NOT NULL DEFAULT 'system',
    update_by          VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted            BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_prm_template_name ON prm_template (name);

CREATE INDEX IF NOT EXISTS idx_prm_template_scope ON prm_template (scope, scope_ref);

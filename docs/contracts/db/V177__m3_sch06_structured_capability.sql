-- V177__m3_sch06_structured_capability.sql
-- SCH-06（ADR-066 §4）：模型结构化输出能力记录（取代 structuredOutput 三档枚举）。
-- 无 tenant_id（ADR-029）；无 workspace_id 列（模型级全局配置，与 model_binding 同口径，
-- 须登记 tioka.persistence.workspace-scope-exempt-tables）。
-- model_ref 口径 = logicalModelId@modelName（与 ModelBindingResolver 一致）。
-- 能力以一致性测试为准（ADR-066 §4 / SCH-10 写回），本表为该记录的持久化载体；
-- version 变化即视为新记录，编译出的生成方言随之变化（ADR-066 §2 方言缓存键）。

CREATE TABLE model_structured_capability (
    model_ref            VARCHAR(160) PRIMARY KEY,
    version              VARCHAR(64)  NOT NULL,
    modes                JSONB        NOT NULL DEFAULT '[]'::jsonb,
    keywords             JSONB        NOT NULL DEFAULT '[]'::jsonb,
    limits               JSONB,
    thinking_compat      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    multimodal_downgrade BOOLEAN      NOT NULL DEFAULT false,
    prompt_keyword       BOOLEAN      NOT NULL DEFAULT false,
    max_tokens_advice    VARCHAR(16)  NOT NULL DEFAULT 'UNSET',
    stability            VARCHAR(16)  NOT NULL DEFAULT 'GA',
    refusal_signal       VARCHAR(64),
    truncation_signal    VARCHAR(64),
    verified_at          VARCHAR(64),
    conformance          VARCHAR(64),
    create_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by            VARCHAR(64)  NOT NULL DEFAULT 'system',
    update_by            VARCHAR(64)  NOT NULL DEFAULT 'system'
);

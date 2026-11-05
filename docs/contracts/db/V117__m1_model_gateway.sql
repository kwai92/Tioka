-- M1：模型网关所需的 binding 属性、故障接力链、默认方案（MH-01〜05）
ALTER TABLE model_binding ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'chat' CHECK (kind IN ('chat','embedding','rerank'));
ALTER TABLE model_binding ADD COLUMN context_window INT;
ALTER TABLE model_binding ADD COLUMN embedding_dim INT;
ALTER TABLE model_binding ADD COLUMN input_price_micros  BIGINT NOT NULL DEFAULT 0;   -- 每 1M 输入 token
ALTER TABLE model_binding ADD COLUMN output_price_micros BIGINT NOT NULL DEFAULT 0;   -- 每 1M 输出 token
ALTER TABLE model_binding ADD COLUMN rpm_limit INT;
ALTER TABLE model_binding ADD COLUMN tpm_limit INT;
ALTER TABLE model_binding ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE model_binding ADD CONSTRAINT ck_binding_embedding_dim CHECK (kind <> 'embedding' OR embedding_dim IS NOT NULL) NOT VALID;

ALTER TABLE model_routing_plan ADD COLUMN fallback_chain VARCHAR(512);                -- 逗号分隔 binding ID
UPDATE model_routing_plan SET fallback_chain = fallback_binding_id WHERE fallback_binding_id IS NOT NULL;

CREATE TABLE model_default_plan (
    purpose         VARCHAR(16) PRIMARY KEY,                                          -- ModelPurpose
    routing_plan_id VARCHAR(64),
    binding_id      VARCHAR(64),
    update_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_by       VARCHAR(64) NOT NULL DEFAULT 'system',
    CHECK (routing_plan_id IS NOT NULL OR binding_id IS NOT NULL)
);

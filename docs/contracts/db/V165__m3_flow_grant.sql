-- V165__m3_flow_grant.sql
-- DAT-04（ADR-029 第 47 条 / M3-02）：Flow 授予透传——Agent 工具形态的 Flow Run
-- 携带调用方 grant_id；发布记录写入 requiredCapabilities。

ALTER TABLE flow_run         ADD COLUMN grant_id VARCHAR(64);
ALTER TABLE flow_publication ADD COLUMN required_capabilities_json TEXT;

COMMENT ON COLUMN flow_run.grant_id IS 'DAT-04：Agent 工具形态 Flow Run 的调用方授予 ID；API 形态为服务账号根授予';

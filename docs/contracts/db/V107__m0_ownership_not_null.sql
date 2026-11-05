-- FND-03：回填后将归属列改为 NOT NULL（ADR-029 第 36 条；禁止 tenant_id）
UPDATE chat_conversation SET workspace_id = 'ws_default' WHERE workspace_id IS NULL;
UPDATE chat_conversation SET owner_id = '0' WHERE owner_id IS NULL;
UPDATE agent_agent SET owner_id = '0' WHERE owner_id IS NULL;
UPDATE kb_base SET owner_id = '0' WHERE owner_id IS NULL;
UPDATE flow_draft SET workspace_id = 'ws_default' WHERE workspace_id IS NULL;
UPDATE flow_draft SET owner_id = '0' WHERE owner_id IS NULL;
UPDATE mcp_server SET workspace_id = 'ws_default' WHERE workspace_id IS NULL;
UPDATE mcp_server SET owner_id = '0' WHERE owner_id IS NULL;
UPDATE skill_package SET workspace_id = 'ws_default' WHERE workspace_id IS NULL;
UPDATE skill_package SET owner_id = '0' WHERE owner_id IS NULL;
UPDATE runtime_session SET workspace_id = COALESCE(workspace_id, 'ws_default') WHERE workspace_id IS NULL;
UPDATE runtime_session SET owner_id = '0' WHERE owner_id IS NULL;
UPDATE orch_run SET workspace_id = 'ws_default' WHERE workspace_id IS NULL;

ALTER TABLE orch_run ADD COLUMN IF NOT EXISTS owner_id VARCHAR(64);
UPDATE orch_run SET owner_id = '0' WHERE owner_id IS NULL;

ALTER TABLE chat_conversation ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE chat_conversation ALTER COLUMN owner_id SET NOT NULL;
ALTER TABLE agent_agent ALTER COLUMN owner_id SET NOT NULL;
ALTER TABLE kb_base ALTER COLUMN owner_id SET NOT NULL;
ALTER TABLE flow_draft ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE flow_draft ALTER COLUMN owner_id SET NOT NULL;
ALTER TABLE mcp_server ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE mcp_server ALTER COLUMN owner_id SET NOT NULL;
ALTER TABLE skill_package ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE skill_package ALTER COLUMN owner_id SET NOT NULL;
ALTER TABLE runtime_session ALTER COLUMN owner_id SET NOT NULL;
ALTER TABLE orch_run ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE orch_run ALTER COLUMN owner_id SET NOT NULL;

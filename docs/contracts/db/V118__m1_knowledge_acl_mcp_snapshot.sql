-- M1：知识检索 ACL 下推（KNW-02）
ALTER TABLE kb_document ADD COLUMN acl_principals TEXT;        -- JSON 数组；空 = 继承知识库权限（ADR-029 第 23 条），M1 建列不启用
ALTER TABLE kb_chunk    ADD COLUMN workspace_id VARCHAR(64);
UPDATE kb_chunk c SET workspace_id = b.workspace_id FROM kb_base b WHERE c.kb_id = b.id;
UPDATE kb_chunk SET workspace_id = 'ws_default' WHERE workspace_id IS NULL;
ALTER TABLE kb_chunk ALTER COLUMN workspace_id SET NOT NULL;
CREATE INDEX idx_chunk_kb ON kb_chunk (kb_id);
CREATE INDEX idx_chunk_ws ON kb_chunk (workspace_id);

-- agent_version.mcp_grants_json 已移除（2026-11-06）：MCP 授权快照并入 AgentSpec.tools.mcp（V123，AST-01）

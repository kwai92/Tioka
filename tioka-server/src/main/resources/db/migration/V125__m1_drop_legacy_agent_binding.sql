-- M1：删除 Agent 旧绑定列与表（AST-01，随该工作项 PR 合入；ADR-029 第 33 条修订）
-- 前提：V124 已把旧数据组装进 spec_json / agent_draft；回填失败的 Agent 已在迁移日志列出。

DELETE FROM agent_version WHERE published = FALSE;           -- 未发布的旧版本已转为 agent_draft
DELETE FROM agent_version WHERE spec_json IS NULL;           -- V124 跳过的 Agent，需管理员重新发布
ALTER TABLE agent_version ALTER COLUMN version_no SET NOT NULL;
ALTER TABLE agent_version ALTER COLUMN spec_json  SET NOT NULL;
ALTER TABLE agent_version DROP COLUMN published;
ALTER TABLE agent_version DROP COLUMN instructions;
ALTER TABLE agent_version DROP COLUMN mcp_grant_ids;
ALTER TABLE agent_version DROP COLUMN skill_bindings;
UPDATE agent_agent a SET published_version_id = NULL
 WHERE published_version_id IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM agent_version v WHERE v.id = a.published_version_id);
DROP TABLE agent_sandbox;
DROP TABLE mcp_grant;                                         -- 编辑态在 agent_draft.spec_json.tools.mcp

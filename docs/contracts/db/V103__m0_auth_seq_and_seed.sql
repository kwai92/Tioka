-- M0：用户 ID 序列、首次改密、M0 最小权限点、默认空间回填、Agent 乐观锁
-- 施工设计：docs/architecture/construction/M0-02-身份与空间.md（FND-02、FND-03、GOV-04）；ADR-029 第 31、36 条

CREATE SEQUENCE IF NOT EXISTS auth_user_id_seq START WITH 1000;
ALTER TABLE auth_user ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE auth_user ADD COLUMN email VARCHAR(255);
CREATE UNIQUE INDEX uk_auth_user_email ON auth_user (lower(email)) WHERE email IS NOT NULL;

-- 旧默认管理员 admin/admin123 禁用，由 BootstrapAdminRunner 另建
UPDATE auth_user SET enabled = FALSE WHERE id = 1 AND username = 'admin';

-- M0 内置角色的最小权限点（M1 V110 全量替换）
INSERT INTO sys_role_permission (role_code, permission) VALUES
  ('tenant_admin',    '*'),
  ('workspace_admin', 'workspace:manage'),
  ('workspace_admin', 'agent:write'),
  ('workspace_admin', 'chat:use'),
  ('builder',         'agent:write'),
  ('builder',         'flow:write'),
  ('builder',         'kb:write'),
  ('builder',         'chat:use'),
  ('member',          'chat:use'),
  ('member',          'agent:read'),
  ('viewer',          'agent:read');

-- 已有数据归入默认空间（ADR-029 第 36 条：无 tenant_id）
INSERT INTO sys_workspace (id, name, owner_user_id)
  SELECT 'ws_default', '默认空间', 1
  WHERE NOT EXISTS (SELECT 1 FROM sys_workspace WHERE id = 'ws_default');
UPDATE chat_conversation SET workspace_id = 'ws_default' WHERE workspace_id IS NULL;
UPDATE flow_draft        SET workspace_id = 'ws_default' WHERE workspace_id IS NULL;
UPDATE mcp_server        SET workspace_id = 'ws_default' WHERE workspace_id IS NULL;
UPDATE skill_package     SET workspace_id = 'ws_default' WHERE workspace_id IS NULL;
UPDATE runtime_session   SET workspace_id = 'ws_default' WHERE workspace_id IS NULL;
UPDATE orch_run          SET workspace_id = 'ws_default' WHERE workspace_id IS NULL;

-- Agent 发布乐观锁（GOV-04）
ALTER TABLE agent_agent ADD COLUMN version INT NOT NULL DEFAULT 0;

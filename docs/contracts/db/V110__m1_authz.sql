-- M1：租户级角色、组、完整权限点、废弃 agent_share
-- 施工设计：M1-01-权限与身份联邦.md（FND-04）

CREATE TABLE sys_user_role (
    user_id    VARCHAR(64) NOT NULL,
    role_code  VARCHAR(64) NOT NULL,
    create_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    create_by  VARCHAR(64) NOT NULL DEFAULT 'system',
    PRIMARY KEY (user_id, role_code)
);
INSERT INTO sys_user_role (user_id, role_code)
  SELECT user_id, role_code FROM sys_member WHERE role_code = 'tenant_admin'
  ON CONFLICT DO NOTHING;
DELETE FROM sys_member WHERE role_code = 'tenant_admin';

CREATE TABLE sys_group (
    id          VARCHAR(32)  PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    source      VARCHAR(32)  NOT NULL DEFAULT 'local',      -- local / oidc:{idp} / scim
    external_id VARCHAR(255),
    create_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by   VARCHAR(64)  NOT NULL DEFAULT 'system',
    update_by   VARCHAR(64)  NOT NULL DEFAULT 'system',
    UNIQUE (source, external_id)
);
CREATE TABLE sys_group_member (
    group_id   VARCHAR(32) NOT NULL,
    user_id    VARCHAR(64) NOT NULL,
    create_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, user_id)
);
CREATE INDEX idx_group_member_user ON sys_group_member (user_id);

DELETE FROM sys_role_permission ;
INSERT INTO sys_role_permission (role_code, permission)
SELECT p.role_code, p.permission
FROM (VALUES
  ('tenant_admin', '*'),
  -- workspace_admin：除 tenant:admin、model:admin 外全部
  ('workspace_admin','agent:read'),('workspace_admin','agent:write'),('workspace_admin','agent:publish'),('workspace_admin','agent:share'),
  ('workspace_admin','flow:read'),('workspace_admin','flow:write'),('workspace_admin','flow:publish'),('workspace_admin','flow:run'),
  ('workspace_admin','kb:read'),('workspace_admin','kb:write'),('workspace_admin','kb:share'),
  ('workspace_admin','skill:read'),('workspace_admin','skill:write'),('workspace_admin','skill:publish'),
  ('workspace_admin','mcp:read'),('workspace_admin','mcp:write'),('workspace_admin','mcp:publish'),
  ('workspace_admin','chat:use'),('workspace_admin','approval:decide'),
  ('workspace_admin','orch:read'),('workspace_admin','orch:write'),('workspace_admin','orch:operate'),
  ('workspace_admin','model:read'),('workspace_admin','quota:read'),('workspace_admin','quota:admin'),
  ('workspace_admin','audit:read'),('workspace_admin','workspace:manage'),
  -- builder
  ('builder','agent:read'),('builder','agent:write'),('builder','agent:publish'),('builder','agent:share'),
  ('builder','flow:read'),('builder','flow:write'),('builder','flow:publish'),('builder','flow:run'),
  ('builder','kb:read'),('builder','kb:write'),('builder','kb:share'),
  ('builder','skill:read'),('builder','skill:write'),('builder','skill:publish'),
  ('builder','mcp:read'),('builder','mcp:write'),('builder','mcp:publish'),
  ('builder','chat:use'),('builder','orch:read'),('builder','orch:write'),('builder','model:read'),('builder','approval:decide'),
  -- member
  ('member','agent:read'),('member','flow:read'),('member','flow:run'),('member','kb:read'),('member','skill:read'),
  ('member','mcp:read'),('member','chat:use'),('member','approval:decide'),
  -- viewer：所有 *:read（不含 audit:read）
  ('viewer','agent:read'),('viewer','flow:read'),('viewer','kb:read'),('viewer','skill:read'),('viewer','mcp:read'),
  ('viewer','orch:read'),('viewer','model:read'),('viewer','quota:read')
) AS p(role_code, permission)
JOIN sys_role r ON r.code = p.role_code;

-- 现有资源的 owner 写入 ACL
INSERT INTO sys_resource_acl (resource_type, resource_id, subject_type, subject_id, level)
  SELECT 'agent', id, 'user', owner_id, 'OWNER' FROM agent_agent WHERE owner_id IS NOT NULL ON CONFLICT DO NOTHING;
INSERT INTO sys_resource_acl (resource_type, resource_id, subject_type, subject_id, level)
  SELECT 'kb', id, 'user', owner_id, 'OWNER' FROM kb_base WHERE owner_id IS NOT NULL ON CONFLICT DO NOTHING;
INSERT INTO sys_resource_acl (resource_type, resource_id, subject_type, subject_id, level)
  SELECT 'flow', id, 'user', owner_id, 'OWNER' FROM flow_draft WHERE owner_id IS NOT NULL ON CONFLICT DO NOTHING;
INSERT INTO sys_resource_acl (resource_type, resource_id, subject_type, subject_id, level)
  SELECT 'mcp', id, 'user', owner_id, 'OWNER' FROM mcp_server WHERE owner_id IS NOT NULL ON CONFLICT DO NOTHING;
INSERT INTO sys_resource_acl (resource_type, resource_id, subject_type, subject_id, level)
  SELECT 'skill', id, 'user', owner_id, 'OWNER' FROM skill_package WHERE owner_id IS NOT NULL ON CONFLICT DO NOTHING;

-- agent_share 只有 agent_id + mode，没有被分享人；空间内共享改由 visibility 表达（V111）
DROP TABLE IF EXISTS agent_share;

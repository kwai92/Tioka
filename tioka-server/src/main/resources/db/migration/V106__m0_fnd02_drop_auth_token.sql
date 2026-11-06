-- FND-02：令牌迁 Redis；禁用旧默认管理员；sys_member 支持同用户多角色（施工 M0-02）
DROP TABLE IF EXISTS auth_token;

UPDATE auth_user SET enabled = FALSE WHERE id = 1 AND username = 'admin';

-- 同空间同用户可同时挂 workspace_admin + tenant_admin（BootstrapAdminRunner）
ALTER TABLE sys_member DROP CONSTRAINT IF EXISTS sys_member_pkey;
ALTER TABLE sys_member ADD PRIMARY KEY (workspace_id, user_id, role_code);

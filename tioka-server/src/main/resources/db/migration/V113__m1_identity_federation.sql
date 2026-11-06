-- M1：OIDC 组映射来源、服务账号（FND-05、FND-06）
ALTER TABLE sys_member ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'manual';   -- manual / oidc:{idp}
ALTER TABLE auth_user  ADD COLUMN type   VARCHAR(16) NOT NULL DEFAULT 'USER' CHECK (type IN ('USER','SERVICE'));
CREATE INDEX idx_api_key_service_user ON sys_api_key (service_user_id);

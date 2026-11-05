-- M0 契约迁移 1/3：身份、租户与权限（ADR-015；定案见 ADR-029 第 9、10、17、19、20 条）
-- 基线：tioka-server/src/main/resources/schema-pg.sql。Flyway 引入后（FND-01）原样移入 db/migration。
-- 约定：新表主键 ULID VARCHAR(32)；外键列 VARCHAR(64)；时间列 TIMESTAMPTZ；
--       可变表带 BaseDO 列（create_at/update_at/create_by/update_by/deleted），只增表带 create_at/create_by。

CREATE TABLE sys_tenant (
    id          VARCHAR(32)  PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    status      VARCHAR(16)  NOT NULL,                    -- ACTIVE / SUSPENDED
    create_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by   VARCHAR(64)  NOT NULL DEFAULT 'system',
    update_by   VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted     BOOLEAN      NOT NULL DEFAULT FALSE
);
-- 单租户部署固定使用 'default'（ADR-029 第 19 条）
INSERT INTO sys_tenant (id, name, status) VALUES ('default', 'Default', 'ACTIVE');

ALTER TABLE sys_workspace ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';

ALTER TABLE auth_user ADD COLUMN tenant_id        VARCHAR(64)  NOT NULL DEFAULT 'default';
ALTER TABLE auth_user ADD COLUMN idp              VARCHAR(32)  NOT NULL DEFAULT 'local';   -- local / oidc:keycloak
ALTER TABLE auth_user ADD COLUMN external_subject VARCHAR(255);
ALTER TABLE auth_user ADD COLUMN default_workspace_id VARCHAR(64);
CREATE UNIQUE INDEX uk_auth_user_idp_subject ON auth_user (tenant_id, idp, external_subject)
    WHERE external_subject IS NOT NULL;
-- password_hash 改存 bcrypt 由 FND-02 的数据迁移完成；auth_token 由 V106 在 FND-02 合入时删除（现有 AuthService 仍在使用）

CREATE TABLE sys_member (
    workspace_id VARCHAR(64) NOT NULL,
    user_id      VARCHAR(64) NOT NULL,
    role_code    VARCHAR(64) NOT NULL,
    create_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    create_by    VARCHAR(64) NOT NULL DEFAULT 'system',
    update_by    VARCHAR(64) NOT NULL DEFAULT 'system',
    deleted      BOOLEAN     NOT NULL DEFAULT FALSE,
    PRIMARY KEY (workspace_id, user_id)
);
CREATE INDEX idx_sys_member_user ON sys_member (user_id);

CREATE TABLE sys_role (
    tenant_id  VARCHAR(64)  NOT NULL,
    code       VARCHAR(64)  NOT NULL,
    name       VARCHAR(128) NOT NULL,
    scope      VARCHAR(16)  NOT NULL,                      -- TENANT / WORKSPACE
    builtin    BOOLEAN      NOT NULL DEFAULT FALSE,
    create_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by  VARCHAR(64)  NOT NULL DEFAULT 'system',
    update_by  VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted    BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (tenant_id, code)
);

CREATE TABLE sys_role_permission (
    tenant_id  VARCHAR(64)  NOT NULL,
    role_code  VARCHAR(64)  NOT NULL,
    permission VARCHAR(128) NOT NULL,                      -- 资源:动作，如 agent:write
    create_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by  VARCHAR(64)  NOT NULL DEFAULT 'system',
    PRIMARY KEY (tenant_id, role_code, permission)
);

INSERT INTO sys_role (tenant_id, code, name, scope, builtin) VALUES
    ('default', 'tenant_admin',    '租户管理员', 'TENANT',    TRUE),
    ('default', 'workspace_admin', '空间管理员', 'WORKSPACE', TRUE),
    ('default', 'builder',         '构建者',     'WORKSPACE', TRUE),
    ('default', 'member',          '成员',       'WORKSPACE', TRUE),
    ('default', 'viewer',          '只读',       'WORKSPACE', TRUE);
-- 内置角色的权限点清单由 system-biz 启动时同步（权限点枚举随代码演进），此处不固化

CREATE TABLE sys_resource_acl (
    resource_type VARCHAR(32) NOT NULL,                    -- agent / flow / kb / skill / mcp / routing_plan
    resource_id   VARCHAR(64) NOT NULL,
    subject_type  VARCHAR(16) NOT NULL,                    -- user / group / workspace
    subject_id    VARCHAR(64) NOT NULL,
    level         VARCHAR(16) NOT NULL,                    -- VIEW / USE / EDIT / OWNER
    create_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    create_by     VARCHAR(64) NOT NULL DEFAULT 'system',
    update_by     VARCHAR(64) NOT NULL DEFAULT 'system',
    PRIMARY KEY (resource_type, resource_id, subject_type, subject_id)
);
CREATE INDEX idx_acl_subject ON sys_resource_acl (subject_type, subject_id, resource_type);
-- agent_share 的数据迁入 sys_resource_acl 后删除（FND-05）

CREATE TABLE sys_api_key (
    id           VARCHAR(32)   PRIMARY KEY,
    tenant_id    VARCHAR(64)   NOT NULL,
    workspace_id VARCHAR(64),
    name         VARCHAR(128)  NOT NULL,
    key_prefix   VARCHAR(16)   NOT NULL,                   -- 明文前缀，便于识别
    key_hash     VARCHAR(128)  NOT NULL UNIQUE,
    service_user_id VARCHAR(64) NOT NULL,                  -- 服务账号身份（PrincipalType.SERVICE）
    scopes       VARCHAR(1024),
    expires_at   TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    create_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    update_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    create_by    VARCHAR(64)   NOT NULL DEFAULT 'system',
    update_by    VARCHAR(64)   NOT NULL DEFAULT 'system',
    deleted      BOOLEAN       NOT NULL DEFAULT FALSE
);

-- 业务表归属三列（ADR-015）。已有 workspace_id 的表只补缺的列。
ALTER TABLE chat_conversation ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE chat_conversation ADD COLUMN workspace_id VARCHAR(64);
ALTER TABLE chat_conversation ADD COLUMN owner_id VARCHAR(64);
ALTER TABLE agent_agent       ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE agent_agent       ADD COLUMN owner_id VARCHAR(64);
ALTER TABLE kb_base           ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE kb_base           ADD COLUMN owner_id VARCHAR(64);
ALTER TABLE flow_draft        ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE flow_draft        ADD COLUMN workspace_id VARCHAR(64);
ALTER TABLE flow_draft        ADD COLUMN owner_id VARCHAR(64);
ALTER TABLE mcp_server        ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE mcp_server        ADD COLUMN workspace_id VARCHAR(64);
ALTER TABLE mcp_server        ADD COLUMN owner_id VARCHAR(64);
ALTER TABLE skill_package     ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE skill_package     ADD COLUMN workspace_id VARCHAR(64);
ALTER TABLE skill_package     ADD COLUMN owner_id VARCHAR(64);
ALTER TABLE runtime_session   ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE runtime_session   ADD COLUMN owner_id VARCHAR(64);
ALTER TABLE orch_run          ADD COLUMN tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE orch_run          ADD COLUMN workspace_id VARCHAR(64);
-- 回填默认空间后由 FND-03 把 workspace_id / owner_id 改为 NOT NULL

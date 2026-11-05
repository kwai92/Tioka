-- M0 契约迁移 1/3：身份与权限（ADR-015；定案见 ADR-029 第 9、10、17、20、36 条）
-- 基线：tioka-server/src/main/resources/schema-pg.sql。Flyway 引入后（ENG-11）原样移入 db/migration。
-- 约定：新表主键 ULID VARCHAR(32)；外键列 VARCHAR(64)；时间列 TIMESTAMPTZ；
--       可变表带 BaseDO 列（create_at/update_at/create_by/update_by/deleted），只增表带 create_at/create_by。
--       禁止 tenant_id / sys_tenant（ADR-029 第 36 条；单实例部署，空间为隔离边界）。

ALTER TABLE auth_user ADD COLUMN idp              VARCHAR(32)  NOT NULL DEFAULT 'local';   -- local / oidc:keycloak
ALTER TABLE auth_user ADD COLUMN external_subject VARCHAR(255);
ALTER TABLE auth_user ADD COLUMN default_workspace_id VARCHAR(64);
CREATE UNIQUE INDEX uk_auth_user_idp_subject ON auth_user (idp, external_subject)
    WHERE external_subject IS NOT NULL;
-- password_hash 改存 bcrypt 由 FND-02 的数据迁移完成；auth_token 迁到 Redis 后删除
DROP TABLE IF EXISTS auth_token;

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
    code       VARCHAR(64)  PRIMARY KEY,
    name       VARCHAR(128) NOT NULL,
    scope      VARCHAR(16)  NOT NULL,                      -- SYSTEM / WORKSPACE
    builtin    BOOLEAN      NOT NULL DEFAULT FALSE,
    create_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    update_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by  VARCHAR(64)  NOT NULL DEFAULT 'system',
    update_by  VARCHAR(64)  NOT NULL DEFAULT 'system',
    deleted    BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE sys_role_permission (
    role_code  VARCHAR(64)  NOT NULL,
    permission VARCHAR(128) NOT NULL,                      -- 资源:动作，如 agent:write
    create_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    create_by  VARCHAR(64)  NOT NULL DEFAULT 'system',
    PRIMARY KEY (role_code, permission)
);

INSERT INTO sys_role (code, name, scope, builtin) VALUES
    ('tenant_admin',    '系统管理员', 'SYSTEM',    TRUE),  -- 历史角色码；界面显示「系统管理员」
    ('workspace_admin', '空间管理员', 'WORKSPACE', TRUE),
    ('builder',         '构建者',     'WORKSPACE', TRUE),
    ('member',          '成员',       'WORKSPACE', TRUE),
    ('viewer',          '只读',       'WORKSPACE', TRUE);
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

-- 业务表归属列：workspace_id + owner_id（禁止 tenant_id，ADR-029 第 36 条）
ALTER TABLE chat_conversation ADD COLUMN workspace_id VARCHAR(64);
ALTER TABLE chat_conversation ADD COLUMN owner_id VARCHAR(64);
ALTER TABLE agent_agent       ADD COLUMN owner_id VARCHAR(64);
ALTER TABLE kb_base           ADD COLUMN owner_id VARCHAR(64);
ALTER TABLE flow_draft        ADD COLUMN workspace_id VARCHAR(64);
ALTER TABLE flow_draft        ADD COLUMN owner_id VARCHAR(64);
ALTER TABLE mcp_server        ADD COLUMN workspace_id VARCHAR(64);
ALTER TABLE mcp_server        ADD COLUMN owner_id VARCHAR(64);
ALTER TABLE skill_package     ADD COLUMN workspace_id VARCHAR(64);
ALTER TABLE skill_package     ADD COLUMN owner_id VARCHAR(64);
ALTER TABLE runtime_session   ADD COLUMN owner_id VARCHAR(64);
ALTER TABLE orch_run          ADD COLUMN workspace_id VARCHAR(64);
-- 回填默认空间后由 FND-03 把 workspace_id / owner_id 改为 NOT NULL

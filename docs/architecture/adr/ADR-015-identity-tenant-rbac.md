# ADR-015 — 身份、租户与权限

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：FND-01〜05、FND-07、GOV-01、CHT-01
- 对应设计：设计 A
- 阅读版：[Tioka 推进规划与详细设计](https://claude.ai/artifact/V3wtabWCVSwAC3ewNerFNi)
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

覆盖 FND-01〜05、FND-07、GOV-01、CHT-01。核心决定是：**每个请求和每个后台任务都携带一个不可变的 `Principal`**，数据隔离在持久层自动生效，资源权限在应用层显式校验。

**请求链路**

1. `SecurityFilterChain`（Spring Security 6）识别凭证：本地登录的不透明 token、OIDC 的 JWT、服务账号的 API Key。
2. 从 `X-Workspace-Id` 请求头取当前空间，校验成员关系；缺省取用户默认空间。
3. 组装 `Principal`（含角色展开后的权限点），写入 `PrincipalHolder` 与现有 `TenantContext`。
4. `@RequirePermission` 做功能权限；`ResourceGuard` 做资源权限（是否可编辑这个 Agent）。
5. MyBatis-Plus 的租户拦截器自动为所有业务表加 `tenant_id` 和 `workspace_id` 条件。

未登录白名单只有：登录/刷新、OIDC 回调、健康检查、IM 平台回调（后者用平台签名校验）。

**接口**

```java
public record Principal(
    String tenantId, String userId, String workspaceId,
    PrincipalType type,            // USER, SERVICE, SYSTEM
    Set<String> roles,
    Set<String> permissions,       // 展开后的权限点，如 agent:write
    String authMethod) {}          // password, oidc, api_key

public interface PrincipalHolder {
    Principal current();                         // 无则抛 401
    <T> T runAs(Principal p, Supplier<T> body);  // 后台任务恢复身份
}

@Target(METHOD) @Retention(RUNTIME)
public @interface RequirePermission { String[] value(); }

public interface ResourceGuard {
    void check(String resourceType, String resourceId, AccessLevel level); // VIEW, USE, EDIT, OWNER
    Set<String> visibleIds(String resourceType, AccessLevel level);        // 列表过滤
}
```

异步任务（Runtime Turn、编排 Run、知识 ingest）在创建时把 `Principal` 序列化到任务行的 `run_as_json` 字段，worker 执行前用 `runAs` 恢复。这是审计归属和配额计费在异步路径上成立的前提。

**权限模型**

权限点格式为 `资源:动作`，内置五个角色，租户可自定义角色。资源级共享（如把一个 Agent 分享给某人可用）走 ACL 表，替代现有 `agent_share`。

| 角色 | 范围 | 典型权限 |
| --- | --- | --- |
| tenant\_admin | 租户 | 全部，含成员、角色、模型供应商、审计查询 |
| workspace\_admin | 空间 | 空间成员、配额、资产发布审批 |
| builder | 空间 | agent/flow/kb/skill/mcp 的 read、write、publish |
| member | 空间 | chat:use、使用已发布资产、管理自己的会话 |
| viewer | 空间 | 只读 |

**数据模型**

```sql
CREATE TABLE sys_tenant (id VARCHAR(32) PRIMARY KEY, name VARCHAR(255) NOT NULL, status VARCHAR(16) NOT NULL);
ALTER TABLE sys_workspace ADD COLUMN tenant_id VARCHAR(32) NOT NULL;
CREATE TABLE sys_member (
  workspace_id VARCHAR(64), user_id VARCHAR(32), role_code VARCHAR(64) NOT NULL,
  PRIMARY KEY (workspace_id, user_id));
CREATE TABLE sys_role (code VARCHAR(64), tenant_id VARCHAR(32), name VARCHAR(128) NOT NULL,
  builtin BOOLEAN NOT NULL, PRIMARY KEY (tenant_id, code));
CREATE TABLE sys_role_permission (tenant_id VARCHAR(32), role_code VARCHAR(64), permission VARCHAR(128),
  PRIMARY KEY (tenant_id, role_code, permission));
CREATE TABLE sys_resource_acl (
  resource_type VARCHAR(32), resource_id VARCHAR(64),
  subject_type VARCHAR(16), subject_id VARCHAR(64),   -- user / group / workspace
  level VARCHAR(16) NOT NULL,                         -- VIEW / USE / EDIT / OWNER
  PRIMARY KEY (resource_type, resource_id, subject_type, subject_id));
CREATE TABLE sys_api_key (id VARCHAR(32) PRIMARY KEY, tenant_id VARCHAR(32) NOT NULL,
  workspace_id VARCHAR(64), name VARCHAR(128), key_hash VARCHAR(128) NOT NULL,
  scopes VARCHAR(1024), expires_at TIMESTAMP, last_used_at TIMESTAMP, created_by VARCHAR(32));
ALTER TABLE auth_user ADD COLUMN tenant_id VARCHAR(32) NOT NULL;
ALTER TABLE auth_user ADD COLUMN idp VARCHAR(32);           -- local / oidc:keycloak
ALTER TABLE auth_user ADD COLUMN external_subject VARCHAR(255);
-- password_hash 改存 bcrypt；auth_token 迁到 Redis：access 8 小时、refresh 7 天
```

业务表统一增加 `tenant_id`、`workspace_id`、`owner_id` 三列：`chat_conversation`、`agent_agent`、`kb_base`、`flow_draft`、`mcp_server`、`skill_package`、`runtime_session`、`orch_run` 等。现有 `TenantContext` 用 `Long` 存 ID，而 `sys_workspace.id` 是字符串，统一改为 `String`。

**对外 API**：`POST /api/system/auth/login|refresh|logout`、`GET /api/system/auth/oidc/{idp}/authorize|callback`、`GET /api/system/auth/me`；管理端 `/api/system/members`、`/roles`、`/api-keys`。

**落地顺序**：先加列并回填默认租户和空间 → 上过滤器与 Principal → 持久层拦截器 → 逐个 Controller 加 `@RequirePermission`（用 ArchUnit 检查所有 Controller 方法都有注解或显式 `@PublicApi`）。仓库没有生产数据，可以直接强制启用，不需要宽松模式。

## 修订（2026-11-05）

见 [ADR-029](ADR-029-design-rulings.md) 第 22 条：多租户不是硬需求，每家企业独立部署，`tenant_id` 固定为 `default`，不做租户开通与租户管理界面。

## 修订：记忆与上下文工程调研落地（2026-11-06）

来由：[调研 03](../research/03-Agent记忆与上下文工程调研.md)；按负责人「有选项时按推荐方案直接写设计」的要求落地，见 ADR-029 第 68 条。

- 新增权限点 `memory:space:read`（默认空间成员）、`memory:space:write`、`memory:space:review`（默认空间管理员）、`memory:admin`（默认系统管理员）。用户管理自己的个人记忆不需要权限点；系统管理员不能浏览他人个人记忆正文，调取须走四眼审批（[ADR-050](ADR-050-memory-system.md) 第 6.1 节）。

## 修订：Foundation 基础平台（2026-11-06）

见 [ADR-051](ADR-051-foundation-platform.md)〜[ADR-054](ADR-054-engineering-base-subsystem-microfrontend.md) 与 ADR-029 第 69 条：M4 之后的 F 阶段，系统级角色、用户与组迁出到独立的 Foundation 平台，`PrincipalLoader` 改读授权快照；空间成员、资源 ACL、API Key 仍在 Tioka。M1 按本 ADR 照做，不返工。

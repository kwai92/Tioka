# ADR-026 — Foundation 余项

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：FND-08〜11
- 对应设计：设计 L
- 阅读版：[Tioka 推进规划与详细设计](https://claude.ai/artifact/V3wtabWCVSwAC3ewNerFNi)
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

覆盖 FND-08〜11 与 S9 的其余部分。核心决定是：**身份和组织以客户 IdP 为准，Tioka 只存映射和授权**；本地账号只保留应急管理员，生产可关闭。外部系统的用户令牌、客户自有的密钥库和中间件，都通过适配器接入。

## 外部身份映射

`sys_external_identity` 统一记录「外部身份 → 平台用户」：OIDC subject、SCIM externalId、飞书 open\_id / union\_id、钉钉 userId 等。绑定方式两种：管理员配置「按工号或已验证邮箱自动关联」；否则用户在 IM 里点绑定链接，用该 IM 的 OAuth 登录证明身份后关联。**不允许仅凭名字匹配**。

## 组织同步（FND-09）

- Tioka 作为 SCIM 2.0 服务方提供 `/scim/v2/Users`、`/scim/v2/Groups`，由 Keycloak、Entra ID、Okta 推送；不支持 SCIM 的来源（飞书通讯录、LDAP、客户 HR 接口）由 `OrgDirectoryAdapter` 定时拉取。
- 部门作为有层级的组存储。管理员配置「组 → 空间角色」映射规则，成员变更后角色自动计算。手工授予的角色单独标记，同步不会覆盖。
- **离职处理**：用户被停用时，吊销登录令牌和代用户令牌、取消进行中的 Turn（定案 6）；其名下的资产转交给空间管理员待处理队列，已发布的资产继续可用。

## 登录方式（FND-10）

OIDC 在 M1（设计 A）；SAML 2.0 用 Spring Security SAML2，LDAP 用绑定认证，都在 M4；CAS 等其他协议由 `IdentityProviderAdapter` 接入。所有方式最终都产生同一种平台令牌和 `Principal`。

## 代用户令牌保管（FND-08）

```java
public interface TokenVault {
    TokenResult get(String userId, String provider, Set<String> scopes);  // 过期自动刷新
    String authorizationUrl(String userId, String provider, Set<String> scopes, String returnTo);
    void revokeAll(String userId);
}
sealed interface TokenResult {
    record Granted(String accessToken, Instant expiresAt) implements TokenResult {}
    record AuthorizationRequired(String url) implements TokenResult {}
}
```

工具拿到 `AuthorizationRequired` 时，把授权链接作为工具结果返回，前端渲染成「去授权」按钮，而不是改用应用身份。刷新时按 (用户, 提供方) 加锁，避免并发刷新使 refresh token 失效。

## 密钥与加密

采用信封加密：每个租户一把数据密钥，由主密钥加密保存；主密钥来自 `SecretStoreAdapter`（本地密钥文件、HashiCorp Vault、客户 KMS）。**生产环境缺少主密钥时直接启动失败**，去掉现在回退到开发密钥的行为。代用户令牌、供应商 API Key、各类回调密钥都用同一套。主密钥轮换只需重新加密数据密钥。

## 适配器与插件

S9 列出的九类 SPI（身份、组织、IM、业务系统、审计外送、通知、密钥、对象存储、工作盘）统一由插件宿主加载：从插件目录读取 jar，用独立类加载器隔离，校验签名和所声明的 SPI 版本，不兼容则拒绝加载。各类适配器实例的配置存在登记表里，密钥只存引用。

## 部署形态（FND-11）

| 项 | 定案 |
| --- | --- |
| 进程角色 | 一个镜像，用 `tioka.roles` 启用角色：`api`（HTTP 与 SSE）、`worker`（Turn、Flow 节点、入库任务、MQ 消费）、`scheduler`（outbox 中继、定时器、回收器、审计封印，靠 Redis 锁保证只有一个实例在跑）。小部署一个进程全开 |
| 中间件 | PostgreSQL（含 pgvector）、Redis、RocketMQ、MinIO 均可用客户自备的；Redis key 与 MQ topic 支持前缀，可与客户其他系统共用 |
| 数据库变更 | 全部改用 Flyway 迁移脚本，废弃手工维护的 `schema.sql`；启动时由拿到锁的实例执行迁移 |
| Helm | 按角色拆分 Deployment，api 与 worker 各自水平扩容；中间件与 Keycloak 为可选子 chart |
| 空气隔离 | 安装包含全部镜像的 tar、chart、校验和、安装与升级脚本、离线的 embedding 与 rerank 模型镜像（可选）；安装脚本先做环境自检 |
| Docker Compose | 用于演示和小团队，与 Helm 共用同一套配置项 |

## 数据模型

```sql
CREATE TABLE sys_external_identity (provider VARCHAR(64), external_id VARCHAR(255), user_id VARCHAR(32) NOT NULL,
  tenant_id VARCHAR(32) NOT NULL, linked_by VARCHAR(16) NOT NULL, linked_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (provider, external_id));
CREATE TABLE sys_group (id VARCHAR(32) PRIMARY KEY, tenant_id VARCHAR(32) NOT NULL, external_id VARCHAR(255),
  source VARCHAR(32) NOT NULL, parent_id VARCHAR(32), name VARCHAR(255) NOT NULL);
CREATE TABLE sys_group_member (group_id VARCHAR(32), user_id VARCHAR(32), PRIMARY KEY (group_id, user_id));
CREATE TABLE sys_group_role_rule (group_id VARCHAR(32), workspace_id VARCHAR(64), role_code VARCHAR(64),
  PRIMARY KEY (group_id, workspace_id, role_code));
ALTER TABLE sys_member ADD COLUMN source VARCHAR(8) NOT NULL DEFAULT 'MANUAL';   -- MANUAL / RULE
CREATE TABLE sys_user_token (user_id VARCHAR(32), provider VARCHAR(64), scopes VARCHAR(1024),
  access_cipher TEXT NOT NULL, refresh_cipher TEXT, expires_at TIMESTAMPTZ, PRIMARY KEY (user_id, provider));
CREATE TABLE sys_tenant_key (tenant_id VARCHAR(32) PRIMARY KEY, wrapped_dek TEXT NOT NULL, kek_ref VARCHAR(255) NOT NULL,
  rotated_at TIMESTAMPTZ NOT NULL);
CREATE TABLE sys_adapter_registration (id VARCHAR(32) PRIMARY KEY, tenant_id VARCHAR(32), kind VARCHAR(32) NOT NULL,
  impl VARCHAR(255) NOT NULL, plugin_sha256 VARCHAR(64), config_json TEXT, status VARCHAR(16) NOT NULL);
-- scim_user 废弃，改为写 auth_user + sys_external_identity
```

## 修订：供应商凭证（2026-11-06）

见 [ADR-042](ADR-042-provider-credentials-model-params.md) 与 ADR-029 第 63 条：供应商 API Key、MCP 令牌、数据源凭证统一使用 FND-08 的 `CredentialCipher`（AES-256-GCM，带 AAD 与密钥版本），替换现有 `LocalSecretStore` 的 ECB 加密，旧密文由迁移任务重新加密。

## 修订：Foundation 基础平台（2026-11-06）

见 [ADR-051](ADR-051-foundation-platform.md)〜[ADR-053](ADR-053-foundation-common-services.md) 与 ADR-029 第 69 条：外部身份映射、组织同步（FND-09）、SAML 与 LDAP（FND-10）移到独立的 Foundation 平台，由 BAS 工作项实现；组 → 空间角色规则改为引用 Foundation 组织单元、岗位与应用角色；离职处理改由 `fdn.person.disabled` 触发。代用户令牌、凭证加密、插件宿主仍在 Tioka。

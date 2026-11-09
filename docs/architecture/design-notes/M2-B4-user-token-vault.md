# M2 残余收口批次 B-4：FND-08 代用户令牌保管 UserTokenVault（ADR-021 / M2-03）实现覆盖说明

> 批次：M2 残余收口批次 B-4（接 B-2 / B-3）
> 分支：`feature/m2-b4-token-vault`（基线 main = `adc51c9`）
> 收口范围：FND-08 UserTokenVault 主体（M2-00 FND-08 行；V145 旧 ECB 重加密不在本批，归 FND-08 收尾项）
> 硬约束遵守：迁移仅 `V141__m2_user_credential.sql`（flyway out-of-order，ADR-029 第 33 条）；不改 `deploy/compose.yml`；不引新运行时依赖；MQ 信封契约不动；`sys_user_credential` 契约无空间隔离列，未越权加列；全程无 tenantId

## 一、范围 → 实现映射

| 项 | 实现 |
|------|------|
| 契约迁移 | V141：`sys_user_credential` 全契约列照抄（UNIQUE(user_id, provider) + status CHECK ACTIVE/REVOKED/REFRESH_FAILED），追加 `idx_sys_user_credential_user_provider` 辅助索引；无空间隔离列 → 登记进 `tioka.persistence.workspace-scope-exempt-tables`（PersistenceProperties 默认清单，deny-by-default 拦截器要求） |
| 接口 | connector-api `UserTokenVault` 最小扩展：新增 `store(userId, provider, accessToken, refreshToken, expiresInSeconds, scopes)`（回调 / 刷新落库入口，Javadoc 同步）；原三方法 `accessToken / authorizeUrl / revoke` 未动 |
| 三层持久化 | `UserCredentialDO`（@TableName，继承 `TechnicalRecordDO` + 自带 create_at/update_at——契约无 create_by/update_by/deleted 列，先例如 OrchRunDO/CfgVersionDO）→ `UserCredentialMapper`（仅 BaseMapper）→ `UserCredentialRepository`（application 层禁直持 Mapper，ArchUnit 守护） |
| `accessToken` | 查 ACTIVE 行 → AES-GCM 解密 access_token；剩余寿命 < 5 分钟且有 refresh token → `OAuthTokenClient.refresh` 刷新，成功 upsert（key_version 按当前 cipher；provider 未下发新 refresh token 则保留旧值），失败置 `REFRESH_FAILED` 返回空；REVOKED / 无行返回空。TIMESTAMPTZ 按 UTC 语义 LocalDateTime（ENG-11 / UtcLocalDateTimeTypeHandler） |
| `authorizeUrl` | 按 `tioka.connector.oauth.<provider>.{auth-url,token-url,client-id,client-secret,redirect-uri,scopes}` 构造标准 OAuth2 授权 URL；缺失配置抛带可行动提示的 IllegalStateException；state 含 userId/provider/returnTo/nonce |
| state 签名 | `OAuthStateCodec`：Base64url JSON + HMAC-SHA256；签名密钥由既有主密钥 `tioka.credential.master-key` 派生（SHA-256(master\|"oauth-state")，与 `AesGcmCredentialCipher` 同源同开发兜底，一致方案选自仓库既有做法）；验签常数时间比较（`MessageDigest.isEqual`）+ 10 分钟时效 |
| 回调端点 | connector-biz `OAuthCallbackController`：`GET /api/connector/oauth/callback?provider=&code=&state=`（@PublicApi，provider 浏览器重定向无会话头）→ 验 state（含 provider 参数一致性）→ 授权码换 token（TokenExchange 走同一 `OAuthTokenClient`）→ `vault.store` 加密落库 → 302 回 returnTo（限定站内相对路径防开放重定向）；换 token 失败降级 302 带 `oauth_error=exchange_failed`；不依赖 CON-02 |
| revoke | 置 status=REVOKED，保留行不物理删除 |
| 装配 | `ConnectorAutoConfiguration` 加 `@EnableConfigurationProperties(OAuthProviderProperties.class)`；Vault/Repository/Mapper/Codec/HttpOAuthTokenClient/Controller 经组件扫描装配（与 connector-biz 现行模式一致） |

## 二、实现决策

1. **刷新窗口**：`REFRESH_AHEAD_SECONDS = 5 * 60`，与接口 Javadoc「剩余不足 5 分钟自动刷新」一致；无 refresh token 的过期行不拦截解密（是否重授权由 provider 侧决策），本批如实登记。
2. **Token 客户端可替换**：`OAuthTokenClient`（`exchangeCode` / `refresh`）接口注入，默认 `HttpOAuthTokenClient`（java.net.http + Spring 注入 ObjectMapper，遵守 ENG-05 禁 new ObjectMapper）；测试 mock 接口。
3. **provider 标识**：沿用接口 Javadoc 约定 `feishu:{appId}` 形态，作为配置 map key 与表 provider 列值。
4. **AAD 语义**：`CredentialCipher.encrypt(plaintext, credentialId=rowId, ownerId=userId)`，与 F19 约定一致。

## 三、测试清单（全部离线，纯 JUnit5 + Mockito）

| 测试 | 结果 | 覆盖 |
|------|------|------|
| `DefaultUserTokenVaultTest`（connector-biz） | 10/10 | 未授权返回空 / 剩余>5min 直返缓存 / 剩余<5min 刷新成功并 upsert 密文 / 刷新失败置 REFRESH_FAILED 返回空 / revoke 后返回空 / revoke 保留行 / 加解密 roundtrip（真实 AesGcmCredentialCipher + AAD 反例）/ store upsert 冲突更新（新 insert + 旧行回 ACTIVE、refresh token 保留）/ authorizeUrl 构造与 state 验签回环、篡改拒绝、缺配置明确异常 / 无 refresh token 过期不拦截 |
| `M2B4VaultContractTest`（tioka-server，nio 源码守护） | 6/6 | V141 存在且含契约列 + status CHECK + UNIQUE、无空间隔离列无 tenantId / 三层封装齐备（TechnicalRecordDO、BaseMapper、@Repository）/ vault 语义关键字段（刷新窗口、降级、cipher、Ids.newId）/ application 层无 Mapper import / 回调控制器（@PublicApi、state 验签、站内 returnTo、store 接线）/ state HMAC 派生自主密钥 |
| `PersistenceArchTest` + `TiokaArchitectureTest`（tioka-server） | 6/6 + 11/11 | 无新违例：workspace 豁免登记生效（deny-by-default）、Mapper 直持、控制器权限注解、ObjectMapper/UUID 规则均通过 |

## 四、未覆盖项（如实登记）

- **真实 provider 联测**：`HttpOAuthTokenClient` 的 HTTP 细节（form 编码 / JSON 解析 / 非 2xx 降级）仅离线单测覆盖端点语义；飞书等真实授权码 / 刷新联测归 CI IT（需 Docker/PG + provider stub）。
- **回调控制器 HTTP 层测试**：本批未引入 @SpringBootTest（约束禁用），控制器逻辑经契约测试源码守护 + vault 单测覆盖；request 参数绑定归 CI IT。
- **V145 旧 ECB 密文重加密**：FND-08 收尾项（Java 迁移），不在本批。
- **refresh_expires_at**：主流 provider 授权/刷新响应多不下发刷新令牌有效期，本实现置 null（列可空）；待具体 provider 接入时补语义。
- **多密钥版本解密**：`AesGcmCredentialCipher` 当前仅支持当前版本密文，主密钥轮换重加密经 `CredentialReencryptRegistry` 登记（M3 范围）。

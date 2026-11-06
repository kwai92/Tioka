# OIDC / Keycloak 本地与闸门环境

ENG-04b 基座。生产密钥走环境变量 / 密钥库，禁止提交真实密钥。

## 本地 Compose

```bash
docker compose -f deploy/compose.yml up -d postgres redis keycloak
```

- 管理控制台：<http://localhost:8180>（`admin` / `$KEYCLOAK_ADMIN_PASSWORD`，默认 `admin`）
- Realm：`tioka`（导入自 `tioka-server/src/test/resources/keycloak/tioka-realm.json`）
- Issuer：`http://localhost:8180/realms/tioka`

## 测试客户端

| 项 | 值 |
| --- | --- |
| 客户端 `tioka` | 机密客户端，支持授权码 + PKCE(S256) |
| client-secret | `tioka-test-client-secret`（**仅本地/测试**；生产用 `TIOKA_OIDC_SECRET`） |
| redirect_uri | `http://127.0.0.1:5173/login/callback` |
| 受众客户端 | `tioka-api` |

## 测试用户（realm 导入）

| 用户 | 密码 | 组 |
| --- | --- | --- |
| alice | `AlicePass1!` | `/tioka/admins` |
| bob | `BobPass1!` | `/tioka/sales` |
| carol | `CarolPass1!` | （无） |

以上密码仅用于本地与 CI 闸门，不得用于生产。

## 应用配置（当前 M0 OIDC 适配器）

```yaml
tioka:
  auth:
    oidc-enabled: true
    oidc-issuer: http://localhost:8180/realms/tioka
    oidc-client-id: tioka
    oidc-client-secret: ${TIOKA_OIDC_SECRET:tioka-test-client-secret}
    oidc-redirect-uri: http://127.0.0.1:5173/login/callback
```

FND-05 将切换为 `tioka.auth.oidc.providers.keycloak.*` 配置树与新回调路径；届时同步更新本页与 `KeycloakLogin`。

## 闸门工具

- `KeycloakGateSupport`：Testcontainers Keycloak 单例
- `KeycloakLogin`：HttpClient 走授权码表单登录，经 Tioka `/api/system/auth/oidc/callback` 换 `tka_` 令牌

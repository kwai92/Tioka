# OIDC / Keycloak 本地与闸门环境

ENG-04b 基座 + FND-05 身份联邦。生产密钥走环境变量 / 密钥库，禁止提交真实密钥。

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
| redirect_uri（IdP → Tioka） | `http://127.0.0.1:<tioka-port>/api/system/auth/oidc/keycloak/callback`（本地固定端口时在 realm / Client 中登记；闸门 IT 经 Admin API 动态注册） |
| 受众客户端 | `tioka-api`（JWT 资源服务器 `aud`） |

## 测试用户（realm 导入）

| 用户 | 密码 | 组 |
| --- | --- | --- |
| alice | `AlicePass1!` | `/tioka/admins` |
| bob | `BobPass1!` | `/tioka/sales` |
| carol | `CarolPass1!` | （无） |

以上密码仅用于本地与 CI 闸门，不得用于生产。

## 应用配置（FND-05）

```yaml
tioka:
  auth:
    oidc:
      frontend-base-url: http://127.0.0.1:5173
      providers:
        keycloak:
          issuer-uri: http://localhost:8180/realms/tioka
          client-id: tioka
          client-secret: ${TIOKA_OIDC_SECRET:tioka-test-client-secret}
          scopes: [openid, profile, email]
          groups-claim: groups
          auto-provision: true
          group-role-mappings:
            - { group: /tioka/admins, system-role: system_admin }
            - { group: /tioka/sales, workspace: ws_sales, role: member }
      resource-server:
        enabled: true
        audiences: [tioka-api]
```

流程：浏览器 `GET /api/system/auth/oidc/{idp}/authorize?redirect=/chat` → IdP → `GET .../callback` → 写 `tioka_rt` Cookie → 302 `/login/callback?redirect=...`（URL 不带令牌）→ 前端调 `/api/system/auth/refresh` 取 `tka_` 访问令牌。

## 本地前后端同源

`SameSite=Strict` 刷新 Cookie 在「前端 5173 / 后端 8080」跨端口会丢。本地开发请用 Vite 代理 `/api` 到后端，使浏览器只访问同源；生产同域部署。

## 闸门工具

- `KeycloakGateSupport`：Testcontainers Keycloak 单例
- `KeycloakLogin`：从 Tioka authorize 入口走表单登录，经 callback + `/refresh` 换 `tka_` 令牌

## 夜间真实模型机密（ENG-04b）

`nightly-real-model.yml` 在 GitHub Actions Secrets 中读取：

| Secret | 用途 |
| --- | --- |
| `TIOKA_REAL_MODEL_BASE_URL` | 真实模型 HTTP 基址 |
| `TIOKA_REAL_MODEL_API_KEY` | API Key |
| `TIOKA_REAL_MODEL_MODEL` | 模型名 |

缺任一则 job 跳过（非失败）。配置后：`gh workflow run nightly-real-model.yml`。走查清单见 `m1-closure-walkthrough.md`。

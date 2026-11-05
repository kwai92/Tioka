# OIDC / Keycloak 联调片段

Tioka 联邦登录由 `tioka.auth.oidc-*` 驱动（`LocalOidcIdPAdapter`，默认关闭）。

## Compose 片段（Keycloak）

```yaml
services:
  keycloak:
    image: quay.io/keycloak/keycloak:25.0
    command: ["start-dev", "--http-port=8081"]
    environment:
      KEYCLOAK_ADMIN: admin
      KEYCLOAK_ADMIN_PASSWORD: admin
    ports:
      - "8081:8081"
```

启动后：

1. 建 Realm（如 `tioka`）与 Client（confidential，`Standard flow`）。
2. Valid redirect URI 填 `http://localhost:5173/login`（与下方 `oidc-redirect-uri` 一致）。
3. 复制 Client ID / Secret。

## 指向现有配置项

见 `tioka-server/src/main/resources/application-ext.yml`：

| 配置 | 环境变量 | 说明 |
| --- | --- | --- |
| `tioka.auth.oidc-enabled` | `TIOKA_OIDC_ENABLED` | 默认 `false` |
| `tioka.auth.oidc-issuer` | `TIOKA_OIDC_ISSUER` | 如 `http://localhost:8081/realms/tioka`（`mock://` 走沙箱假联邦） |
| `tioka.auth.oidc-client-id` | `TIOKA_OIDC_CLIENT_ID` | Keycloak Client ID |
| `tioka.auth.oidc-client-secret` | `TIOKA_OIDC_CLIENT_SECRET` | Client Secret |
| `tioka.auth.oidc-redirect-uri` | `TIOKA_OIDC_REDIRECT_URI` | 默认 `http://localhost:5173/login` |
| `tioka.auth.oidc-token-url` | （可选，代码 `@Value`） | 留空则按 issuer 推导 token endpoint |

示例：

```bash
export TIOKA_OIDC_ENABLED=true
export TIOKA_OIDC_ISSUER=http://localhost:8081/realms/tioka
export TIOKA_OIDC_CLIENT_ID=tioka
export TIOKA_OIDC_CLIENT_SECRET=xxxxx
```

API：

- `GET /api/system/auth/oidc/start` → `authorizationUrl`
- `POST /api/system/auth/oidc/callback` `{ "code", "state" }`

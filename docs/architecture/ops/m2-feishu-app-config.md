# M2 飞书应用配置清单（企业自建应用 / 自定义机器人）

> 状态：2026-11-10（M2 残余收口批次 B-3）。
> 事实来源：`application.yml` tioka.im.feishu 段、`application-local.yml` tioka.feishu 段、
> `FeishuImChannelAdapter`（connector-biz）、`DefaultEgressGuard`（system-biz）。

## 1. 配置项清单（tioka.feishu.*）

| 配置键 | 环境变量 | 默认值 | 用途 |
| --- | --- | --- | --- |
| `tioka.feishu.api-base` | `TIOKA_FEISHU_API_BASE` | `https://open.feishu.cn` | OpenAPI 域名（私有化/测试环境可换） |
| `tioka.feishu.app-id` | `TIOKA_FEISHU_APP_ID` | 空 | 企业自建应用 App ID |
| `tioka.feishu.app-secret` | `TIOKA_FEISHU_APP_SECRET` | 空 | 应用 App Secret |
| `tioka.feishu.encrypt-key` | `TIOKA_FEISHU_ENCRYPT_KEY` | 空 | 回调事件加密 key |
| `tioka.feishu.verification-token` | `TIOKA_FEISHU_VERIFICATION_TOKEN` | 空 | 回调校验 token |
| `tioka.feishu.receive-id-type` | — | `chat_id` | 发消息的 receive_id 类型（`chat_id`/`open_id` 等） |
| `tioka.feishu.webhook-url` | — | 空 | 自定义机器人 webhook 地址 |

注意：`app-id/app-secret/api-base/encrypt-key/verification-token` 声明在 application.yml 的
`tioka.im.feishu` 下（第 139〜145 行），而 `receive-id-type/webhook-url` 目前只在
application-local.yml 的 `tioka.feishu` 下出现；`FeishuImChannelAdapter` 读取的统一前缀是
**`tioka.feishu.*`**（@Value 第 45〜49 行），生产配置时务必使用 `tioka.feishu.*` 口径。
（`tioka.im.feishu.*` 与 `tioka.feishu.*` 两个前缀并存是现状，收敛工作未落地。）

## 2. 发送优先级（FeishuImChannelAdapter）

所有 IM 发送**先落 outbox（`im_outbox`，channel=feishu）再尝试外发**，三条路径：

1. 配置了 `app-id` + `app-secret` → OpenAPI：
   - `POST {api-base}/open-apis/auth/v3/tenant_access_token/internal` 取 token（缓存至过期前 60s，默认 expire 7200s）；
   - `POST {api-base}/open-apis/im/v1/messages?receive_id_type={receive-id-type}`，
     text → msg_type=text，card → msg_type=interactive；
   - 响应 `code != 0` 视为失败抛异常。
2. 否则配置了 `webhook-url` → 自定义机器人协议（msg_type=text / interactive）。
3. 两者都空（CI / 本地默认）→ 仅 outbox，不外发。

## 3. webhook 域名白名单（适配器内置，assertAllowedWebhook）

- 允许 host：`localhost`、`127.0.0.1`、`*.feishu.cn`、`feishu.cn`、`*.larksuite.com`、
  `larksuite.com`、`*.feishu.net`（含 `open.feishu.cn` / `open.larksuite.com`）；
- 生产域名（feishu.cn / larksuite.com / feishu.net）必须 **https**（localhost 除外）；
- 其余 host 直接抛 `feishu webhook host not allowed`。

## 4. 与 EgressGuard 出网白名单的关系

- `FeishuImChannelAdapter` 的所有 HTTP（token + 发消息 + webhook）都经
  `EgressClient.send()`（`com.tioka.system.api.spi.EgressClient`），实现为
  `DefaultEgressGuard`（同一类实现 `EgressGuard.assertAllowed`）。
- 当前 local / prod 配置（application.yml / application-local.yml / application-prod.yml）：
  `tioka.egress.mode=OPEN` + `allow-hosts: []`，即**不启用 allow-list**，出网放行，
  但仍拒绝 `deny-cidrs: ["169.254.0.0/16", "100.100.100.200/32"]`（云元数据端点）。
- 若生产切到 `ALLOW_LIST` / `STRICT` 模式，须把 `api-base` 与 `webhook-url` 的 host
  （如 `open.feishu.cn`、`open.larksuite.com`）加入 `tioka.egress.allow-hosts`，
  否则出网被拒并产生 `egress.denied` 审计事件（含 url 前缀 64 字符）。
- 适配器自身的域名白名单（第 3 节）与 EgressGuard 是**两层独立校验**，先适配器（仅 webhook 路径）
  后 guard（所有路径）；两层都要通过。

## 5. 企业自建应用开通步骤（M2-00 Q4 口径）

1. 飞书开放平台创建企业自建应用，记录 App ID / App Secret。
2. 开通权限：机器人、im:message（发送消息与卡片）、消息与卡片回调、通讯录读、云文档读。
3. 配置事件回调地址（公网可达），记录 Encrypt Key 与 Verification Token。
4. 应用发布可见范围；测试租户群里添加机器人。
5. 环境注入：

```
TIOKA_FEISHU_API_BASE=https://open.feishu.cn
TIOKA_FEISHU_APP_ID=cli_xxx
TIOKA_FEISHU_APP_SECRET=***
TIOKA_FEISHU_ENCRYPT_KEY=***
TIOKA_FEISHU_VERIFICATION_TOKEN=***
```

6. 验证：群里 @机器人有回应（M2-00 第 3 节 Q4 的验收口径）。

## 6. 验证步骤（部署后）

1. `GET /actuator/health` 确认实例健康。
2. 走一条 notice / 审批卡片链路，查日志 `[feishu] outbox {...}` 确认 outbox 落库。
3. OpenAPI 路径：日志 `[feishu] openapi status=200`；webhook 路径：`[feishu] webhook status=200`。
4. 两者都未配置时确认仅有 outbox 日志（CI/本地语义），不误判为故障。
5. token 失效类错误（`feishu token failed`）检查 app-id/app-secret；非 0 code 检查应用权限与
   receive_id_type 是否与目标会话匹配。

## 7. 未落地项登记

- [ ] `tioka.im.feishu.*`（application.yml）与 `tioka.feishu.*`（适配器实际读取）前缀并存，待收敛。
- [ ] 回调入口（事件订阅接收端）随 CON-02 入站链路落地；OBS-04a 台账已登记，im/webhook 入口接线未完成。
- [ ] webhook 签名验证（自定义机器人 secret 签名）未实现，当前仅域名白名单。

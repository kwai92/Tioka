# S12 — MCP 管理（含 MCP over HTTP）详细设计

> 模块：`tioka-module-mcp`（管理面）+ Runtime/Connector 侧 Client 执行  
> 协议：Model Context Protocol；传输：**HTTP（含 Streamable HTTP / SSE）**、stdio（可选）、未来可扩  
> 对标：MCP 规范 + LibreChat/Langflow/WeKnora 的 MCP 接入体验

---

## 1. 定位与边界

**管理面**：登记 MCP Server、传输与认证、工具发现、授权给 Agent/空间、健康与刷新。  
**执行面**：Runtime 经 McpToolProvider 调工具（出站在 connector/runtime 宿主）。

| 边界内 | 边界外 |
| --- | --- |
| Server 生命周期、HTTP 配置、OAuth/头认证、工具目录、ACL | 工具业务逻辑本身 |
| 把 Tioka 能力暴露为 **MCP Server**（出口） | 替代 S10 模型路由 |

---

## 2. 完整能力地图

### 2.1 作为 MCP Client（接入外部 Server）

| 能力 | 说明 | 波次 |
| --- | --- | --- |
| **HTTP 传输** | MCP over HTTP：Streamable HTTP / SSE 长连接；Base URL、路径 | W1–W2 |
| **stdio 传输** | 本地进程命令行（私有化侧车） | W2 |
| **认证** | Bearer、自定义 Header、OAuth2/OIDC（客户 IdP，S9） | W1–W2 |
| **TLS** | 企业 CA、双向 TLS | W2 |
| **工具发现** | `tools/list` 拉取、缓存、手动刷新、定时刷新 | W1 |
| **工具过滤** | 按 Server 全开 / allowlist / denylist | W1 |
| **资源/Prompts** | resources、prompts 列表与读取（若 Server 支持） | W3 |
| **健康检查** | 连通、初始化握手、延迟、连续失败熔断 | W2 |
| **多 Server** | 空间内 N 个；工具名冲突前缀策略 | W1 |
| **版本钉扎** | 记录 protocol version、serverInfo | W2 |
| **审计** | 谁添加、谁授权给哪个 Agent、调用量 | W2 |
| **密钥** | Header/OAuth 密钥进 SecretStore | W1 |

### 2.2 作为 MCP Server（对外暴露 Tioka）

| 能力 | 说明 | 波次 |
| --- | --- | --- |
| HTTP MCP 出口 | 暴露 KB 检索、Flow 运行、受控 Agent 工具 | W4 |
| API Key / OIDC 保护 | 调用方身份映射到 Workspace | W4 |
| 工具范围 | 按 Key 的 scope 裁剪工具集 | W4 |
| 限流 | 按 Key/空间 | W4 |

### 2.3 管理体验

| 能力 | 说明 | 波次 |
| --- | --- | --- |
| 配置台向导 | URL、鉴权、测试连接、预览工具 | W1 |
| 批量导入 | JSON/YAML 声明多 Server | W3 |
| Agent 挂载 UI | 在 Agent 编辑页勾选 Server/工具 | W1 |
| 调用试跑 | 管理面选工具填参试调用（沙箱权限） | W2 |

---

## 3. 领域模型

| 实体 | 说明 |
| --- | --- |
| **McpServer** | 逻辑 Server：名称、空间、状态 |
| **McpTransport** | 类型=`HTTP`\|`STDIO`；HTTP：baseUrl、headersRef、ssePath…；STDIO：command/args/envRef |
| **McpAuth** | none/bearer/oauth；secretRef |
| **McpToolDescriptor** | name、description、inputSchema、cachedAt |
| **McpServerVersion** | 发现快照 |
| **McpBinding** | Agent Spec `tools.mcp[]`：serverId + allowlist（表 `mcp_grant` / GrantApi 已废止，见 47-GL） |
| **McpOutboundEndpoint** | Tioka 作为 Server 的出口配置 |

### HTTP 传输配置示例（逻辑）

```yaml
transport:
  type: HTTP
  baseUrl: https://mcp.customer.local
  protocol: streamable-http   # 或 sse
  timeoutMs: 60000
auth:
  type: bearer
  secretRef: secret://mcp/customer-token
```

---

## 4. 组件结构

```
mcp-biz
├── registry/     Server CRUD
├── transport/    HttpClient、StdioLauncher
├── discover/     tools/list、schema 规范化
├── grant/        授权与冲突检测（工具重名）
├── health/       探针与熔断状态
├── outbound/     Tioka MCP Server 出口
└── probe/        管理面试调用
```

执行路径：Runtime **ToolGateway** ← AgentSpec `tools.mcp`（McpBinding）← McpToolProvider ← transport（ADR-014 D3；禁止绕过 Gateway）。

---

## 5. 生命周期

```
REGISTERED → CONNECT_TEST → DISCOVERED → ENABLED
ENABLED → REFRESH_TOOLS（定时/手动）
连续失败 → DEGRADED → DISABLED（可自动或人工）
```

Agent 发布时固化其 Grant 快照到 AgentVersion（可选跟随最新）。

---

## 6. 管理 API

| API | 说明 |
| --- | --- |
| McpServerApi | CRUD、testConnection |
| McpDiscoverApi | refreshTools、listTools |
| （废止）McpGrantApi | 绑定进 AgentSpec；管理面用 ACL USE |
| McpHealthApi | status、metrics |
| McpOutboundApi | 出口开关与 scope |
| McpProbeApi | invokeTool（管理试跑） |

---

## 7. MQ

`tioka-mcp`：`server.enabled` `tools.refreshed` `server.degraded` `grant.changed`  
Runtime 订阅刷新工具表。

---

## 8. 数据模型

`mcp_server` `mcp_transport` `mcp_auth_ref`  
`mcp_tool` `mcp_tool_snapshot`  
`mcp_outbound` `mcp_health`（`mcp_grant` 已删，见 V125）

工具 schema 存 JSONB；大 schema 可外置对象存储。

---

## 9. 安全

- SSRF：HTTP baseUrl 必须过出站白名单（企业网段策略）。  
- 禁止 Agent 配置任意 URL 绕过登记（只能选已启用 Server）。  
- OAuth token 刷新在管理面完成，执行面只读 token。  
- Probe 调用记审计且默认只读工具可开。

---

## 10. 依赖

S9 SecretStore/RBAC/白名单；S11 Agent 挂载；S1 执行；S6 可复用 HTTP 出站池。

---

## 11. 失败与幂等

- refresh 失败保留上一份工具快照并标 stale。  
- 调用超时按 ToolResult 失败返回，由模型决定是否重试。  
- 工具改名：旧 Grant allowlist 失效告警。

---

## 12. 可观测

发现延迟、调用 QPS、错误码、熔断状态、每工具成本（若计量）。

---

## 13. 测试验收

| 用例 | 期望 |
| --- | --- |
| HTTP MCP 握手+list | 工具入库 |
| Bearer 错误 | test 失败不 enable |
| Allowlist | Agent 看不到未授权工具 |
| 刷新变更 | MQ 后 Runtime 工具更新 |
| SSRF | 内网违规 URL 拒绝 |
| 出口 MCP | 外部 Client 用 Key 调 kb.search |

---

## 14. 波次

| 波次 | 内容 |
| --- | --- |
| W1 | HTTP 接入、发现、Grant、Agent 挂载 |
| W2 | OAuth、健康熔断、试跑、stdio |
| W3 | resources/prompts、批量导入 |
| W4 | Tioka 作为 MCP Server 出口 |

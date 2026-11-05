# ADR-021 — Connector、IM 与 MCP 管理

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：CON-02〜07
- 对应设计：设计 G
- 阅读版：[Tioka 推进规划与详细设计](https://claude.ai/artifact/V3wtabWCVSwAC3ewNerFNi)
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

覆盖 CON-02〜07 与 S6、S12。核心决定有三条：**所有出网请求只能经 Connector 的 `EgressClient`**（白名单、超时、审计一处实现）；**IM 只是 Chat 和审批的另一个入口**，不自己跑 Agent；**MCP 工具和业务系统工具都注册进 ToolGateway**，和内置工具受同一套权限、审批、审计约束。

## IM 入站对话（CON-02）

1. 平台回调打到 `POST /api/connector/im/{channel}/{bindingId}/events`（白名单接口，只靠平台签名鉴权）。验签解密、按平台事件 ID 去重，**3 秒内先应答**，再异步处理。
2. 外部用户按 `sys_external_identity` 映射到平台用户。未绑定的用户收到一条带绑定链接的回复，不执行任何操作。
3. 群聊只响应 @机器人的消息；私聊全部响应。IM 会话（群 + 话题）映射到一个 Chat 会话，默认使用绑定上配置的 Agent。
4. 调 Chat 发送（origin=im），订阅 Turn 事件，把回答渲染为流式卡片：更新节流为每秒一次，结束时写入完整内容和引用。

## 卡片回调与审批（CON-03）

审批卡片里的按钮携带 `approvalId` 和一个签名令牌（对 approvalId、接收人、过期时间做 HMAC），防止伪造。回调时把点击人映射为平台用户，校验其是否有权审批，再以该用户身份调 `decideApproval`，并把卡片更新为「已由某人处理」。同一审批在 Web 和 IM 任一端处理后，另一端同步更新。

## IM 渠道 SPI（CON-06）

```java
public interface ImChannelAdapter {          // 替代 system-api 里现有的 ImChannelAdapter
    String channel();                         // feishu / dingtalk / wecom / custom:{name}
    InboundMessage verifyAndParse(InboundRequest req);      // 验签、解密、去重键
    CardAction parseCardAction(InboundRequest req);
    OutboundRef send(ImTarget target, ImMessage message);   // text / markdown / card
    void update(OutboundRef ref, ImMessage message);        // 流式卡片、状态更新
    ExternalUser lookupUser(String externalUserId);
}
```

`ImMessage` 是与渠道无关的卡片描述（标题、段落、按钮、表单项），各渠道适配器负责转换为飞书卡片 JSON、钉钉互动卡片等。客户自研 IM 实现同一接口，以插件 jar 方式加载。飞书先做完整闭环（M2），钉钉和企微在 M4 照搬。

## 业务系统接入（CON-07）

- **入站**：每个业务绑定配置签名方式（HMAC 头 + 时间戳，允许偏差 5 分钟防重放）和字段映射（JSONPath → `attrs`、`subject_key`），映射后调 `OrchApi.ingest`。只有映射出的字段算可信属性。
- **出站**：上传 OpenAPI 描述，勾选要暴露的操作，生成 `biz.{system}.{operation}` 工具注册进 ToolGateway。写操作默认需要审批。

## MCP 管理（S12、CON-05）

| 项 | 定案 |
| --- | --- |
| 传输 | Streamable HTTP 为主，兼容旧 SSE；stdio 只在私有化部署中由管理员启用，进程跑在沙箱里 |
| 认证 | none / bearer / OAuth。需要代用户授权的 Server，用户令牌存在令牌保管（设计 L） |
| 工具发现 | 接入和手动刷新时拉 `tools/list`，存为不可变快照；Agent 版本引用快照号，工具变化不会悄悄改变已发布 Agent 的行为 |
| 命名 | 暴露给模型的工具名为 `{serverSlug}__{tool}`，避免跨 Server 重名 |
| 健康 | 每 60 秒握手探活，连续 3 次失败熔断 5 分钟；熔断期间该 Server 的工具从模型的工具列表中移除 |
| 授权 | 两层：空间成员对 Server 有 USE 权限才能在 Agent 上绑定；绑定写入 Agent 版本快照，只允许快照里的工具 |
| 对外暴露 | Tioka 作为 MCP Server 暴露知识检索和 kind=MCP 的 Flow 发布；作为资源服务器校验 Keycloak 签发的 OAuth 令牌，Tioka 自己不做授权服务器 |

## 飞书办公套件

飞书文档、Wiki、云盘、表格的读写都做成工具（`feishu.doc.read` 等），默认用代用户令牌，用户未授权时返回授权链接而不是静默改用应用身份。文档同步进知识库走设计 D 的数据源连接器。

## 数据模型

```sql
CREATE TABLE im_binding (id VARCHAR(32) PRIMARY KEY, tenant_id VARCHAR(32) NOT NULL, workspace_id VARCHAR(64) NOT NULL,
  channel VARCHAR(32) NOT NULL, app_id VARCHAR(128) NOT NULL, secret_ref VARCHAR(255) NOT NULL,
  encrypt_key_ref VARCHAR(255), default_agent_id VARCHAR(64), status VARCHAR(16) NOT NULL);
CREATE TABLE im_conversation_map (binding_id VARCHAR(32), external_chat_id VARCHAR(128), external_thread_id VARCHAR(128),
  conversation_id VARCHAR(64) NOT NULL, PRIMARY KEY (binding_id, external_chat_id, external_thread_id));
CREATE TABLE im_inbound_dedupe (binding_id VARCHAR(32), event_id VARCHAR(128), received_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (binding_id, event_id));                -- 保留 7 天
CREATE TABLE biz_binding (id VARCHAR(32) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL, system VARCHAR(64) NOT NULL,
  inbound_secret_ref VARCHAR(255), mapping_json TEXT, openapi_ref VARCHAR(255), exposed_ops_json TEXT,
  egress_hosts TEXT, status VARCHAR(16) NOT NULL);
ALTER TABLE mcp_server ADD COLUMN workspace_id VARCHAR(64) NOT NULL;
ALTER TABLE mcp_server ADD COLUMN slug VARCHAR(64) NOT NULL;
ALTER TABLE mcp_server ADD COLUMN transport VARCHAR(16) NOT NULL;     -- HTTP / SSE / STDIO
ALTER TABLE mcp_server ADD COLUMN auth_type VARCHAR(16) NOT NULL;     -- NONE / BEARER / OAUTH
ALTER TABLE mcp_server ADD COLUMN oauth_json TEXT;
ALTER TABLE mcp_server ADD COLUMN health VARCHAR(16);
ALTER TABLE mcp_server ADD COLUMN protocol_version VARCHAR(32);
CREATE TABLE mcp_server_snapshot (id VARCHAR(32) PRIMARY KEY, server_id VARCHAR(64) NOT NULL,
  tools_json TEXT NOT NULL, server_info_json TEXT, created_at TIMESTAMPTZ NOT NULL);   -- 取代 mcp_tool
-- mcp_grant 废弃：绑定关系进入 Agent 版本快照（设计 I）
```

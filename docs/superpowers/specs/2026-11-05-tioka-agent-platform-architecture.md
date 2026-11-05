> **状态：参考稿（已降级）**。权威分系统详设见 `docs/architecture/systems/S0–S8` 与飞书知识库 Tioka 项目。

# Tioka 企业级 Agent 中台 — 系统架构设计

> 版本：v0.1（草案）  
> 日期：2026-11-05  
> 状态：待评审  
> 约束：整套自研（执行内核 / 编排 / 治理 / HCI）；外部仅接 MCP、IM、业务系统；不集成 Codex / DeepSeek Harness / 阿里云 AgentRun 等外部运行时。

---

## 0. 文档目的与范围

本文给出 **系统级架构**、**各业务系统设计**、**模块间依赖与通信约定**，以及 **飞书文档/知识库双向同步**（借鉴 DeepSeek Harness 生态中 `dsh-feishu` 等插件能力，自研实现，不引入其运行时）。

不在本文范围：具体表字段 DDL、前端像素稿、模型供应商商务选型。

---

## 1. 产品定位

Tioka 是企业级 **Agent 应用套件 + 运行中台**：

| 产品面 | 对标（只借鉴设计） | Tioka 模块 |
|---|---|---|
| 对话应用 | LibreChat | `tioka-module-chat` + HCI Chat |
| 可视化工作流 | Langflow | `tioka-module-flow` |
| 知识库 / RAG | WeKnora | `tioka-module-knowledge` |
| 调度与人审 | agent-autoloop / dsh-superagent | `tioka-module-orchestration` |
| 执行内核 | Codex / DeepSeek harness | `tioka-module-runtime` |
| 治理与底座 | AgentCore + RuoYi | `system` / `infra` / governance 能力 |
| 人机交互 | 自研 HCI Suite | UI + Message Part 协议 |
| 连接器 | MCP / IM / 业务系统 | `tioka-module-connector` |

**一句话：** 中台管何时派、派给谁、等人、记账、审计；Runtime 管这一轮怎么想、怎么调工具；HCI 是人机协作的一等产品，不是附属后台。

---

## 2. 系统级架构

### 2.1 逻辑分层

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         HCI Suite（人机交互套件）                          │
│  Chat 工作台 │ Flow Studio │ 知识工作台 │ 审批/值班台 │ 观测台 │ 配置台     │
│  IM 延伸面（飞书/钉钉/企微卡片与回调）                                      │
└───────────────────────────────────┬─────────────────────────────────────┘
                                    │ HTTPS / SSE / WS
┌───────────────────────────────────▼─────────────────────────────────────┐
│                      API Gateway / BFF（可选独立）                         │
└───┬─────────┬─────────┬─────────┬─────────┬─────────┬───────────────────┘
    │         │         │         │         │         │
┌───▼───┐ ┌───▼───┐ ┌───▼───┐ ┌───▼───┐ ┌───▼───┐ ┌───▼────────┐
│ chat  │ │ flow  │ │know-  │ │ orch  │ │run-   │ │ connector  │
│ 服务  │ │ 服务  │ │ledge  │ │ 服务  │ │time   │ │ 服务       │
└───┬───┘ └───┬───┘ └───┬───┘ └───┬───┘ └───┬───┘ └──────┬─────┘
    │         │         │         │         │            │
    └─────────┴──── RPC(-api) ────┴─────────┴────────────┘
                          │
                    ┌─────▼─────┐
                    │    MQ     │
                    └─────┬─────┘
          ┌───────────────┼───────────────┐
          ▼               ▼               ▼
     PostgreSQL        Redis          对象存储
     （分 schema）     （租约/缓存）    （文档/产物）
```

### 2.2 工程形态（RuoYi-Vue-Pro 风格）

```
tioka/
├── pom.xml
├── tioka-dependencies/                 # BOM
├── tioka-framework/                    # 安全、租户、MQ、Web、MyBatis、Trace…
├── tioka-modules/                      # 业务模块聚合
│   ├── pom.xml
│   ├── tioka-module-system/            # api + biz
│   ├── tioka-module-infra/
│   ├── tioka-module-runtime/
│   ├── tioka-module-orchestration/
│   ├── tioka-module-chat/
│   ├── tioka-module-knowledge/
│   ├── tioka-module-flow/
│   └── tioka-module-connector/
├── tioka-server/                       # 单体聚合启动
├── tioka-server-chat/                  # 可选拆分启动器（示例）
├── tioka-server-runtime/
├── tioka-server-knowledge/
├── tioka-server-flow/
├── tioka-server-orchestration/
├── tioka-server-connector/
└── tioka-ui/                           # Vue3：chat / flow / kb / admin 多路由或 monorepo 包
```

每个业务模块强制：

| 子模块 | 职责 |
|---|---|
| `*-api` | DTO、枚举、错误码、RPC 接口、**MQ Topic/Tag/消息体** |
| `*-biz` | 领域实现、持久化、对内 SPI；**禁止**被其他模块 biz 依赖 |

跨模块：**只依赖对方 `-api` jar**；异步用 MQ；同步用本地 Bean 或 Feign/Dubbo（接口不变）。

### 2.3 部署形态

| 形态 | 说明 |
|---|---|
| 单体 | `tioka-server` 引入全部 `*-biz`，本地调用 api 实现 |
| 多模块部署 | 各 `tioka-server-xxx` 独立进程，`-api` → RPC，事件 → MQ |
| 混合 | 建议：`runtime` / `knowledge` 独立；`chat`+`orch` 可合部 |

---

## 3. 核心领域模型（跨系统统一语言）

### 3.1 执行与编排

| 概念 | 定义 |
|---|---|
| Session | Runtime 内一次连续推理上下文（Turn/Step 日志） |
| Turn / Step | 一轮用户/系统输入到模型与工具闭环 |
| Event | 外部或内部发生的事（业务、cron、人的回答） |
| Rule | 可信指令：谁干、权限、挂起策略（不由事件正文决定） |
| WorkItem（事项/工单） | 同一规则+对象上的持续事务 |
| Run | 一次执行领取→回执→结束；**不阻塞等人** |
| Attention | 需要人处理的结构化请求（卡片） |
| Mandate | 授权快照（策略版本、范围、强度）— 编排侧可选增强 |

### 3.2 人机交互（Message Part）

```text
Message = { id, conversationId, role, parts[], status, createdAt }
Part Discriminator:
  text | markdown | thinking
  tool_call | tool_result
  citation                          # 知识引用
  artifact                          # 产物
  interactive_card                  # mode=continue：对话内继续
  approval_card                     # mode=suspend：编排挂起
  status | error
```

**规则：**

- Chat、IM、Flow 节点输出、Knowledge 引用 **共用 Part 协议**（`platform-contracts` 或 `chat-api` 中的 `message` 包，全仓唯一）。
- `interactive_card`：当前 Turn 内回填，Runtime 继续。
- `approval_card`：结束当前 Run，状态落库；回答后 Resume。

### 3.3 知识与工作流

| 概念 | 定义 |
|---|---|
| KnowledgeBase | 知识空间（文档/FAQ…） |
| Document / Chunk | 文档与检索单元 |
| IngestJob | 解析入库异步任务 |
| FlowDefinition | 可视化图（节点+边）版本 |
| FlowRun | 一次工作流执行实例 |
| NodeExecution | 节点运行记录 |

### 3.4 飞书同步

| 概念 | 定义 |
|---|---|
| FeishuBinding | 应用凭据、身份（user/app）、默认 Wiki Space / Folder |
| DocSyncMapping | 本地文档/设计稿 ↔ 飞书 doc/wiki node 映射 |
| SyncJob | 单向或双向同步任务（幂等、可重试） |

---

## 4. 各系统设计

### 4.1 `tioka-framework`（技术底座）

**职责：** 与业务无关的横向能力。

| 组件 | 说明 |
|---|---|
| 安全 / 租户 | Token、RBAC、多租户上下文（TenantContext） |
| Web | 统一异常、校验、API 前缀 |
| MyBatis / DB | 多数据源、逻辑删除、审计字段 |
| MQ | 生产者/消费者抽象、幂等消费注解 |
| Redis | 分布式锁、在线租约、限流 |
| Trace | TraceId、RunId、SessionId 贯穿 |
| 加密 | 密钥托管接口（模型 Key、飞书 Secret） |

**依赖：** 无业务模块依赖；被所有 `*-biz` 依赖。

---

### 4.2 `tioka-module-system` / `infra`

**职责：** 对齐 RuoYi：用户、角色、菜单、部门、字典、租户、文件、定时任务、操作日志。

**对外 api（摘录）：**

- `AdminUserApi` / `PermissionApi` / `TenantApi`
- `FileApi`（对象存储元数据）
- `ConfigApi`

**被依赖方：** 几乎所有业务模块（只读鉴权与用户信息）。

---

### 4.3 `tioka-module-runtime`（执行内核）

**定位：** 自研 Agent Loop（对标 harness，不集成外部 harness）。

**包结构（biz）：**

```
runtime
├── session/          # Session 生命周期、事件日志、投影
├── loop/             # Turn/Step 驱动、取消、Steer
├── llm/              # 模型适配 SPI（OpenAI 兼容等）
├── tools/            # Tool 注册表、执行管道、权限守卫
├── policy/           # 沙箱/审批策略（工具级）
├── compact/          # 上下文压缩（后期）
└── spi/              # ToolProvider、LlmProvider
```

**对外 api：**

```text
RuntimeSessionApi
  create(CreateSessionReq): SessionDTO
  append(AppendReq): void                    # 用户消息 / 系统注入
  stream(sessionId): ServerSentEvent…        # 或回调 MQ + 客户端拉流
  steer(SteerReq): void
  cancel(sessionId): void

RuntimeToolQueryApi
  listTools(tenantId, agentId): List<ToolDescriptor>

# Tool 执行对内；外部模块通过「注册 ToolProvider」扩展，不直接调执行器
```

**MQ 产出：**

| Topic | Tag | 含义 |
|---|---|---|
| `tioka-runtime` | `turn.started` | Turn 开始 |
| `tioka-runtime` | `turn.completed` | Turn 结束（含 usage） |
| `tioka-runtime` | `part.appended` | 流式 Part 落库通知（可选） |
| `tioka-runtime` | `attention.requested` | 工具策略要求人批（转 orch） |

**依赖：** `system-api`；可选消费 `knowledge-api` / `flow-api` **仅通过 ToolProvider 适配器**（适配器放 connector 或各域自己的 runtime-bridge，避免 runtime-biz 直接依赖 knowledge-biz）。

**原则（借鉴 harness）：**

- Session 日志为模型可见上下文权威来源。
- 工具执行经统一管道：`pre-execute → execute → post-execute`。
- 内核无飞书 SDK、无业务 SQL。

---

### 4.4 `tioka-module-orchestration`（编排中台）

**定位：** 事件驱动调度 + 人审挂起（对标 autoloop / superagent）。

**包结构：**

```
orchestration
├── event/            # 事件接入幂等
├── rule/             # 规则版本、匹配
├── workitem/         # 事项/工单
├── run/              # claim / heartbeat / result
├── attention/        # 卡片状态机
├── decide/           # 决策与事实（可演进事件溯源）
└── why/              # 审计回放查询
```

**对外 api：**

```text
OrchEventApi.emit(EventReq): EmitResult          # 幂等 source+id
OrchWorkItemApi.get / list
OrchRunApi.claim / heartbeat / submitResult
OrchAttentionApi.create / answer / resolve / list
OrchWhyApi.timeline(runId)
OrchRuleApi  # 管理面；执行面只读已发布版本
```

**MQ：**

| Topic | Tag | 生产者 / 消费者 |
|---|---|---|
| `tioka-orch` | `event.routed` | orch → 可触发 flow/runtime |
| `tioka-orch` | `run.dispatched` | orch → runtime 或 worker |
| `tioka-orch` | `attention.created` | orch → chat、connector(IM) |
| `tioka-orch` | `attention.answered` | orch → runtime resume |
| `tioka-orch` | `run.finished` | orch → chat / 业务回写 |

**依赖：** `runtime-api`（派活）、`system-api`；**不**依赖 chat/knowledge/flow 的 biz。

**关键机制：**

1. 挂起零消耗：approval → Run 结束，状态 DB。  
2. 指令数据分离：事件正文不进路由决策。  
3. 跳数熔断：回投事件 hops 上限。  
4. 至少一次投递：副作用动作先核实状态。

---

### 4.5 `tioka-module-chat`（对话域）

**定位：** LibreChat 级会话与消息；HCI Chat 的领域后端。

**包结构：**

```
chat
├── conversation/     # 会话、分支、标题
├── message/          # Message + Part 持久化
├── agentbind/        # 会话绑定 Agent/预设
├── attachment/       # 附件元数据
└── bridge/           # 调用 runtime / orch
```

**对外 api：**

```text
ConversationApi.create / list / get / fork
MessageApi.appendUser / list / get
ChatCardApi.answerInteractive(cardId, payload)   # continue 模式
# 流式：SSE 基于 runtime.stream + 本地 Part 投影
```

**MQ 消费：** `runtime.part.appended`、`orch.attention.*`、`knowledge.ingest.finished`（通知）  
**MQ 产出：** `chat.message.appended`、`chat.conversation.created`

**依赖：** `runtime-api`、`orchestration-api`（审批类）、`system-api`；检索经 Runtime Tool，不直连 knowledge-biz。

---

### 4.6 `tioka-module-knowledge`（知识域）

**定位：** WeKnora 级知识工程（MVP 先文档库+检索+引用）。

**包结构：**

```
knowledge
├── base/             # 知识库
├── document/         # 文档元数据
├── ingest/           # 解析管道、任务队列
├── index/            # 向量 + 关键词
├── retrieval/        # 混合检索、重排
├── citation/         # 引用对象规范
└── sync/             # 与飞书等外部知识源同步（调用 connector-api）
```

**对外 api：**

```text
KnowledgeBaseApi.crud
DocumentApi.upload / get / delete / list
IngestApi.reparse / status
RetrievalApi.search(SearchReq): SearchHits      # 供 ToolProvider
CitationApi.resolve(citationId)
KnowledgeSyncApi.bindFeishu / triggerSync / status
```

**MQ：**

| Tag | 含义 |
|---|---|
| `ingest.queued` / `ingest.finished` / `ingest.failed` | 入库生命周期 |
| `sync.finished` | 飞书等同步完成 |

**依赖：** `system-api`、`infra-api`(文件)、`connector-api`（飞书读写）；被 Runtime 的 `KnowledgeToolProvider`（可放在 knowledge 模块提供的 runtime 扩展 starter，或 connector）使用。

**Runtime 暴露工具（规范名）：**

- `kb.search` → hits + citation stubs  
- `kb.read_chunk` / `kb.read_document`  
- （可选）`kb.ask` 封装检索+生成，仍走 Runtime Session  

---

### 4.7 `tioka-module-flow`（工作流域）

**定位：** Langflow 级可视化编排。

**包结构：**

```
flow
├── definition/       # 图版本、校验
├── editor/           # 草稿保存
├── engine/           # DAG/状态机执行
├── nodes/            # 内置节点
├── publish/          # 发布为 API / Tool
└── run/              # FlowRun 追踪
```

**内置节点（MVP）：** `Input` `LLM` `Agent` `KnowledgeRetrieve` `MCP` `HTTP(Biz)` `Branch` `HumanCard` `Output`

**规则：**

- `Agent` 节点 = 创建/复用 `RuntimeSession`，禁止自建第二套 Loop。  
- `HumanCard` = 走 `OrchAttentionApi` 或 Chat Part（按 mode）。  
- 发布：`FlowToolProvider` 注册 `flow.run:{flowId}`。

**对外 api：**

```text
FlowDefinitionApi.save / publish / get
FlowRunApi.start / get / cancel
FlowPublishApi.asTool / asHttpEndpoint
```

**MQ：** `flow.run.started` / `flow.run.finished` / `flow.node.failed`

**依赖：** `runtime-api`、`orchestration-api`、`knowledge-api`(检索可经 Tool)、`system-api`。

---

### 4.8 `tioka-module-connector`（连接器）

**定位：** 唯一允许持有「外部产品 SDK」的模块（MCP、IM、业务系统、飞书开放平台）。

**子域：**

```
connector
├── mcp/              # MCP Client（及可选 Server 出口）
├── im/
│   ├── feishu/       # 消息、卡片、事件订阅（长连接/Webhook）
│   ├── dingtalk/
│   └── wecom/
├── biz/              # 业务系统 Webhook → OrchEvent
└── feishu-doc/       # 文档 / Wiki / Drive 同步（借鉴 dsh-feishu）
```

#### 4.8.1 飞书能力拆分（重要）

借鉴 `dsh-feishu` / `harness-lark` 的能力边界，Tioka **自研**为两套门面（同模块不同包）：

| 门面 | 用途 | 对应 dsh-feishu |
|---|---|---|
| `FeishuImClient` | 收发消息、交互卡片、审批回调 | `feishu_message` + 事件 |
| `FeishuDocClient` | 云文档创建/追加/读、Wiki 节点、Drive | `feishu_doc` / `feishu_wiki` / `feishu_drive` |
| `FeishuAuthService` | App + User OAuth、token 刷新、身份 auto/user/app | `feishu_auth` |

**身份策略（照搬其安全结论）：**

- 默认 `user`：内容落入授权用户可见空间。  
- 无用户授权时 `app` 回退，并打 **warning**，禁止静默写到用户看不到的位置。  
- App Secret 只进密钥托管，不进配置文件明文。

#### 4.8.2 文档 → 飞书知识库同步（平台能力）

**场景：**

1. **设计/运维文档出站：** Tioka 仓库或知识库中的 Markdown → 飞书 Wiki 指定节点（本架构文档同步属此场景）。  
2. **飞书入站：** 飞书 Wiki/云文档 → Knowledge 入库（WeKnora 式数据源）。  
3. **Agent 工具：** Runtime 注册 `feishu_doc_*` / `feishu_wiki_*`（权限受治理策略约束）。

**同步领域模型：**

```text
DocSyncMapping {
  id, tenantId,
  sourceType: LOCAL_MD | KNOWLEDGE_DOC | GIT_PATH,
  sourceRef,                    # 路径或 documentId
  targetType: FEISHU_DOCX | FEISHU_WIKI_NODE,
  spaceId, parentNodeToken, docToken,
  contentHash, revision, lastSyncAt, direction: PUSH | PULL | BIDI
}
```

**出站流程（PUSH）：**

```text
文档变更（Git hook / 管理台「同步」 / MQ knowledge.document.updated）
  → SyncJob 入队（幂等 key = mappingId + contentHash）
  → FeishuDocClient：无 docToken 则 wiki 建节点 + 创建文档；有则转换为 blocks 更新/全量替换策略
  → 写回 mapping.revision / contentHash
  → MQ connector.doc.sync.finished
```

**入站流程（PULL）：**

```text
定时或 Webhook（若飞书支持）
  → 列出 Wiki 子树 / 读 docx plain/blocks
  → 规范化为 Markdown/中间表示
  → Knowledge DocumentApi 入库（新版本）
  → 触发 IngestJob
```

**对外 api：**

```text
FeishuImApi.sendCard / sendText
FeishuDocSyncApi.upsertMapping / syncNow / getStatus
McpGatewayApi.listTools / …（内部给 Runtime ToolProvider）
BizIngressApi  # 校验签名后 OrchEventApi.emit
```

**依赖：** `orchestration-api`（emit、attention 投递）、`knowledge-api`（入站入库）、`system-api`；**禁止**反向被 runtime-biz 直接依赖飞书 SDK。

---

## 5. 系统间依赖关系

### 5.1 依赖原则

1. **单向、无环：** 依赖只指向 `-api`；出现环则抽到 `platform-contracts` 或下沉 MQ。  
2. **运行时调用方向：** 上层（Chat/Flow/Orch）→ Runtime；Knowledge/Flow 以 Tool 形式被 Runtime 调用。  
3. **连接器在边缘：** 只有 `connector` 出网到 IM/飞书开放平台/MCP Server/业务系统。

### 5.2 Maven / 编译期依赖图

```text
                    system-api
                        ▲
        ┌───────────────┼───────────────────────────┐
        │               │                           │
   runtime-api     orch-api                    infra-api
        ▲               ▲                           ▲
        │               │                           │
   chat-api        flow-api                   knowledge-api
        ▲               ▲                           ▲
        └───────┬───────┴─────────────┬─────────────┘
                │                     │
           connector-api ◀────────────┘（sync、IM）

biz 层：
  chat-biz        → runtime-api, orch-api, system-api
  flow-biz        → runtime-api, orch-api, knowledge-api?, system-api
  knowledge-biz   → system-api, infra-api, connector-api
  orch-biz        → runtime-api, system-api
  runtime-biz     → system-api  （+ Tool SPI 实现由各域 starter 注入）
  connector-biz   → orch-api, knowledge-api, system-api
```

**禁止：** `knowledge-biz → chat-biz`、`runtime-biz → connector-biz`（飞书）、`flow-biz → chat-biz`。

### 5.3 运行时调用矩阵（同步 RPC）

| 调用方 ↓ / 被调 → | runtime | orch | chat | knowledge | flow | connector | system |
|---|---|---|---|---|---|---|---|
| chat | Session/Stream | Attention | — | — | — | — | 鉴权 |
| flow | Agent 节点 Session | HumanCard | — | search(可选) | — | — | 鉴权 |
| orch | 派活 Session | — | — | — | start Flow | 通知 IM | 鉴权 |
| knowledge | — | — | — | — | — | 飞书读写 | 鉴权 |
| connector | — | emit/answer | — | 入库 | — | — | 鉴权 |
| runtime | — | attention 请求 | — | via Tool | via Tool | via MCP Tool | 鉴权 |

### 5.4 MQ 主题总表

| Topic | 主要 Tag | 说明 |
|---|---|---|
| `tioka-runtime` | `turn.*` `part.appended` `attention.requested` | 执行生命周期 |
| `tioka-orch` | `event.*` `run.*` `attention.*` | 编排 |
| `tioka-chat` | `message.*` `conversation.*` | 对话 |
| `tioka-knowledge` | `ingest.*` `document.*` | 知识 |
| `tioka-flow` | `run.*` `definition.published` | 工作流 |
| `tioka-connector` | `im.*` `doc.sync.*` `mcp.*` | 连接器 |

消息体 Schema 版本化：`apiVersion` 字段；消费者忽略未知字段。

### 5.5 典型端到端链路

**链路 A — 对话 + 知识 + 交互卡片**

```text
用户 Chat
 → chat-biz MessageApi
 → runtime Session.append + stream
 → Tool kb.search（knowledge-api）
 → Part citation + interactive_card
 → 用户作答 ChatCardApi
 → runtime steer/继续
```

**链路 B — 业务事件 + 人审 + IM**

```text
业务系统 → connector 验签 → OrchEventApi.emit
 → 规则匹配 → Run → runtime
 → approval_card → OrchAttention → MQ
 → connector 飞书卡片 / Chat 审批台
 → answer → orch resume → runtime 续跑
 → 结果回写业务（connector）
```

**链路 C — Flow 发布为 Tool**

```text
Flow Studio 发布 → FlowPublishApi.asTool
 → Runtime ToolRegistry 可见
 → Chat Agent 调用 flow.run:xxx
 → flow-engine → 内嵌 Agent/KB 节点
 → 结果 Part 回 Chat
```

**链路 D — 架构文档同步飞书知识库**

```text
docs/**/*.md 变更或管理台「同步到飞书」
 → KnowledgeSyncApi / FeishuDocSyncApi
 → connector FeishuDocClient（wiki 节点 + docx）
 → DocSyncMapping 更新
 → （可选）回写 knowledge 作为镜像文档
```

---

## 6. HCI Suite 结构设计

### 6.1 交互面

| 面 | 主用户 | 核心能力 |
|---|---|---|
| Chat 工作台 | 全员 | 会话、流式、Part、交互卡片、选 Agent、附件 |
| Flow Studio | 搭建者 | 画布、调试 Playground、发布 |
| 知识工作台 | 知识管理员 | 库、入库、分块、同步源、评测（后期） |
| 审批/值班台 | 运营 | Attention 队列、超时、批量 |
| 观测台 | 研发/SRE | why、Trace、Token、失败 |
| 配置台 | 管理员 | 租户、Agent、规则、模型、MCP、飞书绑定 |
| IM 延伸 | 移动办公 | 卡片投递与回调，状态权威在中台 |

### 6.2 前端工程

```
tioka-ui/
├── packages/parts          # Message Part 渲染与卡片表单（唯一）
├── apps/web                # 主站：上述各面路由
└── shared/api-client       # 按模块生成的 TS SDK
```

---

## 7. 并行交付（方案 D）与模块归属

| 轨道 | 模块 | 冻结依赖 |
|---|---|---|
| 0 契约/内核 | `framework` + `runtime-api/biz` 最小闭环 + Part Schema | 无 |
| A Chat | `chat` + UI Chat + parts | runtime-api, orch-api |
| B Knowledge | `knowledge` + UI KB | system, infra, connector-api |
| C Flow | `flow` + UI Studio | runtime-api, orch-api |
| 连接/编排 | `orchestration` + `connector` | runtime-api；飞书同步属 connector |

集成里程碑 M1/M2 见前期规划；合同测试基于 `-api` + MQ Schema。

---

## 8. 安全与多租户

- 所有 api 带 `tenantId`；DB 行级或 schema 级隔离。  
- 事件不能指定 agent/cwd/权限；仅规则/治理配置可以。  
- 飞书：最小权限范围；文档同步使用显式 Mapping，禁止全租户盲扫。  
- MCP：按 Agent 绑定允许的 server 列表。  
- 密钥：framework 凭证服务；轮转与审计。

---

## 9. 非功能指标（目标）

| 项 | 目标（初值） |
|---|---|
| Runtime 单 Turn 调度开销 | p99 < 20ms（不含模型） |
| Orch 事件接入幂等 | 严格 exactly-once 记账（业务至少一次） |
| 知识检索 | p95 < 300ms（百万级 chunk 前） |
| 飞书同步 | 单文档失败可重试；不阻塞主问答链路 |
| 可观测 | 每个 Run/Turn/Tool/SyncJob 可关联 Trace |

---

## 10. 分期与文档产物

| 阶段 | 内容 |
|---|---|
| P0 | 本架构评审通过；仓库骨架 `tioka-modules`；Part Schema |
| P1 | runtime + 极简 chat |
| P2 | interactive/approval 卡片 + orch 挂起 + 飞书 IM |
| P3 | knowledge MVP + **飞书 Wiki 出站/入站同步** |
| P4 | flow MVP |
| P5 | 治理强化、观测台、多 IM |

**关联文档（后续）：**

- `docs/architecture/message-part-protocol.md`  
- `docs/architecture/mq-topics.md`  
- `docs/architecture/feishu-doc-sync.md`  
- 各模块 `*-api` JavaDoc / OpenAPI  

---

## 11. 飞书知识库同步 — 实施要点（详设摘要）

> 详细接口可拆 `docs/architecture/feishu-doc-sync.md`；此处为架构约束。

### 11.1 借鉴清单（自研对照）

| dsh-feishu 能力 | Tioka 实现位置 |
|---|---|
| `feishu_doc` 创建/追加 Markdown/导入 | `FeishuDocClient` |
| `feishu_wiki` 空间与节点 | `FeishuWikiClient` |
| `feishu_drive` 目录与上传 | `FeishuDriveClient` |
| `feishu_auth` OAuth / 身份 | `FeishuAuthService` |
| `feishu_message` 卡片 | `FeishuImClient`（与同步分离） |

### 11.2 推荐权限（开放平台）

- `wiki:wiki`、`docx:document`、`drive:drive`  
- IM 场景另加 `im:message` 等  
- `offline_access`（用户授权长期刷新）  

### 11.3 本仓库文档同步约定

路径建议：

- 源：`docs/**/*.md`（含本架构文）  
- 目标：飞书知识库空间「Tioka 工程文档」下按目录镜像节点  
- 触发：CI（文档变更）或管理台按钮 `同步工程文档`  
- 映射表：存 `connector` 库 `doc_sync_mapping`  

> **实际推送到客户飞书租户** 需要 AppId/Secret 与 Wiki SpaceId；配置进入租户级 `FeishuBinding` 后由 `FeishuDocSyncApi.syncNow` 执行。无凭据时仅保留本地 Markdown 为权威源。

---

## 12. 待决事项（评审时勾选）

- [ ] RPC：OpenFeign / Dubbo / 先本地后拆  
- [ ] MQ：RocketMQ / RabbitMQ / Kafka  
- [ ] 向量库：pgvector / Milvus / 其他  
- [ ] 是否将 `message` Part Schema 独立为 `tioka-module-platform-api`  
- [ ] 飞书默认同步身份：user OAuth vs 应用身份  

---

## 13. 修订记录

| 版本 | 日期 | 说明 |
|---|---|---|
| v0.1 | 2026-11-05 | 首版：总架构、模块、依赖、MQ、HCI、飞书同步 |

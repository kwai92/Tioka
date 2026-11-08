# M1-05 — Agent 资产快照、发布校验与 G1 闸门基座

- 负责人：BE-C（AST-01、ENG-04b）；BE-A（AST-02）；BE-B（G1 端到端用例）；FE（Agent 编辑器）
- 工作项：AST-01、AST-02、ENG-04b、G1-E2E、FE-02（按实施顺序）
- 约束：ADR-023、ADR-015、ADR-016、ADR-029 第 11、29、33、60 条；ADR-032、ADR-037〜040 的预留字段（只占位，不实现）
- 前置：FND-04 的 `ResourceGuard`（AST-02 用）；M0 的 `DualInstance`、`LlmStubServer`（ENG-04b 在其上扩充）
- 迁移：V123、V124（Java）、V125
- 来源：2026-11-06 M1 计划核查发现 AST-01、AST-02 在 ADR-023 中定为 **M1 P0**（G1「选已发布 Agent」依赖它），但 M1-01〜M1-04 没有施工设计、周计划和人天；ENG-04 的 M1 扩充同样没有排。本文补齐，排期见 [M1-00](M1-00-M1实施计划.md)

---

## AST-01 AgentSpec、草稿与不可变版本（P0，6 人天）

### 现状

Agent 配置散在四处：`agent_version`（`instructions`、`routing_plan_id`、`mcp_grant_ids`、`skill_bindings`、`published` 布尔值）、`mcp_grant`（按 agent 存，可随时改）、`agent_sandbox`、旧 `agent_share`（V110 已删）。`McpToolProvider` 运行时再经 `AgentQueryApi` 反查 `mcp_grant`，因此「已发布版本」实际上并不冻结：改了 `mcp_grant` 就改了线上 Agent 的工具权限。

### AgentSpec（M1 子集）

`agent-api` 新增包 `com.tioka.agent.api.spec`：

```java
public record AgentSpec(
    int schemaVersion,                 // M1 固定为 1
    Profile profile,                   // name、description、icon、tags、kind(CHAT / TASK / READONLY)
    Instructions instructions,         // sections: [{key, text}]；变量占位 {{user.name}} 等，M1 只做原样拼接
    ModelPolicy model,                 // routingPlanId（必填）、allowUserOverride(false)
    ToolSet tools,                     // builtin: [name]；mcp: [{serverId, allowlist[]}]；flows: []（M3）；business: []（M3）
    List<SkillRef> skills,             // [{skillVersionId, mode: ALWAYS|MANUAL}]；AUTO 在 M3（AST-06）
    KnowledgeBinding knowledge,        // kbIds[]、mode: TOOL | AUTO_RETRIEVE
    SandboxPolicy sandbox,             // level: READ / EDIT / FULL（沿用 agent_sandbox），networkAllowlist[]
    ApprovalSettings approval,         // toolPolicies: {toolName: AUTO|APPROVE|DENY}，assignee（可空 = 发起人）
    Limits limits,                     // maxSteps(默认 20)、maxTokensPerTurn
    // —— 以下为后续里程碑预留，M1 必须为 null，发布校验会拒绝非 null ——
    JsonNode charter,                  // ADR-032 授权书（M3 TRU-01）
    JsonNode output,                   // ADR-037 OutputContract（M3 SCH-02）
    JsonNode subAgents,                // ADR-038 SubAgentPolicy（M3 RT-12）
    String dataClearance               // ADR-040 最高数据标签（M3 DAT-02）
) {}
```

- 预留字段用 `JsonNode`/`String` 占位，M3 换成正式类型时 `schemaVersion` 升到 2，`AgentSpecUpcaster` 负责把 1 升成 2（v1 中这些字段都是 null，升级只是补默认值）。
- 序列化：Spring 注入的 `ObjectMapper`，`FAIL_ON_UNKNOWN_PROPERTIES=true`（快照不接受未知字段）。内容哈希用 RFC 8785 规范化 JSON + SHA-256（与 ADR-029 第 50 条同一工具类 `CanonicalJson`，放 `tioka-framework`）。
- 契约：`docs/contracts/agent/agent-spec-v1.json`（JSON Schema，由本项 PR 产出），前端编辑器与后端校验共用；`AgentSpecContractTest` 校验 Java record 与 Schema 一致。

### 表（V123）

见 [V123__m1_agent_spec.sql](../../contracts/db/V123__m1_agent_spec.sql)。要点：

- `agent_draft`：每个 Agent 一行可编辑草稿，`revision` 乐观锁。
- `agent_version`：加 `version_no`、`spec_json`、`status`（`PUBLISHED` / `PENDING_APPROVAL` / `RETIRED`）、`published_at/by`；`snapshot_hash` 改存规范化哈希。版本行发布后不可修改（`AgentVersionRepository` 不提供 update；ArchUnit `agentVersionImmutable`：除 `retire` 外禁止调用 `updateById`）。
- `agent_agent.published_version_id` 仍是「发布指针」，回滚只改指针。

### 数据迁移

- **V124（Java 迁移 `V124__BackfillAgentSpec`）**：对每个 Agent，按 `create_at` 给已有 `published=true` 的版本编号 1..n，并从旧列组装 `spec_json`：`instructions` → `instructions.sections[0]`；`routing_plan_id` → `model.routingPlanId`；`mcp_grant_ids` 逐个查 `mcp_grant` → `tools.mcp`；`skill_bindings` → `skills`（模式一律 `ALWAYS`）；`agent_sandbox.level` → `sandbox.level`。最新一条 `published=false` 的版本（若有）转为 `agent_draft`，没有则用最新已发布版本的 spec 生成草稿。组装失败的 Agent 记 `warn` 并跳过（其旧版本 V125 后不可用，迁移日志列出 ID，由管理员重新发布）。
- **V125（随本项 PR 合入，ADR-029 第 33 条修订：破坏性迁移随工作项落地）**：删除未发布的旧版本行，`version_no`、`spec_json` 置 NOT NULL，删除 `agent_version.published`、`instructions`、`mcp_grant_ids`、`skill_bindings` 与表 `agent_sandbox`、`mcp_grant`。`routing_plan_id` 保留作冗余索引列（发布时从 spec 写入，供「哪些 Agent 用了某方案」查询）。

### 读取路径

```java
// agent-api
public interface AgentSnapshotApi {
    /** 已发布或已退役的版本快照；不存在 → NOT_FOUND。快照不可变，biz 侧 Caffeine 缓存 1000 条、不过期。 */
    AgentSnapshot get(String agentVersionId);
    /** Agent 当前发布指针所指版本；未发布 → NOT_FOUND。缓存 30 秒，发布/回滚事件主动清除。 */
    AgentSnapshot currentPublished(String agentId);
    record AgentSnapshot(String versionId, String agentId, int versionNo, String workspaceId,
                         String ownerId, String contentHash, AgentSpec spec) {}
}
```

- Runtime 在 `createThread` 时把 `agentVersionId` 固定在 thread 上（M0 已有字段），每个 Turn 用 `AgentSnapshotApi.get` 取 spec：`instructions` 拼系统提示，`limits.maxSteps` 给 `AgentLoop`，`tools` 交给 `ToolCallContexts.forTurn`（工具白名单 + `agentToolConfig.mcp`），`approval` 交给 RT-07 的 `agent-config` 策略。
- Chat 新建会话时用 `currentPublished(agentId)` 取版本号；之后 Agent 再发布，已有会话不变，新会话用新版本（ADR-023「Runtime 只认快照」）。
- `AgentQueryApi.AgentVersionView` 与 `requirePublished` 删除；`McpToolProvider` 改为从 `ToolCallContext.agentToolConfig` 读 MCP 授权（与 [M1-04](M1-04-工具网关与其他.md) ENG-06 第 1 步是同一件事，ENG-06 排在本项之后）。

### REST

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| `GET /api/agents/{id}/draft` | `AGENT_READ` + `VIEW` | `{spec, revision, baseVersionId}` |
| `PUT /api/agents/{id}/draft` | `AGENT_WRITE` + `EDIT` | `{spec, revision}`；revision 不符 → 409；只做 JSON Schema 校验，引用校验在发布时做 |
| `POST /api/agents/{id}/validate` | `AGENT_WRITE` + `EDIT` | 对当前草稿跑 AST-02 全部检查，不发布；返回 `{ok, errors[]}` |
| `POST /api/agents/{id}/publish` | `AGENT_PUBLISH` + `EDIT` | `{revision, note?}` → `{versionId, versionNo}`；校验失败 422 `AGENT_PUBLISH_INVALID`（新错误码 42201），`data.errors=[{path, code, message}]` |
| `GET /api/agents/{id}/versions`、`/versions/{versionId}` | `AGENT_READ` + `VIEW` | 列表含 `versionNo, status, publishedAt, publishedBy, note, current` |
| `GET /api/agents/{id}/versions/diff?from&to` | 同上 | RFC 6902 JSON Patch 数组 |
| `POST /api/agents/{id}/rollback` | `AGENT_PUBLISH` + `EDIT` | `{versionId}`；目标版本须为本 Agent 的 `PUBLISHED`；**回滚也重跑 AST-02 的第 2、3 项**（引用可能已被删除或发布者已失去权限） |
| `POST /api/agents/{id}/disable`、`/enable` | `AGENT_PUBLISH` + `OWNER` | 停用后新会话不可选，已有会话的新 Turn 返回 423 `AGENT_DISABLED` |

发布、回滚、停用都 `@Audited`（`agent.publish`、`agent.rollback`、`agent.disable`），并在 afterCommit 发 Spring 事件 `AgentPublishedEvent(agentId, versionId)`，清 `currentPublished` 缓存；M2 起经 outbox 发 `tioka.agent.published.v1`（ADR-023）。

### 测试

| 用例 | 断言 |
| --- | --- |
| `AgentSpecContractTest` | record ↔ `agent-spec-v1.json` 字段一致；未知字段反序列化失败 |
| `CanonicalJsonTest` | 键顺序、数字格式不同的等价 JSON 哈希相同 |
| `AgentDraftIT#optimisticLock` | 两人基于同一 revision 保存，后者 409 |
| `AgentPublishIT#snapshotFrozen` | 发布后改草稿、改 MCP Server 的工具白名单，已发布版本 spec 与哈希不变 |
| `AgentPublishIT#threadPinsVersion` | 会话进行中重新发布，原会话下一个 Turn 仍用旧版本，新会话用新版本 |
| `AgentPublishIT#rollbackMovesPointer` | 回滚后 `currentPublished` 返回旧版本，旧版本行未被修改 |
| `V124MigrationIT` | 造 3 种旧数据（有草稿、只有已发布、grant 引用已删除），迁移后 spec 正确或被列入跳过清单 |

---

## AST-02 发布校验（P0，3 人天）

`PublishValidator` 依次执行一组 `PublishCheck` Bean（`@Order`），**收集全部错误再返回**，不在第一个错误处停。M3 的 TRU-01（授权书）、SCH-01（`output.schemaRef`）、MAS（委派）各自加一个 `PublishCheck`，不改本类（M1-00 第 6 节 F5）。

| 顺序 | 检查 | 错误码（`errors[].code`） |
| --- | --- | --- |
| 10 | spec 通过 JSON Schema；预留字段（`charter`、`output`、`subAgents`、`dataClearance`）必须为 null | `SCHEMA_INVALID`、`RESERVED_FIELD` |
| 20 | 引用存在：路由方案已发布且 `purpose=CHAT` 可用；MCP Server 存在且未熔断，`allowlist` 中每个工具名在 `mcp_tool` 中存在；知识库存在且未删除；Skill 版本存在；内置工具名在注册表中 | `REF_NOT_FOUND`、`REF_UNAVAILABLE` |
| 30 | **发布者对每个引用都有 USE**（`ResourceGuard.allows`）：`mcp`、`kb`、`skill`；路由方案要求 `model:read` | `REF_FORBIDDEN` |
| 40 | Skill：含脚本的 Skill 不能启用（沙箱在 M4，ADR-023） | `SKILL_SCRIPT_UNSUPPORTED` |
| 50 | `approval.toolPolicies` 的键都在本 spec 的工具集合中；`sandbox.networkAllowlist` 每项通过 `EgressGuard` 的主机格式校验 | `POLICY_UNKNOWN_TOOL`、`HOST_INVALID` |
| 60 | 空间开启发布审批时（设计 K，GOV-06 在 M4）：M1 不做，`PENDING_APPROVAL` 状态只预留 | — |

- 发布者 = 当前 Principal。发布后 Runtime 用 **Turn 发起人** 的权限执行工具（CON-01 第 3 步），第 30 项只防止「借 Agent 把自己没有的资源给别人用」。
- 校验在发布事务内执行，引用行加 `FOR SHARE` 锁，避免校验通过后引用被同时删除。

### 测试

| 用例 | 断言 |
| --- | --- |
| `PublishValidatorIT#collectsAllErrors` | 一份同时缺路由方案、引用无权 MCP 的 spec 返回 2 条错误 |
| `PublishValidatorIT#publisherNeedsUse` | builder 引用他人 PRIVATE 知识库 → `REF_FORBIDDEN`；被授予 USE 后可发布 |
| `PublishValidatorIT#reservedFieldRejected` | `charter` 非 null → `RESERVED_FIELD` |
| `PublishValidatorIT#rollbackRevalidates` | 引用的 MCP Server 被删除后回滚到引用它的版本 → 422 |

---

## ENG-04b G1 闸门基座（P0，5 人天）

M0 的闸门基座只有 PostgreSQL、Redis、双实例（同一 JVM 内两个 Spring 上下文）和按剧本回放的 LLM 桩。G1 用例还需要以下能力，必须在 W5 完成，否则 W6 起的 G1 用例无法开写。

| 能力 | 做法 | 使用者 |
| --- | --- | --- |
| Keycloak 容器 | `dasniko/testcontainers-keycloak:3.3.0`（Keycloak 24），单例；导入 `src/test/resources/keycloak/tioka-realm.json`：用户 `alice`（组 `/tioka/admins`）、`bob`（组 `/tioka/sales`）、`carol`（无组）；客户端 `tioka`（机密，PKCE）与 `tioka-api`（受众） | FND-05、G1-E2E |
| `KeycloakLogin` 测试工具 | 用 JDK `HttpClient` 走一遍授权码流程（取登录表单、POST 用户名密码、跟随重定向到 Tioka 回调），返回 `tka_` 访问令牌；不引入浏览器 | 同上 |
| MCP 桩 | `McpStubServer`（MockWebServer），按现有 MCP 客户端的传输方式实现 `initialize`、`tools/list`、`tools/call`；内置工具 `erp.query`（READ）、`erp.update`（WRITE）、`erp.slow`（每次 3 秒，用于崩溃与取消）；记录每次调用供断言 | CON-01、RT-06、RT-07、G1-E2E |
| LLM 桩扩充 | 剧本指令 `fail: {status, after_chars}`（MH-02）、`delay_ms`、`usage: {prompt, completion}`（缺省按字符数估算，GOV-03 计数用）；新增剧本 `mcp-three-steps`、`write-needs-approval`、`failover-mid-stream`、`kb-search` | MH-02、GOV-03、RT-07、KNW-02 |
| 第三个实例 | `DualInstance` 泛化为 `GateCluster.start(n)`，保留 `DualInstance` 作薄封装 | `G1ApprovalCrossInstanceGateIT`（A 挂起、B 审批、C 续跑） |
| 模拟崩溃 | `GateCluster.crash(i)`：先调测试钩子 `RuntimeTestHooks.freeze()`（停续租线程、阻塞正在执行的工具），再以 `tioka.runtime.release-on-shutdown=false` 关闭上下文，租约不释放。闸门 profile 把租约 TTL 设为 5 秒 | `G1RecoveryGateIT` |
| CI | gate 任务预拉 Keycloak 镜像；整个 gate 任务时长预算 ≤ 15 分钟，超出时按类拆成两个并行 job | — |
| 夜间真实模型 | `.github/workflows/nightly-real-model.yml`：每天 02:00（UTC+8）用仓库机密 `TIOKA_REAL_MODEL_BASE_URL/API_KEY/MODEL`（百炼或 DeepSeek 的 OpenAI 兼容端点）跑 `G1EndToEndGateIT`，不是必需检查；未配置机密则跳过（34 号计划风险表「每日一次真实模型夜间任务」） | G1-E2E |

`RuntimeTestHooks` 只在 `gate` profile 下注册（`@Profile("gate")`），ArchUnit 规则 `testHooksOnlyInGateProfile` 守护。

验收：`GateInfraSmokeIT` 一次启动 Keycloak + 3 实例 + 两个桩，完成一次 Keycloak 登录与一次 MCP 工具调用；`GateCluster.crash` 后另一实例在 10 秒内看到租约过期。

---

## G1-E2E G1 端到端用例（P0，2 人天，W12）

34 号计划对 G1 的定义是一条完整链路，现有 5 个 G1 骨架各测一段，没有一条把它们串起来。新增 `G1EndToEndGateIT`（骨架已随本 PR 提交），按下列步骤在 3 实例集群上执行，任何一步失败即闸门不过：

| 步 | 操作 | 断言 |
| --- | --- | --- |
| 1 | `alice` 经 Keycloak 登录（实例 A） | 得到 `tka_` 令牌；`/me` 含 `tenant_admin` |
| 2 | alice 创建 Agent：路由方案用 LLM 桩、工具 `erp.query` + `erp.update`、`toolPolicies.erp.update=AUTO`；发布 | 返回 `versionNo=1`；审计有 `agent.publish` |
| 3 | 给空间 `ws_sales` 配 `tokens_month` 上限；`bob` 经 Keycloak 登录（实例 B） | `bob` 是 `ws_sales` 的 member |
| 4 | bob 用该 Agent 新建会话并发送，剧本 `mcp-three-steps`（3 次工具调用） | HTTP 202 且 < 500ms；在实例 C 订阅 SSE，收到 3 对 `tool_call/tool_result` 与最终消息；MCP 桩记录 3 次调用 |
| 5 | bob 再发一条，剧本带 `erp.slow`；执行中在实例 A 调 `/stop` | Turn `CANCELLED`；SSE 收到 `turn.cancelled` |
| 6 | 查 `GET /api/system/quotas?scopeType=workspace&scopeId=ws_sales` | `tokens_month.used` = 桩返回 usage 之和（允许 Redis 实时值与 `model_usage` 之和完全相等） |
| 7 | alice 查审计 `GET /api/system/audit?resourceType=tool` | 有 bob 的 `tool.invoke` 3 条以上，`outcome=SUCCESS` |
| 8 | 无 `erp` MCP 授权的 Agent 尝试调用 `erp.update` | 模型收到拒绝结果；审计 `tool.invoke DENIED` |

步骤 8 与 `G1PermissionGateIT` 有重叠，保留在这里是为了确认端到端链路上拒绝也成立。夜间真实模型任务跑的是第 1〜7 步（真实模型不一定按剧本调工具，第 4 步只断言「至少 1 次工具调用且 Turn COMPLETED」）。

---

## FE-02 Agent 分区编辑器、发布错误定位与版本（P0，前端 5 人天）

挂靠本文件理由：M1-00 设计列已指向 05；依赖 AST-01/AST-02 已落地的草稿 / 校验 / 发布 / 版本 / diff / 回滚 REST。现状：`Agents.vue` 仍调已废弃的 `/drafts` + `/versions/{id}/publish`，无分区表单、无 422 path 定位、无版本列表。**不重做 AST**，只改 `tioka-ui`。

### 目标

1. 按 `agent-spec-v1.json` **分区表单**编辑草稿（`GET/PUT …/draft`，乐观锁 `revision`）。
2. 发布失败（422 `AGENT_PUBLISH_INVALID`）按 `errors[{path,code,message}]` **高亮分区/字段并滚动**。
3. **版本列表** + **diff**（RFC 6902）+ **回滚确认**。

### 页面与路由

**改造现有** `/agents` → `Agents.vue`（列表 + 选中后右侧/下方编辑器），**不新增** `/agents/:id`。

理由：Shell 已有「Agent」入口；G1 手工演示与 Chat 选 Agent 均从列表起步；同页切换 agent 即可完成「创建 → 填分区 → 发布 → 回滚」，减少路由与状态扩散。若日后要深链分享编辑页，再加子路由，本项不做。

布局要点：

| 区 | 内容 |
| --- | --- |
| 列表卡 | 现有：创建、表格、选中编辑 |
| 编辑器卡 | `el-tabs` 九分区；顶栏：保存草稿 / 校验 / 发布；侧栏或折叠：版本列表 |
| 版本区 | 版本表（`versionNo/status/publishedAt/note/current`）；选两条看 diff；对非 current 的 `PUBLISHED` 行「回滚」 |

### 分区 ↔ Schema

| Tab | JSON Pointer 前缀 | 可编辑字段（M1） |
| --- | --- | --- |
| Profile | `/profile` | `name`、`description`、`icon`、`tags[]`、`kind`（CHAT/TASK/READONLY） |
| Instructions | `/instructions` | `sections[{key,text}]`（至少可增删一行） |
| Model | `/model` | `routingPlanId`（下拉已发布 CHAT 方案）、`allowUserOverride` |
| Tools | `/tools` | `builtin[]`（文本多选或逗号输入）；`mcp[{serverId,allowlist[]}]`（选 MCP Server + 工具多选）；`flows`/`business` 固定 `[]` 只读提示「M3」 |
| Skills | `/skills` | `[{skillVersionId, mode: ALWAYS\|MANUAL}]` |
| Knowledge | `/knowledge` | `kbIds[]`、`mode`（TOOL/AUTO_RETRIEVE） |
| Sandbox | `/sandbox` | `level`（READ/EDIT/FULL）、`networkAllowlist[]` |
| Approval | `/approval` | `toolPolicies`（工具名 → auto/approve/deny）、`assignee` |
| Limits | `/limits` | `maxSteps`（默认 20）、`maxTokensPerTurn` |

预留字段 `charter` / `output` / `subAgents` / `dataClearance`：**UI 不展示**；`PUT draft` / `publish` 前强制写 `null`（避免旧草稿脏值触发 `RESERVED_FIELD`）。`schemaVersion` 固定 `1`，隐藏。

选项数据来源（只读列表，不新增后端）：`GET /api/model/routing-plans`（`published`）、`GET /api/mcp/servers` + `…/tools`、`GET /api/skills`（取已发布版本 id）、`GET /api/knowledge/bases`。内置工具名无专用列表 API 时允许自由输入，发布时由 AST-02 校验。

### API（对齐 AST-01，前端封装）

| 方法与路径 | 用途 |
| --- | --- |
| `GET /api/agents/{id}/draft` | 进入编辑：填 `spec` + `revision` |
| `PUT /api/agents/{id}/draft` | `{spec, revision}` → 新 `DraftView`；409 = 乐观锁冲突，提示刷新 |
| `POST /api/agents/{id}/validate` | 不发布；预览 `errors[]` |
| `POST /api/agents/{id}/publish` | `{revision, note?}` → `{versionId, versionNo}` |
| `GET /api/agents/{id}/versions` | 版本列表 |
| `GET /api/agents/{id}/versions/diff?from&to` | JSON Patch 数组 |
| `POST /api/agents/{id}/rollback` | `{versionId}` |

422 时响应 `code=42201`，`data.errors=[{path,code,message}]`。`http.ts` 的 `ApiError` **须透传 `data`**（否则无法定位）。落点：`tioka-ui/src/views/agentEditor.ts`（纯函数 + API 封装，对齐 FE-01 `mcpToolRisk.ts`）+ 改写 `Agents.vue`。

### UX

| 场景 | 行为 |
| --- | --- |
| 保存草稿 | 带当前 `revision`；成功回写 `revision`；409 提示「他人已保存，请重新加载草稿」并 `GET draft` |
| 发布失败 | 解析 `data.errors`；按 path 首段映射 Tab（如 `/model/…` → Model）；高亮对应表单项；`scrollIntoView` 第一条；顶栏列出全部错误可点击跳转 |
| 版本 / diff | 勾选 `from`/`to` 拉 diff，只读展示 op/path/value |
| 回滚 | `ElMessageBox.confirm` 后调 rollback；失败同样走 path 定位（回滚会重跑 AST-02） |

### 测试（vitest 最小集）

| 用例 | 断言 |
| --- | --- |
| 保存草稿 | mock `api`：`PUT …/draft` body 含 `spec`+`revision`；成功后 revision 更新 |
| 发布失败多条定位 | 422 `data.errors` 两条不同 path → `mapPublishErrorsToSections` 得到两个分区；`firstErrorPath` 指向第一条 |
| 回滚调 API | `rollbackAgent(api, id, versionId)` 发出 `POST …/rollback` 且 body `{"versionId":…}` |

不强制挂载整页 Vue（对齐 FE-01 抽纯函数测 API/映射）；可选组件冒烟不计入完成标准。

### 完成标准

- [ ] `/agents` 分区表单可编辑九分区；预留字段不展示且提交为 null
- [ ] 保存草稿走 `PUT …/draft` 乐观锁；409 可感知并刷新
- [ ] 发布 / 校验失败按 path 高亮分区并滚动；`ApiError` 透传 `data.errors`
- [ ] 版本列表 + diff + 回滚确认可用
- [ ] vitest：保存草稿、多错误定位、回滚调 API 绿；`npm test` / `npm run typecheck` 绿
- [ ] 未改 AST 后端与已合并迁移；仅前端

## 完成标准

- 已发布版本不可变，会话固定版本，发布校验拒绝借 Agent 越权（AST-01、AST-02 测试全过）。
- 旧 `agent_version` 绑定列、`agent_sandbox`、`mcp_grant` 已删除，`AgentQueryApi.AgentVersionView` 无引用。
- `GateInfraSmokeIT` 绿；G1 全部 8 个闸门用例在 CI 运行（见 [M1-00](M1-00-M1实施计划.md) 第 5 节）。
- Agent 页可分区编辑草稿、按 path 定位发布错误、版本 diff/回滚（FE-02）。

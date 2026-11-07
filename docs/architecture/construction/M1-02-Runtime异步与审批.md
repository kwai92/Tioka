# M1-02 — Runtime 异步派发、审批与对外协议

- 负责人：BE-B（RT-06〜08）、BE-C（CHT-02、CHT-03）、FE（HCI-02、HCI-04、FE-01、审批卡片）
- 工作项：RT-06、RT-07、RT-08、CHT-02、CHT-03、HCI-02、HCI-04、FE-01
- 约束：ADR-016、ADR-022、ADR-028、ADR-029 第 1〜7、16、30 条
- 前置：[M0-03](M0-03-Runtime内核.md)；FND-04（审批权限）
- 迁移：V114、V115（索引列，CHT-02）、V126（DROP `chat_message`，CHT-03；V116 预留未用）
- 排期与人天：以 [M1-00 实施计划](M1-00-M1实施计划.md) 为准；CHT-02、CHT-03 由 BE-C 负责

---

## RT-06 跨实例派发与崩溃回收（P0，8 人天）

### 派发

M0 中 Turn 在接收实例的本地线程池执行。M1 改为任意实例执行：

```text
startTurn 事务提交后（afterCommit）：
   XADD rt:turns * turnId {id} threadId {threadId}
每个实例的 TurnDispatcher（1 个读线程）：
   loop:
     free = maxConcurrentTurns - running
     if free == 0: 等待槽位释放（Semaphore），continue
     msgs = XREADGROUP GROUP rt-workers {instanceId} COUNT min(free, 16) BLOCK 2000 STREAMS rt:turns >
     for m in msgs:
        XACK rt:turns rt-workers m.id          // 数据库是事实来源，消息只是「去看看」的提示
        submit(TurnRunner.run(m.turnId))       // TurnRunner 内部 tryAcquire 锁 + acquire 租约，失败即返回
```

- 消费组在启动时创建：`XGROUP CREATE rt:turns rt-workers $ MKSTREAM`（已存在忽略 BUSYGROUP 错误）。`XADD` 带 `MAXLEN ~ 100000`。
- 同一 thread 上一个 Turn 结束时，`TurnRunner` 在 finally 里查 `nextQueued(threadId)`，有则重新 `XADD`。
- **兜底扫描** `QueuedSweeper`（每 15 秒，任一实例，`pg_try_advisory_lock` 保证同一时刻只有一个实例在扫）：

```sql
SELECT t.id, t.session_id FROM runtime_turn t
 WHERE t.status = 'QUEUED' AND t.update_at < now() - interval '15 seconds'
   AND NOT EXISTS (SELECT 1 FROM runtime_turn r WHERE r.session_id = t.session_id AND r.status = 'RUNNING')
   AND t.seq = (SELECT MIN(seq) FROM runtime_turn q WHERE q.session_id = t.session_id AND q.status = 'QUEUED')
 LIMIT 100;
```

  对每条执行 `SET rt:dispatched:{turnId} 1 NX EX 15`，成功才重新 `XADD`，避免重复投递风暴。Redis 数据全部丢失时，这一步把队列从数据库重建出来。
- `TurnScheduler`（M0 的本地调度）删除，`startTurn` 不再直接提交本地线程池。

### 崩溃回收（替换 M0 的 LeaseReaper 第二步）

V114：`ALTER TABLE runtime_turn ADD COLUMN recover_count INT NOT NULL DEFAULT 0;`

```text
对每个刚被置为 INTERRUPTED 的 Turn（M0 已有的第一步 SQL）：
  if recover_count >= tioka.runtime.max-recover (默认 2): → FAILED(LEASE_LOST)，发 turn.failed
  last = 本 Turn 未作废的最后一个 item
  if 存在 rt:cur:{threadId}（有流式中的 assistant 消息）:
      发 item.discarded{seq = 预占 seq}；删除 rt:cur:{threadId}
  if last.type == tool_call 且无对应 tool_result:
      对每个未完成调用写 tool_result{ok:false, output:"执行实例中断，工具结果丢失，未重放"}   // 不重放工具（ADR-016）
  INTERRUPTED → QUEUED，recover_count + 1，XADD rt:turns
```

恢复执行时 `AgentLoop` 从 `step_count + 1` 开始；`ContextBuilder` 照常从 item 重建上下文，因此无需额外保存循环状态。回收写入使用独立的 `lease_version + 1`（`UPDATE … SET lease_version = lease_version + 1 … WHERE status = 'INTERRUPTED'`），保证原执行者若「假死复活」也写不进去。

### 用户被禁用

`TurnRunner` 每步 `checkpoint()` 中每 60 秒重新加载一次 Principal（ADR-029 第 6 条）；用户被禁用或已不是空间成员 → 以 `CANCELLED(reason=principal_revoked)` 结束。

### 测试

| 用例 | 断言 |
| --- | --- |
| `DispatchIT#anyInstanceExecutes` | 在 A 上 `startTurn`，关闭 A 的 Dispatcher，B 执行完成 |
| `DispatchIT#threadSerialized` | 同一 thread 连发 3 条，执行顺序 = seq，任何时刻最多 1 个 RUNNING |
| `QueuedSweeperIT#rebuildAfterRedisFlush` | 排队中 `FLUSHALL`，15 秒内全部被执行 |
| `RecoveryGateIT`（G1） | Turn 执行工具时杀掉 A：B 接管，补「结果丢失」tool_result，最终 COMPLETED；`recover_count=1`；前端收到 `item.discarded` |
| `RecoveryIT#giveUpAfterMaxRecover` | 连续中断 3 次 → FAILED(LEASE_LOST) |

---

## RT-07 审批挂起与续跑（P0，8 人天）

### 审批策略

`runtime-api` 新增 SPI：

```java
package com.tioka.runtime.api.approval;

public interface ApprovalPolicy {
    String code();                                         // 例 "agent-config"、"always"、"never"
    Decision evaluate(ApprovalRequestContext ctx);
    record ApprovalRequestContext(Principal principal, String agentVersionId, String toolName,
                                  Map<String, Object> args, ToolRisk risk, Map<String, String> options) {}
    sealed interface Decision permits Allow, Deny, RequireApproval {}
    record Allow() implements Decision {}
    record Deny(String reason) implements Decision {}
    record RequireApproval(Assignee assignee, Duration ttl, String reason) implements Decision {}
    record Assignee(List<String> userIds, List<String> roleCodes) {}   // 都为空 = Turn 发起人
}
public enum ToolRisk { READ, WRITE, DESTRUCTIVE, EXTERNAL_SEND }
```

`ToolProvider.ToolDescriptor` 增加 `ToolRisk risk` 字段（新 record 构造器，旧三参构造器保留并默认 `READ`）；MCP 工具从 `mcp_tool` 新列 `risk`（V114，默认 `WRITE`）取值，管理员可在 MCP 页修改。

默认策略 `agent-config`：读 Agent 版本快照 `AgentSpec.approval.toolPolicies`（[M1-05](M1-05-Agent资产与G1闸门基座.md) AST-01，不另建配置表）`{"<toolName>": "auto" | "approve" | "deny"}`；未配置的工具按风险：`READ → auto`，`WRITE`、`DESTRUCTIVE`、`EXTERNAL_SEND → approve`。`TurnOptions.approvalPolicy` 可覆盖为 `never`（仅系统内部调用，需 `SYSTEM` 主体）或 `always`。

向前兼容（M1-00 第 6 节 F3、F4）：`ApprovalRequestContext` 只能经 `ApprovalRequestContexts.from(turn, call, descriptor)` 构造，调用方不直接 `new`。M3 的 `autonomy` 策略（ADR-034）会在 record 上加 `actionClass`、`changeSetTotals` 等字段，届时只改这个工厂。`runtime_approval` 的 `subject_type`、`kind` CHECK 约束在 M3 迁移中放宽，M1 不预加取值。

### 执行循环中的处理

```text
一步中模型返回调用 c1..cn：
  先写全部 tool_call item
  对每个调用 evaluate：
     Allow   → 放入立即执行集合
     Deny    → 写 tool_result{ok:false, output:"策略拒绝：" + reason}
     Require → 放入待审批集合
  执行立即执行集合（并行），写结果
  if 待审批集合非空：
     事务内（校验 lease_version）：
        每个调用插入 runtime_approval(subject_type='turn', subject_id=turnId, item_id=tool_call item id,
                                      kind='tool', tool_name, args_json, policy, assignee_json, status='PENDING',
                                      expires_at = now() + ttl（默认 24h）)
        每个调用写一个 card item：{approvalId, toolName, args, risk, reason}
        Turn RUNNING → WAITING_APPROVAL，lease_until=NULL
     发 approval.requested（每个审批一条）、turn.waiting
     释放会话锁；结束本次执行（不占 worker、不占并发配额）
```

### 提交审批

```java
// RuntimeApi.decideApproval 实现
@Transactional
public void decideApproval(String approvalId, ApprovalDecision d) {
    RuntimeApprovalDO a = approvals.findForUpdate(approvalId);          // SELECT … FOR UPDATE
    guard 1：调用者有 approval:decide，且是 assignee（userIds 含本人，或 roles 有交集；空 = Turn 发起人）
    guard 2：a.status != PENDING → 同结果幂等返回；不同结果 409 CONFLICT
    guard 3：kind=tool 且 answer 中带修改后的参数 → 用工具 inputSchema 校验，不合法 400
    approvals.decide(approvalId, APPROVED/REJECTED, principal.userId, answer, comment)
    audit(approval.decide)
    afterCommit: events.publish(approval.decided)；handlers.get(a.subjectType).onDecided(a)
}
```

`ApprovalSubjectHandler` SPI（`runtime-api`）：

```java
public interface ApprovalSubjectHandler {
    String subjectType();                     // turn / flow_node_run / orch_run
    void onDecided(ApprovalView approval);    // 必须幂等
}
```

`TurnApprovalHandler`（runtime-biz）：若该 Turn 已无 `PENDING` 审批 → `WAITING_APPROVAL → QUEUED`（`UPDATE … WHERE status='WAITING_APPROVAL'`，0 行即已被处理）并 `XADD rt:turns`。

M1 中 handler 在提交审批的实例上 afterCommit 同步调用；若该实例在提交后、调用前崩溃，`ApprovalResumeSweeper`（每 30 秒）兜底：查 `WAITING_APPROVAL` 且无 `PENDING` 审批的 Turn，同样置 QUEUED。M2 改为经 outbox 投递 `tioka.runtime.approval_decided.v1`。

### 续跑

`AgentLoop` 启动时检查「最后一步存在没有 tool_result 的 tool_call」：

| 审批状态 | 动作 |
| --- | --- |
| `APPROVED` | 用 `answer.args`（若有）或原参数调用工具，写 tool_result；`content.approvedBy` 记录审批人 |
| `REJECTED` | tool_result `{ok:false, output:"用户拒绝执行：" + comment}` |
| `EXPIRED` | tool_result `{ok:false, output:"审批超时，未执行"}` |

然后进入下一步模型调用。工具实际执行时的 Principal 仍是 Turn 发起人（审批人只是放行，ADR-015）。

### 过期

`ApprovalExpirer`（每分钟）：`UPDATE runtime_approval SET status='EXPIRED' WHERE status='PENDING' AND expires_at < now() RETURNING …`，对每条调用 handler。

### 取消

Turn 在 `WAITING_APPROVAL` 时 `interrupt` → `CANCELLED`，其 `PENDING` 审批一并置 `EXPIRED`（`decided_by='system'`），发 `approval.decided`。

### REST

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| `POST /api/runtime/approvals/{id}/decision` | `approval:decide` | `{approved, answer?, comment?}` |
| `GET /api/runtime/approvals/{id}` | 同上 | `ApprovalView` |
| 审批台列表 | — | 见 [M2-04](M2-04-审批台与轨迹.md) |

### 测试

| 用例 | 断言 |
| --- | --- |
| `ApprovalFlowIT#approveResumes` | 34 号验收：WRITE 工具触发审批，Turn 进入 WAITING_APPROVAL；批准后原 Turn 继续并 COMPLETED |
| `ApprovalFlowIT#rejectFeedsModel` | 拒绝后模型收到拒绝结果并给出最终回答 |
| `ApprovalFlowIT#mixedStep` | 一步中 1 个 READ + 1 个 WRITE：READ 立即执行，WRITE 等审批 |
| `ApprovalFlowIT#editedArgsValidated` | 修改参数不符合 schema → 400；符合 → 按新参数执行 |
| `ApprovalFlowIT#nonAssignee403` / `#doubleDecideIdempotent` | — |
| `ApprovalFlowIT#waitingDoesNotHoldConcurrency` | WAITING_APPROVAL 的 Turn 不计入并发配额（ADR-029 第 7 条） |
| `ApprovalCrossInstanceGateIT`（G1） | A 上挂起，B 上审批，C 上续跑 |

---

## RT-08 对外协议收口（P0，4 人天）

1. `RuntimeApi` 全部方法实现（`fork` 抛 `NOT_IMPLEMENTED`，M3）。
2. `runtime-api` 新增 `TurnAwaiter`，给需要同步结果的调用方（M1 的 Flow Agent 节点、M2 前的编排）使用：

```java
public interface TurnAwaiter {
    /** 等待终态或 WAITING_*；超时返回当前状态，不取消 Turn。 */
    TurnView await(String turnId, Duration timeout);
    /** 取本 Turn 最后一条 assistant message 的文本。 */
    Optional<String> finalText(String turnId);
}
```

实现：订阅该 thread 的 Redis 事件流 + 每 5 秒查库兜底。
3. 改造调用方：

| 调用方 | 现状 | 改为 |
| --- | --- | --- |
| Chat | `LegacySessionAdapter.append` | CHT-02 |
| Flow Agent 节点、LLM 节点 | 直连 LLM / `RuntimeSessionApi` | Agent 节点：`createThread(origin=flow, metadata={runId,nodeId})` + `startTurn` + `TurnAwaiter.await`（ADR-029 第 11 条）；LLM 节点走 `ModelGateway`（FLW-02） |
| IM（飞书文本） | `RuntimeSessionApi` | `startTurn(origin=im)`，M2 CON-02 完整改造 |
| 编排 | 无执行 | M2 ORC-03 |

4. 删除 `RuntimeSessionApi`、`LegacySessionAdapter`、`PartView` 及 `runtime-api/part` 包。ArchUnit `onlyRuntimeApiExecutesAgents` 生效。
5. 旧回归套件 `GateRegressionSuiteIT`（W5-15 遗留，调用 `/api/runtime/sessions/*/append`、`DIFFICULTY` 路由、`/api/model/router/select`）随本项删除：其中仍有价值的断言（steer、取消后追加 409、冻结）已由 G0、G1 闸门用例覆盖，对照表写进 PR 描述。

测试：`RuntimeApiContractTest`（每个方法的参数校验与错误码）；ArchUnit 规则绿；`grep -r RuntimeSessionApi` 为空。

---

## CHT-02 Chat 发送异步化（P0，3 人天）

| 方法与路径 | 请求 | 响应 |
| --- | --- | --- |
| `POST /api/chat/conversations` | `{title?, agentVersionId}` | `{id, threadId, title}`；内部 `createThread(origin=chat, originRef=conversationId)` |
| `POST /api/chat/conversations/{id}/messages` | `{text, clientMsgId, attachments?}` | HTTP 202 `{turnId, threadId, eventsUrl}` |
| `GET /api/chat/conversations/{id}/messages?afterSeq&limit` | — | `ItemView[]`（转调 `listItems`） |
| `POST /api/chat/conversations/{id}/stop` | — | 转调 `interrupt(当前活动 Turn)` |

`ChatService.send` 只做：校验会话归属 → `runtime.startTurn` → 更新 `chat_conversation.last_active_at` 与标题（首条消息时取前 30 字）。不再等待模型；ArchUnit `noModelCallOnRequestThread` 已守护。

测试：`ChatAsyncIT#sendReturnsBeforeModel`（模型桩延迟 5 秒，接口 200ms 内返回）；`ChatAsyncIT#idempotentClientMsgId`。

---

## CHT-03 消息去双份（P1，2 人天）

- V115（CHT-02 已合入）：`chat_conversation` 加 `last_active_at TIMESTAMPTZ`、`last_preview VARCHAR(200)` 并回填索引。
- V126（本项）：迁移前校验每个会话的 `runtime_item` 数 ≥ 原 `chat_message` 数，不满足则失败并输出会话 ID；通过后 `DROP TABLE chat_message`（历史已在 V104 转入 `runtime_item`）。勿改已合并的 V115。
- `last_preview` 由 `TurnRunner` 在 Turn 完成时通过 `ConversationIndexListener`（监听 Spring 事件 `TurnCompletedEvent`，origin=chat 时）更新。
- `ChatStore` 中消息相关方法删除。

测试：`ChatIndexIT#previewUpdatedAfterTurn`；`V115MigrationIT`（覆盖 V126 校验与 DROP）。

---

## HCI-02 前端续传与对话状态（P0，前端 6 人天）

M0 已有 `streamThread` 与 thread store，M1 补齐：

| 场景 | 行为 |
| --- | --- |
| 打开会话 | `GET …/messages?afterSeq=0` 填充 store → `streamThread(after = 最大 seq)` |
| 网络断开 | `fetchEventSource` 自动重连（退避 1s、2s、4s… 最长 30s），带 `Last-Event-ID`；顶栏显示「重连中」 |
| 页面隐藏后恢复 | 不断流（`openWhenHidden: true`）；若超过 `sse-timeout` 被服务端关闭，重连即可 |
| 令牌过期 | 收到 401 时先刷新令牌再重连；刷新失败跳登录 |
| `item.discarded` | 从 store 删除该 item，显示一次「模型已切换，正在重新生成」提示 |
| 发送 | 生成 `clientMsgId = crypto.randomUUID()`；本地先插入「发送中」的用户消息，收到服务端 `item.completed`（role=user）后以服务端为准替换 |
| 停止 | 按钮调 `/stop`；收到 `turn.cancelled` 后结束输入状态 |

测试（vitest，用可控的 `ReadableStream` 模拟 SSE）：断线重连不重复文本；discarded 后 item 消失；乐观插入的用户消息被替换而非重复。

---

## HCI-04 Item 渲染快照与审批卡片（P1，前端 6 人天）

- 新目录 `src/items/`：`ItemRenderer.vue`（按 `itemType` 分发）+ 每种类型一个组件：`MessageItem`、`ReasoningItem`（默认折叠）、`ToolCallItem`（与对应 `ToolResultItem` 成对折叠显示）、`CitationItem`、`CardItem`、`StatusItem`、`ErrorItem`、`UnknownItem`（未知类型占位，不报错）。旧 `parts/PartRenderer.vue`、`normalizePart.ts` 删除。
- `CardItem` 中 `kind=tool` 的审批卡片：显示工具名、风险标签、参数（JSON 可编辑，提交前按 inputSchema 用 `ajv` 校验）、批准/拒绝按钮、备注；已决定的显示结果与审批人。
- 快照：`src/items/__fixtures__/{type}.json` 每种类型至少 2 个样例（含边界：超长文本、截断的工具结果、未知类型），`ItemRenderer.spec.ts` 对每个样例 `toMatchSnapshot()`。
- 契约同步：fixtures 由脚本 `scripts/gen-item-fixtures.mjs` 从 `runtime-event-v1.json` 的 examples 生成并校验，契约改了而 fixtures 没更新时 CI 失败。

---

## FE-01 MCP 工具风险等级编辑（P1，前后端合计 1 人天）

挂靠本文件理由：M1-00 设计列已指向 02；RT-07 已规定 `mcp_tool.risk`（V114 四态）与管理员可在 MCP 页修改。M1-04 只涉及 `ToolDescriptor` 字段演进，不承载本项 UI/写接口。现状：列与读路径已有，**无写接口、刷新会冲掉已配置 risk、MCP 页无编辑列**。

### 目标

管理员在 MCP 页编辑 `mcp_tool.risk`，取值对齐 V114 / `ToolRisk`：`READ` | `WRITE` | `DESTRUCTIVE` | `EXTERNAL_SEND`。供 RT-07 默认审批策略消费。

### API

| 方法与路径 | 权限 | 请求 | 响应 |
| --- | --- | --- | --- |
| `PATCH /api/mcp/servers/{serverId}/tools/{toolName}` | `mcp:write`（`Permissions.MCP_WRITE`，与现有 `McpAdminController` 一致） | `{"risk":"READ\|WRITE\|DESTRUCTIVE\|EXTERNAL_SEND"}` | `Tool`（含更新后的 `risk`） |

错误码（沿用惯例，不新增）：

| 条件 | 错误码 |
| --- | --- |
| `serverId` / `toolName` 不存在 | `NOT_FOUND` |
| `risk` 缺省、空白或非四态之一 | `BAD_REQUEST` |
| 无 `mcp:write` | `FORBIDDEN` |

实现落点：`McpAdminController` → `McpAdminService.updateToolRisk` → `McpStore` / `McpRepository`（MyBatis-Plus `updateById` 或按 `server_id`+`name` 更新；禁止 JDBC）。不改已合并迁移；V114 已存在则只用。

### 刷新策略

`refreshTools` → `replaceTools`：

1. 替换前读出该 server 已有工具的 `name → risk` 映射。
2. 同名工具：**保留**已配置 `risk`（忽略发现结果里的 risk，若有）。
3. 新工具：默认 `WRITE`（与 V114 列默认一致）。
4. 已消失的工具随全量替换删除（现状行为不变）。

### UI

`tioka-ui/src/views/Mcp.vue` 工具预览区改为表格（或在现有列表旁加列）：

| 列 | 内容 |
| --- | --- |
| 名称 / 描述 | 现有信息；allowlist 勾选保留 |
| 风险 | `el-select` 四态；`@change` 即调 PATCH，成功后回写本地 `tools`；失败展示错误并回滚选项 |

### 测试

| 层 | 用例 | 断言 |
| --- | --- | --- |
| 前端 vitest | 改 risk 调 API | mock `api`/`fetch`：选择新 risk → 发出 `PATCH …/tools/{name}` 且 body `{"risk":…}` |
| 后端 IT | PATCH 成功 | 200，库中 `mcp_tool.risk` 更新；再次 GET tools 可见 |
| 后端 IT | 非法枚举 | `BAD_REQUEST` |
| 后端 IT | 无权限或 404 | viewer 无 `mcp:write` → `FORBIDDEN`；不存在的 tool → `NOT_FOUND` |
| 后端 IT（可并入上列） | 刷新保留 | 先 PATCH 为 `READ`，再 `refresh-tools`，同名工具仍为 `READ`；新工具为 `WRITE` |

### 完成标准

- [ ] `PATCH` 四态可写；非法枚举 / 缺失资源 / 无权限分别返回 `BAD_REQUEST` / `NOT_FOUND` / `FORBIDDEN`
- [ ] `refresh-tools` / `replaceTools` 同名保留已配置 `risk`；新工具默认 `WRITE`
- [ ] MCP 页工具表有 risk 列，改即 PATCH
- [ ] 前端 vitest「改 risk 调 API」绿；后端 IT 覆盖成功 / 非法枚举 / 无权限或 404
- [ ] 未改已合并迁移；无 `tenantId`；`*-biz` 不互依赖；ORM 仅 MyBatis-Plus

## 完成标准

- 杀进程后 Turn 被接管或标记失败（G1 `RecoveryGateIT`）。
- 审批卡片提交后原 Turn 继续（G1 `ApprovalCrossInstanceGateIT`）。
- Chat、Flow、IM 只用 `RuntimeApi`；HTTP 请求不再等模型返回。
- 断网重连后从断点继续（HCI-02 测试）。
- MCP 页可编辑工具风险且刷新不冲配置（FE-01）。

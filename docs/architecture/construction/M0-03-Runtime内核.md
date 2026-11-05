# M0-03 — Runtime 内核

- 负责人：BE-B（前端流式改造由 FE 负责）
- 工作项：RT-02、RT-01、RT-03、RT-04、RT-05，以及旧接口适配（按实施顺序）
- 约束：ADR-016、ADR-029 第 1〜5、9、15、30 条；契约 `runtime-api/kernel/*`、`runtime-event-v1.json`、V101
- 前置：[M0-01](M0-01-工程基线.md) ENG-11；[M0-02](M0-02-身份与空间.md) FND-01（需要 Principal）

## 0. M0 的边界（ADR-029 第 30 条）

| 能力 | M0 | M1（RT-06） |
| --- | --- | --- |
| Turn 在哪执行 | 接收 `startTurn` 的实例，本地有界线程池 | 任意实例，经 Redis Stream `rt:turns` 消费组派发 |
| 事件、取消、插话 | 经 Redis，跨实例成立 | 不变 |
| 实例崩溃 | 该实例上 RUNNING 的 Turn 停住；租约过期后由**任一实例**的回收器标为 `INTERRUPTED → FAILED(LEASE_LOST)` | 回收器改为重新入队续跑 |
| 审批 | 不支持；工具一律直接执行（与现状一致） | RT-07 |
| 模型调用 | 经 `ModelPort` 适配现有 `ModelRouterApi` + `LlmClientApi` | `ModelPort` 换成 `ModelGateway`（MH-01） |

## 1. 代码结构

全部新代码在 `tioka-module-runtime-biz` 的 `com.tioka.runtime.kernel` 下，旧 `RuntimeSessionService` 在本节完成后删除。

```text
com.tioka.runtime.kernel
├── api/
│   ├── ThreadController.java          # REST，见第 7 节
│   └── ThreadEventsController.java    # SSE
├── application/
│   ├── KernelRuntimeService.java      # implements RuntimeApi（M0 实现：createThread/startTurn/interrupt/steer/getTurn/listItems）
│   ├── TurnScheduler.java             # 本地有界线程池 + 同 thread 串行
│   ├── TurnRunner.java                # 单个 Turn 的执行（获取租约 → AgentLoop → 收尾）
│   ├── LeaseReaper.java               # 租约过期回收（@Scheduled）
│   └── LegacySessionAdapter.java      # implements RuntimeSessionApi，转调新内核
├── domain/
│   ├── AgentLoop.java                 # 多步工具循环，纯逻辑，依赖下面的端口
│   ├── LoopPorts.java                 # ModelPort / ToolPort / ItemSink / SignalPort 接口
│   ├── ContextBuilder.java            # item → 模型 messages
│   ├── EventId.java                   # 事件 ID 解析与比较
│   └── TurnTransitions.java           # 包装 TurnStatus.canTransitionTo，非法转换抛 IllegalStateException
├── infrastructure/
│   ├── persistence/ RuntimeThreadDO, RuntimeTurnDO, RuntimeItemDO
│   ├── mapper/      RuntimeThreadMapper, RuntimeTurnMapper, RuntimeItemMapper
│   ├── ThreadRepository / TurnRepository / ItemRepository
│   ├── redis/ SessionLock, EventStream, SignalBus, StreamPump
│   └── model/ LegacyModelPort.java    # M0 适配器
└── config/ KernelProperties, KernelAutoConfiguration
```

`runtime_session` 表即 thread（不改表名，ADR-029 第 1 条）；新建 thread 的 ID 用 `Ids.newId("thr")`。

## 2. 配置

```yaml
tioka:
  runtime:
    instance-id: ${HOSTNAME:local}       # worker_id 与锁的持有者标识
    executor:
      max-concurrent-turns: 64           # 本实例同时 RUNNING 的 Turn 上限
      queue-capacity: 256
    max-queued-per-thread: 5             # ADR-029 第 5 条
    lease-ttl: 30s
    lease-renew-interval: 10s
    loop:
      max-steps: 20
      tool-parallelism: 4
      tool-timeout: 60s
      repeat-call-limit: 3               # 同名同参数工具连续调用次数上限
    events:
      stream-maxlen: 10000              # ADR-029 第 3 条
      stream-ttl-running: 1h            # Turn 运行中每次写入续期
      stream-ttl-after-turn: 10m        # Turn 结束后保留 10 分钟（ADR-029 第 3 条）
      sse-heartbeat: 15s
      sse-timeout: 30m
```

---

## RT-02 thread / turn / item 存储（P0，4 人天）

### 迁移

V101 已建 `runtime_turn`、`runtime_item` 并删除 `parts_json`。为保住历史会话，调整顺序：

| 文件 | 内容 |
| --- | --- |
| `V101__m0_runtime_kernel.sql`（改） | 去掉 `DROP COLUMN parts_json`、`cancelled`、`steer_text` 三句（V101 尚未在任何环境执行，允许修改） |
| `V104__BackfillRuntimeItems.java`（新，`db/migration` 包下的 Flyway `JavaMigration`） | 逐会话读 `parts_json`，按顺序转成 item：`text` 且 role=user → `message/user`；role=assistant 且 `delta=false` → `message/assistant`；`tool_call`、`tool_result`、`error` 同名映射；delta 片段丢弃。每个旧 turnId 生成一条 `runtime_turn(status=COMPLETED, origin=chat, run_as_json='{"tenantId":"default","userId":"1","type":"USER"}')`。批量 500 会话一批 |
| `V105__m0_drop_legacy_runtime.sql`（新） | 上面三句 `DROP COLUMN` 与 `DROP TABLE runtime_idem` |

V104、V105 不随本项（RT-02，W1）合入，而是随本文末尾「旧接口适配与下线」（W4）与删除 `RuntimeSessionService` 的 PR 一起合入；在此之前新旧两套表并存，旧 Chat 路径继续可用（M0-01 ENG-11「迁移随工作项落地」）。

### DO 与仓储

```java
@TableName("runtime_turn")
public class RuntimeTurnDO extends MutableRecordDO {   // 无逻辑删除；审计列由基类提供
    @TableId private String id;
    private String sessionId; private Integer seq; private String status;
    private String origin; private String originRef; private String clientMsgId;
    private String runAsJson; private String optionsJson; private Integer stepCount;
    private String workerId; private Long leaseVersion; private LocalDateTime leaseUntil;
    private String errorCode; private String errorMessage;
    private Integer promptTokens; private Integer completionTokens;
    private LocalDateTime startedAt; private LocalDateTime endedAt;      // UTC
}
```

`RuntimeItemDO` 继承 `AppendOnlyDO`，`RuntimeThreadDO` 沿用现有 `runtime_session` 的 DO（`WorkspaceAwareDO`）。时间字段一律 `LocalDateTime`（UTC），对外视图用 `toInstant(ZoneOffset.UTC)` 转 `Instant`（M0-01 ENG-11「DO 基类与时间类型」）。

`TurnRepository` 必须提供以下方法，全部带 `lease_version` 条件的写入返回影响行数，调用方为 0 时抛 `BizException(LEASE_LOST)`：

```java
Optional<RuntimeTurnDO> findById(String id);
Optional<RuntimeTurnDO> findBySessionAndClientMsgId(String sessionId, String clientMsgId);
int countActive(String sessionId);                       // status IN (QUEUED, RUNNING)
int nextSeq(String sessionId);                           // SELECT COALESCE(MAX(seq),0)+1 … FOR UPDATE 在 session 行上
void insert(RuntimeTurnDO t);                            // 唯一键冲突（client_msg_id）→ DuplicateKeyException
/** QUEUED → RUNNING，成功返回新的 lease_version。 */
OptionalLong acquire(String turnId, String workerId, Instant leaseUntil);
int renew(String turnId, long leaseVersion, Instant leaseUntil);
int finish(String turnId, long leaseVersion, TurnStatus to, String errorCode, String errorMessage, Integer pt, Integer ct);
int incrementStep(String turnId, long leaseVersion);
int cancelQueued(String turnId);                         // QUEUED → CANCELLED，无租约
List<String> findExpired(Instant now, int limit);        // RUNNING 且 lease_until < now
Optional<RuntimeTurnDO> nextQueued(String sessionId);    // 最小 seq 的 QUEUED
```

```sql
-- acquire
UPDATE runtime_turn
   SET status='RUNNING', worker_id=#{workerId}, lease_version=lease_version+1,
       lease_until=#{leaseUntil}, started_at=COALESCE(started_at, now()), update_at=now()
 WHERE id=#{turnId} AND status='QUEUED'
RETURNING lease_version;

-- finish（其余 renew/incrementStep 同理带 lease_version 条件）
UPDATE runtime_turn SET status=#{to}, error_code=…, ended_at=now(), lease_until=NULL, update_at=now()
 WHERE id=#{turnId} AND lease_version=#{leaseVersion} AND status='RUNNING';
```

`ItemRepository.append`：一个事务内完成「校验租约 → 分配 seq → 插入」：

```sql
SELECT 1 FROM runtime_turn WHERE id=#{turnId} AND lease_version=#{lv} AND status='RUNNING' FOR SHARE;  -- 无行 → LEASE_LOST
UPDATE runtime_session SET last_item_seq = last_item_seq + 1, update_at=now() WHERE id=#{sessionId} RETURNING last_item_seq;
INSERT INTO runtime_item (session_id, seq, id, turn_id, step, type, role, content_json, token_count) VALUES (…);
```

用户输入 item 在 `startTurn` 事务里写（此时 Turn 为 QUEUED，不校验租约）。

流式中的 assistant 消息：在 `item.created` 时用 `reserveSeq(sessionId, turnId, lv)`（只执行前两句）预占 seq，`item.completed` 时用 `insertReserved(seq, …)` 插入。同一 thread 同时只有一个 RUNNING Turn（RT-01），因此预占不会交叉；崩溃留下的空洞 seq 无害，`listItems` 按 seq 排序跳过即可。

### `item.content_json` 结构（按 ItemType）

| type | role | content |
| --- | --- | --- |
| `message` | user / assistant / system | `{"text": "…", "attachments": [{"fileRef": "…"}]}` |
| `reasoning` | assistant | `{"text": "…"}` |
| `tool_call` | assistant | `{"callId": "call_x", "name": "kb_search", "arguments": {…}}` |
| `tool_result` | tool | `{"callId": "call_x", "name": "kb_search", "ok": true, "output": "…", "durationMs": 120, "truncated": false}` |
| `status` | system | `{"kind": "max_steps_reached" \| "repeat_call_stopped" \| "steer_applied" \| "cancelled", "detail": "…"}` |
| `error` | system | `{"code": "MODEL_ERROR", "message": "…"}` |

`tool_result.output` 超过 `tioka.runtime.loop.tool-output-max-chars`（默认 20000）时截断并置 `truncated=true`。

### 测试

| 用例 | 断言 |
| --- | --- |
| `ItemRepositoryIT#appendIsGapFreeUnderSingleWriter` | 同一租约连续追加 100 条，seq 为 1..100 |
| `ItemRepositoryIT#staleLeaseRejected` | 用旧 `lease_version` 追加抛 `LEASE_LOST` |
| `TurnRepositoryIT#acquireOnlyOnce` | 两个线程同时 `acquire` 同一 Turn，只有一个成功 |
| `TurnRepositoryIT#clientMsgIdIdempotent` | 同 `clientMsgId` 第二次插入冲突，`startTurn` 返回已有 Turn |
| `BackfillRuntimeItemsIT` | 用 V103 状态的库插入旧 `parts_json`，迁移后 item 数量与类型正确 |
| `TurnStatusContractTest`（已有） | 状态转换表 |

---

## RT-01 会话锁与 fencing（P0，3 人天）

### 设计

两层保护：Redis 锁保证同一 thread 同时只有一个 Turn 在执行；数据库 `lease_version` 保证锁丢失后旧执行者的写入一定失败（fencing）。

| Redis 键 | 值 | 说明 |
| --- | --- | --- |
| `rt:lock:{threadId}` | `{instanceId}:{turnId}` | `SET NX PX 30000` |

```java
public interface SessionLock {
    /** 成功返回 Handle；已被占用返回 empty。 */
    Optional<Handle> tryAcquire(String threadId, String turnId);
    interface Handle extends AutoCloseable {
        boolean renew();          // Lua：值相等才 PEXPIRE；失败说明锁已丢
        void close();             // Lua：值相等才 DEL
    }
}
```

```lua
-- renew.lua
if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) else return 0 end
-- release.lua
if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end
```

续期：`TurnRunner` 用共享的 `ScheduledExecutorService`（2 线程）每 `lease-renew-interval` 执行一次：先 `lock.renew()`，再 `turns.renew(turnId, lv, now+lease-ttl)`。任一失败 → 设置本 Turn 的 `lost` 标志，执行循环在下一个检查点退出且**不写任何终态**（终态由回收器或新持有者写）。

### 测试

| 用例 | 断言 |
| --- | --- |
| `SessionLockIT#mutualExclusion` | 两个 Handle 抢同一 thread，只有一个成功；释放后另一个可获取 |
| `SessionLockIT#releaseDoesNotDeleteOthersLock` | 锁过期被别人拿走后，旧 Handle 的 `close()` 不删除新锁 |
| `ConcurrencyIT#hundredThreadsDoNotBlock` | 100 个 thread 各发一个 Turn（模型桩延迟 500ms），总耗时 < 3s（本实例并发 64，两批）；旧实现会串行 50s |
| `FencingIT#staleWorkerWritesFail` | 手动把 `lease_version` +1 模拟被接管，原 Runner 的下一次 item 写入抛 `LEASE_LOST` 且 Turn 状态不被它改写 |

---

## RT-03 多步工具循环（P0，5 人天）

### TurnRunner 流程

```text
run(turnId):
  turn = turns.findById(turnId)
  lock = sessionLock.tryAcquire(threadId, turnId)  → 失败：保持 QUEUED，等当前 Turn 结束后由 TurnScheduler 再调度
  lv = turns.acquire(turnId, instanceId, now+ttl)   → 空（已被取消或被别人拿走）：释放锁，返回
  principalHolder.runAs(loader.reload(turn.runAs), () -> {  // 权限每个 Turn 开始时重新加载（ADR-029 第 6 条）
      events.publishTurn(turnId, RUNNING)
      result = agentLoop.run(ctx)
      turns.finish(turnId, lv, result.status, …)
      events.publishTurn(turnId, result.status)
  })
  finally: 停止续期；lock.close()；scheduler.onTurnFinished(threadId)  // 调度同 thread 下一个 QUEUED
```

任何未捕获异常：写 `error` item（若租约仍有效）→ `finish(FAILED, "INTERNAL", 截断到 500 字的消息)`。`LEASE_LOST` 异常：什么都不写，只记日志。

### AgentLoop

```java
public final class AgentLoop {
    public LoopResult run(LoopContext ctx) {
        List<Map<String, Object>> tools = toolPort.describe(ctx);
        Deque<String> recentCalls = new ArrayDeque<>();
        int promptTokens = 0, completionTokens = 0;
        for (int step = ctx.startStep(); ; step++) {
            ctx.checkpoint();                                   // 取消 → 抛 CancelledSignal；租约丢 → 抛 LeaseLost
            applySteers(ctx, step);                             // 插话写成 user message item + status(steer_applied)
            boolean last = step >= ctx.maxSteps();
            List<Map<String, Object>> messages = contextBuilder.build(ctx.threadId(), ctx.turnId());
            ModelPort.Result r = modelPort.chat(ctx, messages, last ? List.of() : tools,
                                                delta -> sink.delta(ctx, delta));   // 流式 delta 只进 Redis
            promptTokens += r.promptTokens(); completionTokens += r.completionTokens();
            if (r.reasoning() != null) sink.complete(ctx, step, REASONING, …);
            if (r.text() != null && !r.text().isBlank()) sink.completeMessage(ctx, step, r.text());
            turns.incrementStep(ctx.turnId(), ctx.leaseVersion());
            if (r.toolCalls().isEmpty()) return LoopResult.completed(promptTokens, completionTokens);
            if (last) { sink.status(ctx, step, "max_steps_reached"); return LoopResult.completed(…); }
            if (repeated(recentCalls, r.toolCalls(), ctx.repeatLimit())) {
                sink.status(ctx, step, "repeat_call_stopped");
                ctx.forceFinalAnswer();                          // 下一步不再给工具，促使模型总结
                continue;
            }
            for (ToolCall c : r.toolCalls()) sink.toolCall(ctx, step, c);   // 先全部写 tool_call item
            List<ToolOutcome> outs = toolPort.invokeAll(ctx, r.toolCalls()); // 并行，见下
            for (ToolOutcome o : outs) sink.toolResult(ctx, step, o);       // 按调用顺序写 tool_result
        }
    }
}
```

要点：

1. **最后一步**（`step == maxSteps`）不给工具，强制模型给出最终回答；仍返回工具调用时忽略并写 `status(max_steps_reached)`，Turn 记为 `COMPLETED`。
2. **并行工具**：`invokeAll` 用 `tool-parallelism` 大小的共享线程池（`TiokaExecutors.wrap` 传递 Principal），每个调用 `orTimeout(tool-timeout)`；超时或异常转为 `ok=false` 的结果，交给模型自行处理，不终止 Turn。
3. **重复调用检测**：键为 `name + 规范化 JSON 参数`，最近 `repeat-call-limit` 次完全相同即触发。
4. **参数解析失败**：`arguments` 不是合法 JSON → 不调用工具，直接写 `ok=false, output="arguments 不是合法 JSON：…"`。
5. **ContextBuilder**：按 seq 读取本 thread 所有未作废 item，映射为 OpenAI 格式：`message` → `{role, content}`；同一步的多个 `tool_call` 合并为一条 `assistant.tool_calls`；`tool_result` → `{role:"tool", tool_call_id, content}`；`status`、`error`、`reasoning` 不进上下文。Agent 版本的系统提示与 Skill 片段放在最前（沿用现有 `SkillInstallService.promptFragments`）。M0 不做压缩（RT-10 在 M3）。

### 端口

```java
public interface ModelPort {
    Result chat(LoopContext ctx, List<Map<String, Object>> messages, List<Map<String, Object>> tools, Consumer<Delta> onDelta);
    record Delta(String text, String reasoning) {}
    record Result(String text, String reasoning, List<ToolCall> toolCalls, int promptTokens, int completionTokens, String modelRef) {}
}
public interface ToolPort {
    List<Map<String, Object>> describe(LoopContext ctx);                 // M0：ToolGateway.asOpenAiTools
    List<ToolOutcome> invokeAll(LoopContext ctx, List<ToolCall> calls);  // M0：ToolGateway.call；M1 CON-01 加鉴权
}
```

`LegacyModelPort`：每个 Turn 调一次 `ModelRouterApi.select(threadId, routingPlanId, 首条用户文本)` 并在 `LoopContext` 缓存选择结果，之后每一步复用（与 MH-01 的轮次冻结语义一致），调用 `LlmClientApi.streamComplete`。取消时通过 `SignalBus` 设置的标志让 `onDelta` 抛 `CancelledSignal`，从而中断 HTTP 流。

### 测试（`AgentLoopTest` 用内存端口；`*IT` 用模型桩）

| 用例 | 断言 |
| --- | --- |
| `AgentLoopTest#threeToolCallsThenAnswer` | 剧本 `three-tools.yaml`：3 个 tool_call、3 个 tool_result、1 条 assistant message，状态 COMPLETED |
| `AgentLoopTest#parallelToolsKeepOrder` | 一步返回 2 个调用，结果 item 顺序与调用顺序一致；耗时约等于单个工具 |
| `AgentLoopTest#maxStepsStops` | `max-steps=2` 且模型总是调工具 → 第 2 步不给工具；写 `status(max_steps_reached)` |
| `AgentLoopTest#repeatCallStopped` | 同参数调用 3 次 → `status(repeat_call_stopped)` 后下一步无工具 |
| `AgentLoopTest#toolTimeoutBecomesResult` | 工具 sleep 超时 → `ok=false`，循环继续 |
| `AgentLoopTest#badArgumentsJson` | 不调用工具，结果 `ok=false` |
| `MultiStepToolIT#threeToolsEndToEnd` | 经 REST 发起，SSE 收到完整事件序列，`listItems` 与 SSE 一致 |

---

## RT-04 事件流与 SSE 续传（P0，5 人天）

### 事件 ID

| 事件 | SSE `id` | 说明 |
| --- | --- | --- |
| `item.created` | `{seq}.0` | 流式 item 开始（只有 assistant message/reasoning 有此事件） |
| `item.delta` | `{seq}.{i}`，i 从 1 起 | — |
| `item.completed` | `{seq}` | 完整内容；对客户端是「按 itemId 覆盖」 |
| `item.discarded` | `{seq}` | M1 故障接力用 |
| `turn.*` | 无 `id` | 不改变 `Last-Event-ID`；重连时服务端先补发当前 Turn 状态 |

比较规则（`EventId.compareTo`）：先比 seq；同 seq 时有小数的小于无小数的，小数之间按数值比。

### 写入路径

```text
ItemSink.completeMessage / toolCall / toolResult / status:
  1. ItemRepository.append（事务提交）
  2. EventStream.publish(threadId, item.completed{…}, id="{seq}")
ItemSink.delta:
  首个 delta：seq = reserveSeq(...)；publish(item.created, id="{seq}.0")；记录 Redis rt:cur:{threadId} = 本条事件的 stream entry id
  之后：publish(item.delta, id="{seq}.{i}")
  completeMessage 时用预占 seq 插入并 publish(item.completed)，删除 rt:cur:{threadId}
```

`EventStream.publish` = `XADD rt:events:{threadId} MAXLEN ~ 10000 * eid {id} type {type} data {json}` 后 `EXPIRE rt:events:{threadId} 3600`；Turn 进入终态时改为 `EXPIRE … 600`。delta 只进 Redis，不入库。流已过期时第 3 步跳过，全部从库补（ADR-029 第 3 条）。

### SSE 端点

`GET /api/runtime/threads/{threadId}/events`，`produces=text/event-stream`，请求头 `Last-Event-ID` 可选，查询参数 `after`（与头二选一，头优先，供 `fetch` 首连指定起点）。

```text
open(threadId, lastEventId):
  校验 thread 可见（owner 或 SYSTEM）
  L = XREVRANGE rt:events:{threadId} + - COUNT 1 的 entry id（没有则 "0-0"）      // 先记下流的当前位置，避免漏事件
  emitter = new SseEmitter(sse-timeout)
  sent = EventId.parse(lastEventId)  （空则 0）
  1. 补发当前活动 Turn 的 turn.* 状态
  2. DB：listItems(threadId, afterSeq = sent.seq（有小数则 sent.seq - 1）, …) → 逐条发 item.completed，更新 sent
  3. 若 rt:cur:{threadId} 存在：XRANGE 从该 entry 到 L，只发 eid > sent 的事件
  4. 注册到 StreamPump：从 L 之后继续，只发 eid > sent 的事件（turn.* 事件总是发）
```

### StreamPump（每实例一个）

不为每个 SSE 连接占一个阻塞线程：

```text
线程 "rt-stream-pump"（1 个）：
  loop:
    keys = 本实例有订阅者的 thread 流及各自的最后读取位置
    if keys 为空: 等待新订阅（Condition，最多 1s）; continue
    res = XREAD COUNT 200 BLOCK 1000 STREAMS k1 … kn id1 … idn     // 专用 Lettuce 连接
    for 每条 entry: 更新位置；交给该 thread 的所有 emitter（emitter.send 在 sse-send 线程池中执行，防止慢客户端拖住泵）
  订阅变化时写入 ConcurrentHashMap；下一轮 XREAD 生效（最多延迟 1s）
心跳：每 15s 对每个 emitter 发注释行 ": ping"
```

单实例连接数上限 `tioka.runtime.events.max-connections`（默认 2000），超出返回 503。

### 前端（FE，2 人天）

`src/api/stream.ts`：

```ts
export function streamThread(threadId: string, handlers: StreamHandlers, signal: AbortSignal) {
  let lastId = handlers.lastEventId ?? ''
  return fetchEventSource(`/api/runtime/threads/${threadId}/events`, {
    headers: authHeaders(),                       // Authorization + X-Workspace-Id；令牌过期先刷新
    signal,
    openWhenHidden: true,
    onmessage(ev) {
      if (ev.id) { if (compareEventId(ev.id, lastId) <= 0) return; lastId = ev.id }
      handlers.onEvent(JSON.parse(ev.data) as RuntimeEvent)
    },
    onerror(err) { /* 返回 undefined 让库按退避自动重连；重连时库会带 Last-Event-ID */ },
  })
}
```

`stores/thread.ts`（每个 thread 一个 store 实例，`defineStore('thread:' + id, …)`）：`items: Map<itemId, Item>` 按 seq 排序；`item.created` 新建空 item；`item.delta` 追加文本；`item.completed` 覆盖；`item.discarded` 删除；`turn.*` 更新 `activeTurn`。`PartRenderer.vue` 改为按 `ItemType` 渲染（M1 HCI-04 补快照测试）。

### 测试

| 用例 | 断言 |
| --- | --- |
| `EventIdTest` | 比较规则：`41.0 < 41.3 < 41 < 42.0` |
| `ThreadEventsIT#resumeFromMiddleOfDelta` | 收到 `5.3` 后断开，带 `Last-Event-ID: 5.3` 重连：不重复 1..3，收到 4.. 和 `5` |
| `ThreadEventsIT#resumeAfterCompletion` | 断开期间 Turn 完成，重连后从库补齐所有 item |
| `DualInstanceStreamIT`（闸门 G0） | 实例 A 执行 Turn，客户端连实例 B 订阅，收到完整序列；中途改连 A 续传无重复无缺失 |
| `StreamPumpIT#slowClientDoesNotBlockOthers` | 一个客户端不读，其它客户端延迟 < 200ms |

---

## RT-05 跨实例取消与插话（P0，3 人天）

### 信号

| 操作 | Redis | 说明 |
| --- | --- | --- |
| 取消 | `SET rt:signal:{turnId}:cancel 1 EX 3600` + `PUBLISH rt:signal {"turnId":…,"kind":"cancel"}` | 键用于轮询兜底；PUBLISH 用于立即中断 |
| 插话 | `RPUSH rt:signal:{turnId}:steer {text}`、`EXPIRE … 3600` + `PUBLISH rt:signal {"turnId":…,"kind":"steer"}` | — |

`SignalBus`：每实例订阅 `rt:signal` 频道；收到消息时若 `turnId` 在本实例运行（`TurnScheduler.running` 表），设置该 Turn 的 `AtomicBoolean cancelRequested` 或 `steerPending`。`LoopContext.checkpoint()` 先看本地标志，再每 2 秒兜底读一次 `cancel` 键。

### `interrupt(turnId)` 语义

| 当前状态 | 行为 |
| --- | --- |
| `QUEUED` | 直接 `cancelQueued` → `CANCELLED`，发 `turn.cancelled` |
| `RUNNING` | 写信号；执行实例在检查点抛 `CancelledSignal` → 若有流式中的 item，以已收到的文本 `completeMessage` 并在 content 加 `"interrupted": true`；写 `status(cancelled)`；`finish(CANCELLED)` |
| 终态 | 幂等返回，不报错 |

模型流中断：`LegacyModelPort` 在 `onDelta` 回调里检查标志并抛出，`LlmClientApi` 的 HTTP 流随之关闭；工具调用中被取消时，已在执行的工具不强杀，等其返回后不再写结果，直接结束。

### `steer(turnId, text)`

- `RUNNING`：入队信号；`applySteers` 在下一次模型调用前取出所有插话（`LPOP` 直到空），每条写一个 `message/user` item（`content.steer=true`）和 `status(steer_applied)`。
- `QUEUED`：直接追加到该 Turn 的输入（写 user item），不走信号。
- 其他状态：`CONFLICT`。
- 长度上限 4000 字符。

### 测试

| 用例 | 断言 |
| --- | --- |
| `CancelIT#cancelQueued` | 排队中的 Turn 取消后不执行 |
| `CancelAcrossInstancesGateIT`（G0） | Turn 在 A 上流式输出，向 B 发 interrupt；1 秒内 A 停止输出，状态 CANCELLED，已流出文本保留 |
| `SteerIT#steerAppliedBeforeNextStep` | 工具执行期间插话，下一次模型请求的 messages 包含该插话 |
| `LeaseReaperIT#crashedTurnFails` | 杀掉执行实例（关闭其上下文），租约过期后另一实例把 Turn 标为 FAILED(LEASE_LOST)，发 `turn.failed` |

### LeaseReaper（M0 版本）

每 10 秒，任一实例执行：

```sql
UPDATE runtime_turn SET status='INTERRUPTED', update_at=now()
 WHERE id IN (SELECT id FROM runtime_turn WHERE status='RUNNING' AND lease_until < now()
              ORDER BY lease_until LIMIT 50 FOR UPDATE SKIP LOCKED)
RETURNING id, session_id;
```

M0 随后对每条 `INTERRUPTED` 执行 `→ FAILED(LEASE_LOST)` 并发事件，再调度该 thread 的下一个 QUEUED Turn。M1 RT-06 把第二步改为 `→ QUEUED` 续跑。

---

## 旧接口适配与下线（P0，2 人天）

| 旧 | 新 | 处理 |
| --- | --- | --- |
| `RuntimeSessionApi.create` | `RuntimeApi.createThread` | `LegacySessionAdapter` 转调；`origin=chat` |
| `RuntimeSessionApi.append`（同步，返回 parts） | `startTurn` + 等待终态 | 适配器订阅本 thread 事件，等到 `turn.completed/failed/cancelled`（最长 `tioka.runtime.legacy-append-timeout`，默认 120s），再把本 Turn 的 item 转成 `PartView` 返回。仅供 M0 的 Chat、Flow Agent 节点、编排使用；M1 CHT-02、M2 ORC-03 后删除 |
| `cancel(sessionId)` | `interrupt(当前活动 Turn)` | — |
| `steer(sessionId, text)` | `steer(当前活动 Turn, text)` | — |
| `GET /api/runtime/sessions/{id}/stream` | `GET /api/runtime/threads/{id}/events` | 旧端点删除，前端同步切换 |
| `PartAppendedEvent`、`UsageReportedEvent`（Spring 事件） | `UsageReportedEvent` 保留，由 `TurnRunner` 在 Turn 结束时发布 | `CostTimelineService` 继续监听；`PartAppendedEvent` 删除 |
| `RuntimeSessionService`、`runtime_idem` 表 | — | 删除类；`runtime_idem` 在 V105 删除（幂等改用 `runtime_turn.client_msg_id` 唯一键） |

---

## REST 一览（M0 实现部分）

所有端点要求登录；thread 必须属于当前用户（`owner_id`）或调用方为 SYSTEM，否则 404。

| 方法与路径 | 请求 | 响应 `data` | 错误 |
| --- | --- | --- | --- |
| `POST /api/runtime/threads` | `{agentVersionId, title?, origin?, originRef?, metadata?}` | `ThreadView` | agentVersion 不存在 404 |
| `POST /api/runtime/threads/{id}/turns` | `{clientMsgId, input:[{type:"message", text}], options?:{maxSteps}}` | `TurnHandle`，HTTP 202 | 排队超过 5 条 429 `RATE_LIMITED`；本实例线程池与队列都满 503 |
| `GET /api/runtime/threads/{id}/items?afterSeq=0&limit=100` | — | `ItemView[]`（limit ≤ 500） | — |
| `GET /api/runtime/threads/{id}/events` | 头 `Last-Event-ID` | SSE | — |
| `GET /api/runtime/turns/{id}` | — | `TurnView` | — |
| `POST /api/runtime/turns/{id}/interrupt` | — | `null` | — |
| `POST /api/runtime/turns/{id}/steer` | `{text}` | `null` | 状态不允许 409 |

`options.maxSteps` 上限 50，超出按 50。

---

## 闸门 G0 用例（`tioka-server/src/test/java/com/tioka/server/gate/`）

| 类 | 对应验收 |
| --- | --- |
| `G0AuthGateIT` | 未登录全部 401；两个用户互不可见 |
| `G0MultiStepGateIT` | 3 次工具调用任务自动完成（单实例） |
| `G0DualInstanceStreamGateIT` | A 执行、B 订阅；断线续传无重复无缺失 |
| `G0CancelAcrossInstancesGateIT` | B 取消 A 上的 Turn |
| `G0ConcurrencyGateIT` | 100 个 thread 并发互不阻塞 |

骨架已随本 PR 提交（`@Disabled("M0 实现后启用")`），实现者删除 `@Disabled` 并补全断言。

## 完成标准

- 34 号计划 RT-01〜05 的验收标准全部由上面的 IT/闸门用例覆盖并通过。
- 代码中无 `RuntimeSessionService`、`parts_json`、`synchronized`（runtime 模块）。
- 前端对话页走新事件端点，刷新页面后从 `listItems` 恢复，断网重连无重复文本。

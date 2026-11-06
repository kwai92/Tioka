# ADR-016 — Runtime 内核（thread / turn / item）

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：RT-01〜08、HCI-02
- 对应设计：设计 B
- 阅读版：[Tioka 推进规划与详细设计](https://claude.ai/artifact/V3wtabWCVSwAC3ewNerFNi)
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

覆盖 RT-01〜08、HCI-02。核心决定是改成 **thread / turn / item 三级模型（对齐 Codex app-server），Turn 异步执行、按会话串行、先入库再推送**。任何实例都能接收请求、执行 Turn、提供流式订阅，实例之间只通过 PostgreSQL 和 Redis 协作。

> 架构图见设计文档「Runtime 内核架构」图示。

PostgreSQL 是事实来源，Redis 只负责分发、推送和互斥；Redis 数据丢失时可以从数据库重建队列和流。

**状态机**

Turn 状态：`QUEUED → RUNNING → COMPLETED | FAILED | CANCELLED`；`RUNNING ⇄ WAITING_APPROVAL`；租约过期进入 `INTERRUPTED`，由回收器决定重试或失败。同一会话同时最多一个 RUNNING 的 Turn，后续消息按序排队。

**执行循环**（RT-03）

```text
for step in 1..maxSteps (默认 20):
    检查 cancel / steer 信号
    selection = modelGateway.select(sessionId, turnId)      // 同一 Turn 内冻结
    result    = modelGateway.chat(ctx, messages, tools, onDelta)
                // delta 只发 Redis Stream；完整消息作为 item 入库
    若无 toolCalls: Turn → COMPLETED，结束
    对每个 toolCall（最多 4 个并行）:
        decision = approvalPolicy.evaluate(principal, tool, args)
        若需审批: 写 runtime_approval，Turn → WAITING_APPROVAL，释放锁，返回
        out = toolGateway.call(ctx, tool, args, timeout)        // 工具网关写审计
        写 tool_result item
达到 maxSteps: 写 status item，Turn → COMPLETED(reason=max_steps)
```

**数据模型**（RT-02，替代 `runtime_session.parts_json`）

```sql
ALTER TABLE runtime_session DROP COLUMN parts_json;
ALTER TABLE runtime_session ADD COLUMN last_item_seq BIGINT NOT NULL DEFAULT 0;

CREATE TABLE runtime_turn (
  id VARCHAR(32) PRIMARY KEY, session_id VARCHAR(64) NOT NULL, seq INT NOT NULL,
  status VARCHAR(24) NOT NULL, origin VARCHAR(16) NOT NULL,      -- chat / flow / orch / im
  origin_ref VARCHAR(64), client_msg_id VARCHAR(128),
  run_as_json TEXT NOT NULL,                                       -- Principal
  options_json TEXT,                                               -- maxSteps, approvalPolicy
  step_count INT NOT NULL DEFAULT 0,
  worker_id VARCHAR(64), lease_version BIGINT NOT NULL DEFAULT 0, lease_until TIMESTAMP,
  error_code VARCHAR(64), error_message TEXT,
  prompt_tokens INT, completion_tokens INT,
  created_at TIMESTAMP NOT NULL, started_at TIMESTAMP, ended_at TIMESTAMP,
  UNIQUE (session_id, seq), UNIQUE (session_id, client_msg_id));

CREATE TABLE runtime_item (
  session_id VARCHAR(64) NOT NULL, seq BIGINT NOT NULL,          -- 会话内单调递增，即 SSE 事件 ID
  id VARCHAR(32) NOT NULL UNIQUE, turn_id VARCHAR(32) NOT NULL, step INT NOT NULL,
  type VARCHAR(32) NOT NULL,                                     -- PartType
  role VARCHAR(16), content_json TEXT NOT NULL, created_at TIMESTAMP NOT NULL,
  PRIMARY KEY (session_id, seq));

CREATE TABLE runtime_approval (
  id VARCHAR(32) PRIMARY KEY, subject_type VARCHAR(16) NOT NULL, subject_id VARCHAR(32) NOT NULL, item_id VARCHAR(32),  -- subject: turn / flow_node_run / orch_run（见定案 1）
  kind VARCHAR(16) NOT NULL, tool_name VARCHAR(255), args_json TEXT, card_json TEXT, policy VARCHAR(64),  -- kind: tool / card
  status VARCHAR(16) NOT NULL,                                  -- PENDING / APPROVED / REJECTED / EXPIRED
  decided_by VARCHAR(32), decided_at TIMESTAMP, answer_json TEXT, expires_at TIMESTAMP);
```

**并发与多实例**（RT-01、RT-04、RT-05）

| 问题 | 现状 | 设计 |
| --- | --- | --- |
| 会话互斥 | `append` 上全局 `synchronized` | Redis 锁 `rt:lock:{sessionId}`，TTL 30 秒、每 10 秒续期；写库带 `lease_version` 条件，旧 worker 的写入会失败 |
| Turn 分发 | HTTP 线程内同步执行 | Redis Stream `rt:turns` + 消费组，各实例的有界线程池消费 |
| 流式推送 | SSE emitter 存在 JVM Map | item 先入库再写 `rt:events:{sessionId}`；任意实例的 SSE 用 `XREAD` 订阅，`Last-Event-ID` 之前的部分从数据库补 |
| 取消与插话 | 只改本机内存 | 写 `rt:signal:{turnId}`，执行循环在每步之间和每个 delta 之间检查 |
| 崩溃恢复 | 内存态丢失 | 回收器扫 `lease_until < now()` 的 Turn：最后一个 item 是用户输入或工具结果则重新入队；有未返回的工具调用则补一条「结果丢失」的 tool\_result 后再入队，不重放工具 |

Turn 分发用 Redis Stream 而不是 RocketMQ，是因为它延迟低且 M0 就要用；RocketMQ 在 M2 引入后只承担跨域业务事件。

**对外协议**（RT-08，替代现有 `RuntimeSessionApi`）

```java
public interface RuntimeApi {
    ThreadView createThread(CreateThreadCmd cmd);
    TurnHandle startTurn(String threadId, StartTurnCmd cmd);   // 立即返回；按 clientMsgId 幂等
    void interrupt(String turnId);
    void steer(String turnId, String text);
    void decideApproval(String approvalId, ApprovalDecision decision);
    TurnView getTurn(String turnId);
    List<ItemView> listItems(String threadId, long afterSeq, int limit);
    ThreadView fork(String threadId, long atSeq);               // M3，供 Chat 真分叉
}

record StartTurnCmd(String clientMsgId, List<InputItem> input, TurnOptions options,
                    String origin, String originRef) {}
record TurnOptions(Integer maxSteps, String approvalPolicy, List<String> toolAllowList) {}
record TurnHandle(String turnId, String status, String eventsUrl) {}
```

流式接口 `GET /api/runtime/threads/{id}/events`（SSE），事件类型：`turn.started`、`item.created`、`item.delta`、`item.completed`、`approval.requested`、`turn.completed`、`turn.failed`。事件 ID 为 `{itemSeq}` 或 `{itemSeq}.{deltaIndex}`，客户端断线后带 `Last-Event-ID` 重连。M2 的 HCI-06 再把这套事件映射到 AG-UI。

Chat、Flow、Orchestration、IM 四个入口只使用 `RuntimeApi`，不再各自调模型。Orchestration 的人审（Attention）与 Runtime 的工具审批共用 `runtime_approval`，卡片提交最终都调用 `decideApproval`。

## 修订：Agent 架构调研落地（2026-11-06）

来由：[调研 01](../research/01-Agent与多Agent架构调研.md)、[调研 02](../research/02-Agent执行策略与执行器插件调研.md)；负责人同意按调研建议设计，见 ADR-029 第 67 条。

- **执行策略**：执行循环中「组装本步提示 → 调模型 → 解释结果」一段改为可插拔的执行策略，默认 `REACT` 即本 ADR 的循环，行为不变；其他策略见 [ADR-046](ADR-046-execution-strategy.md)。所有策略共用本 ADR 的状态机、租约、审批挂起、取消与插话、item 落库与推送。`runtime_item.type` 增加 `plan`、`reflection`。
- **工具检索（RT-13，M4 P2）**：本 Turn 授予内的工具超过 15 个或工具定义合计超过 10K token 时，`listTools` 只给模型「常用工具」（Agent 配置的固定集合，缺省为最近 30 天调用最多的 8 个）与内置工具 `tool.search`；其余工具标记为延迟加载，模型经 `tool.search`（按名称、描述做关键词与向量检索，范围限于授予内）取得定义后才可调用。检索结果作为普通工具结果入库。延迟加载不影响提示缓存：常用工具集合在一个 Turn 内不变。
- **护栏挂点（RT-14，M4 P2）**：Turn 管线固定三个挂点，均为 SPI，可由平台或领域包注册检查器：① **输入**（本轮用户输入进入上下文前：相关性、越狱、PII）；② **工具前**（即 ADR-033 管线，不变）；③ **最终输出**（交付前：输出契约 Schema、PII、敏感词、引用存在性）。检查结果为放行、改写（如掩码）、拒绝、转人审四种；拒绝与转人审写 `status` item 并审计。模型型检查经 ModelGateway（purpose=guardrail），计入本 Turn 预算。挂点顺序与上下文组装顺序一样由平台固定，模块不得自行插入。

## 修订：记忆与上下文工程调研落地（2026-11-06）

来由：[调研 03](../research/03-Agent记忆与上下文工程调研.md)；按负责人「有选项时按推荐方案直接写设计」的要求落地，见 ADR-029 第 68 条。

- **工具检索（RT-13）**：常用工具集合由「Turn 内不变」改为「thread 内不变」；工具列表按名称排序、定义规范化序列化；不可用工具在调用时拒绝而不从列表删除，授予收窄除外（[ADR-048](ADR-048-context-engineering.md) 第 1.2 节、[ADR-049](ADR-049-prompt-caching.md) 规则 C2、C4）。
- **Turn 循环**：每次调模型前由组装器按 ADR-048 生成上下文并写上下文清单；本轮尾部在 Turn 开始时生成，本 Turn 内各步复用。新增 Turn 结局 `CONTEXT_OVERFLOW`。

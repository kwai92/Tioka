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

# ADR-020 — Orchestration 编排

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：ORC-01〜07
- 对应设计：设计 F
- 阅读版：[Tioka 推进规划与详细设计](https://claude.ai/artifact/V3wtabWCVSwAC3ewNerFNi)
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

覆盖 ORC-01〜07 与 S2 的全部能力簇。核心决定是：**编排只做「事件 → 规则 → 工单 → Run」的调度和记账，执行一律交给 Runtime，人审一律用通用审批表**。S2 中的 Mandate、Eval 准入、交付流水线不另造引擎，而是用服务账号权限、前置决策 Turn、Flow 发布来组合实现。

## 主链路

1. **事件接入**：外部经 Connector 验签后调 `OrchApi.ingest`，内部模块通过 outbox 发事件。幂等键必填（来源 + 外部 ID），重复事件直接返回已有结果。
2. **规则匹配**：取该事件类型的已发布规则版本，用 CEL 求值条件。按 S0 硬边界「事件正文不决定路由和权限」，**条件只能引用信封字段和 Connector 映射出的可信属性 `attrs`**，不能读原始正文。
3. **归单**：按 `subject_key` 找到或创建工单（WorkItem）。规则声明并发模式：`serial`（同一工单串行排队）、`merge`（运行中来的新事件合并为下一次 Run 的输入）、`parallel`。
4. **派工**：写 Run 与 outbox 同事务；派工器消费 `tioka.orch.run.created.v1`，调 `RuntimeApi.createThread` + `startTurn`（origin=orch，run\_as=规则的服务账号）。
5. **回执**：编排发起的 Turn 自动挂上内置工具 `orch.receipt`，Agent 用它交回结构化结果：`resolve`（完成）、`ask`（发起人审）、`notice`（通知）、`later`（延时再跑）、`emit`（派生新事件，hops+1）。Turn 结束却没有回执，按 `resolve` 处理并记一条告警事实。
6. **人审**：`ask` 写通用审批（subject\_type=orch\_run，kind=card），经审批台和 IM 同时投递；答复后用答案作为新输入在同一 thread 上开下一个 Turn。超时按规则配置升级给指定角色，或采用默认答案。

## Run 状态机

`PENDING → DISPATCHED → RUNNING ⇄ WAITING_ATTENTION → SUCCEEDED | FAILED | CANCELLED`。Turn 被中断且已有工具对外产生副作用时进入 `UNCERTAIN`，只能由人在审批台判定重跑或关闭，系统不自动重试。迟到的 Turn 结果（Run 已取消）只记事实，不改状态。

## 规则版本与灰度

规则版本不可变。灰度按 `hash(subject_key) % 100 < canary_percent` 分流到候选版本，同一工单始终命中同一版本。候选版本求值报错时回落到稳定版本并告警（即 S2 的「坏规则保旧版」）。

## 定时与延时（ORC-06）

统一一张 `orch_timer` 表，取代现有 `LaterTriggerService`。各实例每 5 秒用 `FOR UPDATE SKIP LOCKED` 取到期定时器，触发即发一条事件。cron 按规则声明的时区计算；错过的触发默认只补最近一次（`LATEST_ONLY`），可配 `ALL` 或 `SKIP`。

## 熔断（ORC-07）

- hops 超过 8 的事件丢弃并告警，防止事件环。
- 同一规则连续 5 次 Run 失败则自动暂停规则。
- 预算熔断复用配额：规则是配额的一种作用对象（`gov_quota.scope_type = rule`）。
- 消费失败 3 次的消息进死信表，管理端可重放。

## Mandate、Eval 与交付流水线的落法

| S2 能力 | 实现方式 |
| --- | --- |
| Mandate 授权 | 每条规则绑定一个服务账号（FND-06），其角色、可用工具、审批策略、配额就是 Mandate。撤销或收窄即时生效（定案 6） |
| Eval 准入 | 规则可配一个前置「研判 Agent」，先跑一个只读 Turn，其回执为 `resolve(skip)` 时不创建正式 Run |
| 交付流水线 | 审查→CI→合并→发布类长任务建成 Flow，规则动作直接调用 Flow 发布（origin=orch） |
| 订阅扇出 | 一个事件可命中多条规则，各自生成 Run；不另设订阅实体 |
| SLA 与值班 | M4 之后：审批超时升级链 + 值班日历表，接口留在审批超时处理器上 |

## 接口

```java
public interface OrchApi {                        // 替代现有 claim / heartbeat / openAttention / answer
    EventAck ingest(EventEnvelope event);
    RunView getRun(String runId);
    void cancelRun(String runId, String reason);
    List<FactView> facts(String runId, long afterSeq);   // why 时间线，另有 SSE
}
record EventEnvelope(String source, String type, String idempotencyKey, String subjectKey,
                     Map<String, String> attrs,      // 可信属性，规则只看这里
                     JsonNode body,                  // 只传给 Agent 作输入
                     int hops) {}
record Receipt(ReceiptKind kind, String summary, Map<String, Object> data,
               Instant laterAt, List<EventEnvelope> emits, AttentionSpec ask) {}
```

Run 的领取和心跳不再对外暴露，由派工器内部完成；人审答复统一走 `decideApproval`。

## Outbox 与 MQ（ORC-02，全平台共用）

业务写入与消息登记同事务进 `sys_outbox`；一个中继线程（抢占式单例，用 Redis 锁）按主键顺序投递到 RocketMQ；消费端用 `sys_consumed` 去重，实现至少一次投递、恰好一次处理。所有模块的跨域事件都用这一套。

## 数据模型

```sql
CREATE TABLE orch_event (id VARCHAR(32) PRIMARY KEY, tenant_id VARCHAR(32) NOT NULL, source VARCHAR(64) NOT NULL,
  type VARCHAR(128) NOT NULL, idempotency_key VARCHAR(255) NOT NULL, subject_key VARCHAR(255),
  attrs_json TEXT, body_json TEXT, hops INT NOT NULL DEFAULT 0, received_at TIMESTAMPTZ NOT NULL,
  UNIQUE (tenant_id, idempotency_key));
CREATE TABLE orch_rule_version (id VARCHAR(32) PRIMARY KEY, rule_id VARCHAR(64) NOT NULL, version INT NOT NULL,
  event_type VARCHAR(128) NOT NULL, condition_cel TEXT, actions_json TEXT NOT NULL,
  concurrency VARCHAR(16) NOT NULL, service_account_id VARCHAR(32) NOT NULL,
  stage VARCHAR(16) NOT NULL,                    -- DRAFT / CANARY / STABLE / RETIRED
  canary_percent INT, created_by VARCHAR(32), created_at TIMESTAMPTZ NOT NULL, UNIQUE (rule_id, version));
CREATE TABLE orch_work_item (id VARCHAR(32) PRIMARY KEY, tenant_id VARCHAR(32) NOT NULL, rule_id VARCHAR(64),
  subject_key VARCHAR(255) NOT NULL, status VARCHAR(16) NOT NULL, pending_events_json TEXT,
  UNIQUE (tenant_id, rule_id, subject_key));
-- orch_run 重建
CREATE TABLE orch_run (id VARCHAR(32) PRIMARY KEY, work_item_id VARCHAR(32) NOT NULL, event_id VARCHAR(32) NOT NULL,
  rule_version_id VARCHAR(32) NOT NULL, status VARCHAR(24) NOT NULL, thread_id VARCHAR(64), current_turn_id VARCHAR(32),
  receipt_json TEXT, run_as_json TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL, ended_at TIMESTAMPTZ);
CREATE TABLE orch_timer (id VARCHAR(32) PRIMARY KEY, kind VARCHAR(8) NOT NULL,      -- CRON / LATER
  cron VARCHAR(64), tz VARCHAR(64), next_fire_at TIMESTAMPTZ NOT NULL, misfire VARCHAR(16) NOT NULL,
  target_json TEXT NOT NULL, status VARCHAR(16) NOT NULL);
CREATE INDEX idx_timer_due ON orch_timer (status, next_fire_at);
CREATE TABLE orch_fact (run_id VARCHAR(32), seq INT, kind VARCHAR(32) NOT NULL, detail_json TEXT,
  at TIMESTAMPTZ NOT NULL, PRIMARY KEY (run_id, seq));           -- 取代 orch_why，只增
CREATE TABLE sys_outbox (id BIGSERIAL PRIMARY KEY, topic VARCHAR(128) NOT NULL, msg_key VARCHAR(128),
  payload_json TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL, sent_at TIMESTAMPTZ);
CREATE TABLE sys_consumed (consumer VARCHAR(64), msg_id VARCHAR(64), consumed_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (consumer, msg_id));
CREATE TABLE sys_dead_letter (id VARCHAR(32) PRIMARY KEY, consumer VARCHAR(64), topic VARCHAR(128),
  payload_json TEXT, error TEXT, attempts INT, created_at TIMESTAMPTZ NOT NULL);
```

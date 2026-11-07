# M2-01 — 消息、Outbox 与可观测起步

- 负责人：BE-C（ORC-02、ORC-07）；BE-A（GOV-08）
- 工作项：ORC-02、ORC-07、GOV-08
- 约束：ADR-020（Outbox 与 MQ、熔断）、ADR-025、ADR-029 第 15 条；契约 `docs/contracts/events/mq-envelope-v1.json`
- 前置：M1 完成
- 迁移编号段：M2 使用 V130〜V149，本文件用 V130〜V131

> 2026-11-06 补充（[M2-00](M2-00-M2实施计划.md)、ADR-029 第 65 条）：排期、人天与负责人以 M2-00 为准。ORC-02 第一周先合事件契约 PR：补齐全部事件 payload schema，信封 `mq-envelope-v1.json` 去掉 `tenantId`，加可选字段 `replayOf`、`traceparent`；身份生命周期事件桥接（ID-BR）由 BE-A 做。本文建表片段中的 `tenant_id` 与伪代码中的租户维度已按第 60 条废止，以 `docs/contracts/db` 中的契约为准。

---

## ORC-02 RocketMQ、事务 Outbox、消费幂等（P0，8 人天）

### 主题与命名

| 项 | 规则 |
| --- | --- |
| 事件名 | `tioka.{domain}.{event}.v{n}`（ADR-029 第 15 条），如 `tioka.orch.run_created.v1` |
| RocketMQ Topic | 每个领域一个：沿用现有 `*MqTopics.TOPIC`（`tioka-runtime`、`tioka-orch`…）；M2 新增 `tioka-orch`、`tioka-connector` |
| Tag | 事件名全文；消费方用 Tag 表达式订阅 |
| 消息体 | `mq-envelope-v1.json` 信封，`payload` 为事件专属结构，每个事件的 payload schema 放在 `docs/contracts/events/{event}.json` |
| 顺序 | 需要顺序的事件把 `subjectKey` 作为 RocketMQ 消息组（FIFO 主题）或 sharding key；M2 只有编排工单相关事件需要 |

现有 `*MqTopics` 中的 `TAG_*` 常量改为事件名常量（`public static final String RUN_CREATED = "tioka.orch.run_created.v1"`），未被任何生产者使用的常量删除。

### 迁移 V130

```sql
CREATE TABLE sys_outbox (
    id           BIGSERIAL    PRIMARY KEY,
    event_id     VARCHAR(32)  NOT NULL UNIQUE,
    topic        VARCHAR(128) NOT NULL,
    tag          VARCHAR(128) NOT NULL,
    msg_key      VARCHAR(255),                       -- subjectKey
    payload_json TEXT         NOT NULL,              -- 完整信封
    attempts     INT          NOT NULL DEFAULT 0,
    last_error   TEXT,
    create_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at      TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unsent ON sys_outbox (id) WHERE sent_at IS NULL;

CREATE TABLE sys_consumed (
    consumer    VARCHAR(64) NOT NULL,
    event_id    VARCHAR(32) NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

CREATE TABLE sys_dead_letter (
    id           VARCHAR(32)  PRIMARY KEY,
    consumer     VARCHAR(64)  NOT NULL,
    topic        VARCHAR(128) NOT NULL,
    tag          VARCHAR(128) NOT NULL,
    event_id     VARCHAR(32)  NOT NULL,
    payload_json TEXT         NOT NULL,
    error        TEXT,
    attempts     INT          NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'OPEN',    -- OPEN / REPLAYED / DISCARDED
    create_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    handled_by   VARCHAR(64),
    handled_at   TIMESTAMPTZ
);
CREATE INDEX idx_dead_letter_open ON sys_dead_letter (consumer, create_at) WHERE status = 'OPEN';
-- im_outbox 不在此删除：现有 IM 发送代码仍在用，由 V142 随 CON-02 删除（ADR-029 第 33 条修订）
```

### 生产端

```java
// tioka-framework/.../messaging/EventPublisher.java（新，framework 只放接口与信封）
public interface EventPublisher {
    /** 必须在事务内调用（Propagation.MANDATORY），与业务写入同事务登记。 */
    void publish(DomainEvent event);
}
public record DomainEvent(String event, String topic, String subjectKey, Map<String, Object> payload, Integer hops) {}
```

`OutboxEventPublisher`（`tioka-module-infra-biz`）：补齐信封字段（`eventId=Ids.newId("evt")` 去掉前缀后的 ULID、`tenantId`/`workspaceId`/`runAs` 取当前 Principal、`traceId` 取 OTel 当前 span、`occurredAt`、`producer` 取调用方模块名），JSON Schema 校验信封（开发与测试环境开启，生产关闭），`INSERT sys_outbox`。

### 中继 `OutboxRelay`（infra-biz）

```text
单例运行：Redis 锁 mq:relay:lock（SET NX PX 15000，每 5 秒续期；拿不到锁的实例每 5 秒重试）
loop（持有锁时）：
  rows = SELECT … FROM sys_outbox WHERE sent_at IS NULL ORDER BY id LIMIT 200
  if rows 为空：等待 200ms（或被 LISTEN/NOTIFY 唤醒：发布时 pg_notify('tioka_outbox','')）
  for r in rows（按 id 顺序）：
     producer.syncSend(topic:tag, message(keys=event_id, sharding=msg_key, body=payload))
     成功：UPDATE sys_outbox SET sent_at = now() WHERE id = r.id
     失败：attempts + 1、last_error；同一 msg_key 的后续行本轮跳过（保持顺序），其他继续
  清理：每小时删除 sent_at < now() - 7 天的行
```

PG `NOTIFY` 用专用 JDBC 连接 `LISTEN tioka_outbox`，不经连接池。

### 消费端

```java
// tioka-framework/.../messaging/IdempotentConsumer.java
public abstract class IdempotentConsumer<P> {
    protected abstract String consumerName();          // 唯一，例 "orch-dispatcher"
    protected abstract Class<P> payloadType();
    protected abstract void handle(EventEnvelope env, P payload);   // 在事务内执行

    // 框架实现（infra-biz 中的 RocketMQ 监听器适配器调用）：
    // @Transactional
    // void onMessage(envelope):
    //    INSERT INTO sys_consumed (consumer, event_id) … ON CONFLICT DO NOTHING
    //    影响 0 行 → 已处理，直接确认
    //    principalHolder.runAs(principalLoader.reload(env.runAs), () -> handle(env, payload))
    //    异常 → 事务回滚（sys_consumed 一起回滚）→ 抛出让 RocketMQ 重投
}
```

- RocketMQ 消费组 = `consumerName()`，重试 3 次（`maxReconsumeTimes=3`，退避由 Broker 决定）。
- 第 3 次仍失败：监听器适配器在最后一次失败时把消息写 `sys_dead_letter`（独立事务），然后确认消息（不进 RocketMQ 自带死信队列，便于管理端查询与重放）。
- 每个消费者的 `handle` 必须幂等（`sys_consumed` 只防重复投递，不防业务重入）。

### 本地开发与测试

`deploy/compose.yml` 已有 RocketMQ 5.3.1；Testcontainers 用 `apache/rocketmq:5.3.1`（NameServer + Broker 两个容器，`GenericContainer`，封装在 `TestContainers.ROCKETMQ`，只在 `@Tag("mq")` 的 IT 中启动）。不需要 MQ 的 IT 用 `tioka.messaging.mode=in-memory`：中继直接把消息交给本进程消费者（仍走 `sys_outbox` 与 `sys_consumed`），让 IT 不依赖 Broker。

```yaml
tioka:
  messaging:
    mode: rocketmq                 # rocketmq / in-memory（仅测试）
    relay: { batch: 200, idle-wait: 200ms, lock-ttl: 15s }
    consumer: { max-attempts: 3 }
rocketmq:
  name-server: ${TIOKA_MQ_NAMESRV:localhost:9876}
  producer: { group: tioka-producer, send-message-timeout: 3000 }
```

### 迁移现有 Spring 事件

| 现有 | 处理 |
| --- | --- |
| `UsageReportedEvent`（进程内） | 保留进程内（同模块内使用） |
| `approval.decided` 的 afterCommit 调用（M1） | 改为发布 `tioka.runtime.approval_decided.v1`，`ApprovalSubjectHandler` 由消费者 `runtime-approval-router` 调用 |
| `TurnCompletedEvent` 给 Chat 更新索引（M1） | 改为 `tioka.runtime.turn_finished.v1`，消费者 `chat-index`、`orch-receipt`（M2-02） |
| `im_outbox` | IM 发送改为消费 `tioka.connector.im_send_requested.v1` |

### 测试

| 用例 | 断言 |
| --- | --- |
| `OutboxIT#rollbackDropsEvent` | 业务事务回滚，`sys_outbox` 无行、消费者未收到 |
| `OutboxRelayIT#orderPerKey`（mq） | 同 key 100 条按序到达 |
| `OutboxRelayIT#singleRelayAcrossInstances` | 双实例只有一个中继在发，杀掉持有者 15 秒内另一个接管 |
| `IdempotentConsumerIT#duplicateDeliveryHandledOnce` | 同一 eventId 投递 3 次，业务只执行 1 次（34 号验收） |
| `IdempotentConsumerIT#failureGoesToDeadLetter` | 连续失败 3 次进 `sys_dead_letter` |
| `EnvelopeContractTest` | 所有 `docs/contracts/events/*.json` 是合法 schema；每个事件名常量都有对应 schema 文件 |

---

## ORC-07 跳数熔断、死信、重放（P1，5 人天）

### 跳数

- 信封 `hops` 由派生方 +1：编排回执 `emit` 产生的事件、Flow 发布被编排调用后再发出的事件。
- `OrchApi.ingest`：`hops > tioka.orch.max-hops`（默认 8）→ 不处理，`disposition=FILTERED`，写 `orch_decision(decision=STOP, reason="hops_exceeded")`，写审计 `orch.hops_exceeded`，并发一条通知给规则负责人（`tell` 不影响此告警）。

### 规则自动暂停

`orch_rule` 增加 `paused_at`、`paused_reason`、`consecutive_failures`（V131）。Run 以 `FAILED` 结束时 `consecutive_failures + 1`，`SUCCEEDED` 时清零；达到 5 → 自动暂停（新事件命中时 `decision=UNAVAILABLE, reason="rule_paused"`），通知规则负责人。连续失败第 3 次时按 `tell` 规则通知一次（ADR-020 修订第 7 条）。

### 管理端

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| `GET /api/ops/dead-letters?consumer&status&page&size` | `orch:operate` | 列表；`payload` 只返回前 2000 字 |
| `POST /api/ops/dead-letters/{id}/replay` | `orch:operate` | 重新写一条 `sys_outbox`（新 `eventId`，信封中 `replayOf` 记原 ID），状态置 `REPLAYED`，写审计 |
| `POST /api/ops/dead-letters/{id}/discard` | `orch:operate` | `{reason}`，状态置 `DISCARDED` |
| `POST /api/orch/rules/{id}/resume` | `orch:operate` | 解除暂停，清零失败计数 |

重放用新 eventId，是因为原 eventId 可能已在 `sys_consumed` 中被部分消费者记录；信封 `replayOf` 字段需加入 `mq-envelope-v1.json`（可选字段，兼容变更，不升版本）。

### 测试

`HopsIT#ninthHopDropped`（34 号验收：事件环被熔断并告警）、`RulePauseIT#fiveFailuresPause`、`DeadLetterReplayIT#replaySucceedsAfterFix`。

---

## GOV-08 OpenTelemetry 起步（P1，M2 部分 5 人天）

M2 目标：一次 Turn（含模型调用、工具调用、MQ 跳转）在 Jaeger 中是一条完整链路。完整覆盖在 M4。

### 方式

- 使用 OpenTelemetry Java Agent 1.33.x（`-javaagent`，Dockerfile 与 `deploy/compose.yml` 中加入），自动埋点 Spring MVC、JDBC、Lettuce、JDK HttpClient、RocketMQ 客户端。
- 手动埋点（`io.opentelemetry:opentelemetry-api`，`@WithSpan` 或 `Tracer`）：

| span 名 | 位置 | 属性 |
| --- | --- | --- |
| `turn.run` | `TurnRunner.run` | `tioka.thread_id`、`tioka.turn_id`、`tioka.origin`、`tioka.agent_version_id` |
| `turn.step` | `AgentLoop` 每步 | `tioka.step` |
| `model.chat` | `DefaultModelGateway.chat` | `gen_ai.system`、`gen_ai.request.model`、`gen_ai.usage.input_tokens`、`gen_ai.usage.output_tokens`、`tioka.failover_count`（遵循 OTel GenAI 语义约定） |
| `tool.invoke` | `DefaultToolGateway.call` | `tioka.tool`、`tioka.tool.ok` |
| `orch.ingest`、`orch.dispatch` | 编排 | `tioka.rule_id`、`tioka.run_id` |

- 跨异步边界传播：`sys_outbox` 信封 `traceId` 字段之外再加 `traceparent`（W3C），消费者以其为父 span；`runtime_turn` 增加 `traceparent VARCHAR(64)`（V131），派发到其他实例执行时恢复上下文。
- `trace_span` 表与 `TraceService`：停止写入，Observability 页改为链接 Jaeger（`tioka.observability.trace-url-template`），M4 删除表。
- 日志：Logback 模式加入 `%X{trace_id} %X{span_id}`（Agent 自动注入 MDC）。

```yaml
# deploy/compose.yml 新增
jaeger:
  image: jaegertracing/all-in-one:1.57
  ports: ["16686:16686", "4317:4317"]
# 应用环境变量
OTEL_SERVICE_NAME: tioka
OTEL_EXPORTER_OTLP_ENDPOINT: http://jaeger:4317
OTEL_TRACES_SAMPLER: parentbased_traceidratio
OTEL_TRACES_SAMPLER_ARG: "1.0"
```

### 测试

`TracingIT`（用 OTel SDK 的 `InMemorySpanExporter`，不启 Agent）：一次带工具调用的 Turn 产生 `turn.run` → `turn.step` → `model.chat`/`tool.invoke` 父子关系正确；经 outbox 的消费者 span 与生产者在同一 trace。

## 完成标准

- 至少一次投递、重复消息不重复执行；死信可查、可重放。
- 事件环被熔断并告警；连续失败的规则自动暂停。
- 一次 Turn 在 Jaeger 中完整可见。

## 修订：关联上下文与外部交互台账（2026-11-07，ADR-029 第 75 条）

- 信封除 `traceparent`、`replayOf` 外增加 `requestId`、`subject`；消费者恢复关联上下文；重放保留原 `requestId`（[ADR-073](../adr/ADR-073-correlation-trace-propagation.md)，OBS-03，3 人天，BE-A 随 GOV-08 在 W16）。
- GOV-08 的 span 属性增加 `tioka.request_id`、`tioka.subject`、`tioka.origin`；`TracingIT` 增加用例：审批挂起后续跑，新 trace 有 span link 指回原 trace，且两段记录 `request_id` 相同。
- 外部交互台账 OBS-04a（4 人天，BE-B，W19–W20，紧接 BE-A 的 CON-02 飞书入站之后接入；BE-B 负载 40 → 44）：飞书回调与发送、Webhook、`EgressClient` 写 `ext_interaction`（[ADR-074](../adr/ADR-074-external-interaction-ledger.md)）。
- OBS-05（1.5 人天，W22）：动作目录契约、审计转发进度表、定时任务数据库锁。**2026-11-07 改由 BE-C 负责**（BE-A 的 W17〜W21 是飞书主链，放不下；BE-C 熟悉 outbox 与调度），见 M2-00 第 4.1 节。
- 以上 8.5 人天进入 M2 余量（2026-11-07 重排后 BE-A 41 → 44，BE-B 40 → 46（含 M0 遗留 2 人天），BE-C 42 → 43.5，每人容量 50），不改 M2 周数。

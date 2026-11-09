# M2 RocketMQ 部署口径

> 状态：2026-11-10（M2 残余收口批次 B-3）。本文登记仓库当前真实口径，未落地项显式标注。
> 事实来源：`deploy/compose.yml`、`tioka-server/src/main/resources/application.yml`、`application-prod.yml`、
> `tioka-module-infra-biz` 的 `messaging` 包（`RocketMqConsumerBootstrap` / `IdempotentDeliveryService` / `OutboxEventPublisher`）。

## 1. compose 内的 RocketMQ 基础设施

`deploy/compose.yml` 提供本地 NameServer + Broker（无认证、无 Dashboard）：

| 服务 | 镜像 | 端口 | 说明 |
| --- | --- | --- | --- |
| `rocketmq-namesrv` | apache/rocketmq:5.3.1 | 9876 | `sh mqnamesrv` |
| `rocketmq-broker` | apache/rocketmq:5.3.1 | 10911 | `NAMESRV_ADDR=rocketmq-namesrv:9876`，`depends_on: rocketmq-namesrv` |

注意：compose 只提供基础设施，**不含 tioka-server 服务本身**；本地应用以
`spring-boot:run` / IDE 启动（profile `local`），生产部署形态不在 compose 范围内。

## 2. 客户端接入口径（application.yml）

- `tioka.messaging.mode`：`rocketmq | in-memory`，默认 `${TIOKA_MESSAGING_MODE:in-memory}`；
  `application-prod.yml` 默认 `${TIOKA_MESSAGING_MODE:rocketmq}` 且 `relay-enabled: true`。
- `rocketmq.producer.group`：`${TIOKA_MQ_PRODUCER_GROUP:tioka-producer}`，`send-message-timeout: 3000`。
- `rocketmq.name-server`：**默认注释掉**（application.yml 第 167 行），IT / gate 通过
  DynamicProperty / GateCluster 注入；未注入时Producer/消费者都不会创建或启动
  （RocketMQAutoConfiguration 要求 name-server + producer.group 齐备才建 `DefaultMQProducer`）。
- 中继（outbox → RocketMQ）：`relay.batch=200`、`relay.idle-wait=200ms`、`relay.lock-ttl=15s`；
  IT/local/gate 默认 `relay-enabled=false`，避免后台线程与 LeaseReaper / Redis 关停串扰。
- 信封校验：`tioka.messaging.validate-envelope=true`；消费重试：`tioka.messaging.consumer.max-attempts=3`。

## 3. Topic 清单（*MqTopics 常量类，biz 内禁止硬编码）

每个模块 api 包下有常量类，`TOPIC` 即物理 topic 名：

| Topic | 常量类 | 代表 Tag / 跨域事件 |
| --- | --- | --- |
| `tioka-system` | `com.tioka.system.api.mq.SystemMqTopics` | user.created / role.changed / audit.emitted；`tioka.identity.user_disabled.v1`、`tioka.identity.member_removed.v1` |
| `tioka-runtime` | `com.tioka.runtime.api.mq.RuntimeMqTopics` | thread.* / turn.* / item.* / tool.invoked / approval.requested 等；`tioka.runtime.turn_finished.v1` 等 |
| `tioka-orch` | `com.tioka.orchestration.api.mq.OrchestrationMqTopics` | event.received / run.claimed / run.completed / attention.* / trigger.fired；`tioka.orch.run_created.v1` 等 |
| `tioka-chat` | `com.tioka.chat.api.mq.ChatMqTopics` | conversation.created / message.appended / message.streamed |
| `tioka-agent` | `com.tioka.agent.api.mq.AgentMqTopics` | agent.published / agent.enabled / binding.changed；`tioka.mas.grant_issued.v1` 等 |
| `tioka-flow` | `com.tioka.flow.api.mq.FlowMqTopics` | draft.saved / version.published / run.* / publication.changed |
| `tioka-knowledge` | `com.tioka.knowledge.api.mq.KnowledgeMqTopics` | document.ingested / index.updated / sync.finished；eval 事件暂挂此类 |
| `tioka-ontology` | `com.tioka.ontology.api.mq.OntologyMqTopics` | 跨域事件名常量（P3-00a 契约），无独立 tag |
| `tioka-governance` | `com.tioka.governance.api.mq.GovernanceMqTopics` | cfg.version_published.v1（遗留 tag）；`tioka.config.published.v1` 等 |
| `tioka-model` | `com.tioka.model.api.mq.ModelMqTopics` | provider.changed / routing.published / usage.recorded / failover.switched |
| `tioka-connector` | `com.tioka.connector.api.mq.ConnectorMqTopics` | im.message.sent / im.event.received / sync.*；`tioka.connector.im_send_requested.v1` 等 |
| `tioka-infra` | `com.tioka.infra.api.mq.InfraMqTopics` | file.uploaded / job.finished / config.changed |
| `tioka-skill` | `com.tioka.skill.api.mq.SkillMqTopics` | skill.imported / skill.published / binding.changed |
| `tioka-mcp` | `com.tioka.mcp.api.mq.McpMqTopics` | server.registered / tools.refreshed / grant.changed / health.failed |
| —（无 TOPIC 常量） | `com.tioka.eval.api.mq.EvalMqTopics` | 仅有跨域事件名 `tioka.eval.run_finished.v1` 等（P3-00b 骨架） |

**部署口径**：RocketMQ 4.x/5.x 的 topic 可自动创建（broker 默认 autoCreateTopicEnable），
仓库内**没有** topic 预创建脚本；私有化环境如关闭自动建 topic，需按上表手工创建 14 个物理 topic。

## 4. 消费组与幂等

- 消费者实现 `com.tioka.framework.messaging.IdempotentConsumer`（继承它的 biz 类即消费者，
  ArchUnit 强制；**不使用** `@RocketMQMessageListener` 注解）。
- `RocketMqConsumerBootstrap`（`@ConditionalOnProperty tioka.messaging.mode=rocketmq`）在
  `ApplicationReadyEvent` 后为每个消费者建一个 `DefaultMQPushConsumer`：
  - **消费组名 = `resolvedConsumerName()`**（消费者名即消费组，一一对应）；
  - 订阅 `resolvedTopic()` + `resolvedTagExpression()`（空则 `*`）；
  - `MessageListenerOrderly` 顺序消费；失败返回 `SUSPEND_CURRENT_QUEUE_A_MOMENT`；
  - `maxReconsumeTimes = tioka.messaging.consumer.max-attempts`（默认 3）。
- 幂等与死信统一由 `IdempotentDeliveryService` 处理：
  - `sys_consumed` 表按 `(consumerName, eventId)` `tryMarkConsumed` 去重；
  - 重试耗尽（`reconsumeTimes >= max-attempts-1`）写 `sys_dead_letter`（status=OPEN），独立事务；
  - 信封携带 `traceparent` / `traceId`，消费侧恢复 Span 并回写 MDC（P2-L6）。

## 5. 本地 vs 生产差异

| 维度 | local / IT / gate | prod（application-prod.yml） |
| --- | --- | --- |
| mode | in-memory（默认） | rocketmq（默认，可覆盖） |
| relay | 关（默认 false） | 开（true） |
| name-server | 显式注入才启用 | 需设置 `TIOKA_MQ_NAMESRV` / `rocketmq.name-server` |
| model / mcp | mock=true | mock=false |
| in-memory 语义 | 进程内重试至 max-attempts，末次失败写死信，与 RocketMQ 共用幂等与死信表 | — |

## 6. 运维检查点

1. `rocketmq.name-server` 未配置时消费者**静默跳过启动**（仅 WARN 日志）——生产上线必查
   `RocketMQ 消费者已启动 group=... topic=...` 日志。
2. 死信：查 `sys_dead_letter`（status=OPEN）确认积压；重新投递走运维手段（当前无重放接口，未落地）。
3. 中继双实例只有一个在发（lock-ttl 15s 抢锁），扩容实例不影响顺序消费。
4. tag 表达式变更（`resolvedTagExpression`）需同步检查订阅关系，避免同消费组多订阅冲突。

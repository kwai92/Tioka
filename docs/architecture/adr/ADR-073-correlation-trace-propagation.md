# ADR-073 — 关联上下文与 Trace 传播：request_id、subject、跨异步边界与采样

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-07
- 覆盖工作项：OBS-03、OBS-11（新增）；修订 GOV-08、ORC-02、RT-05、FLW-14a
- 来由：[调研 09](../research/09-审计与可观测缺口分析.md) 第 3 节 D1、D11，选项 A2；ADR-029 第 75 条
- 相关：[ADR-025](ADR-025-governance.md)、[ADR-020](ADR-020-orchestration.md)、[ADR-036](ADR-036-business-ontology-domain-pack.md)、[ADR-039](ADR-039-delegation-grant.md)、[ADR-072](ADR-072-audit-event-model-coverage.md)、[ADR-074](ADR-074-external-interaction-ledger.md)、[ADR-075](ADR-075-unified-timeline-evidence.md)、[M2-01](../construction/M2-01-消息与Outbox.md)
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。
- 写法说明：只用文字和表格，不附代码。

## 背景

GOV-08 让一次 Turn 及其经 outbox 的下游在一条 trace 里，但：trace 会被采样掉；审批挂起数小时后续跑、编排定时器触发、业务系统隔天回调都会开始新的 trace；trace 不带业务含义，按「一份合同」查不到。审计、用量、外部交互之间目前也只有 `trace_id` 一个关联字段（`model_usage` 还没有）。

## 决定

### 1. 关联上下文（CorrelationContext）

| 键 | 含义 | 生成 | 传播 |
| --- | --- | --- | --- |
| `trace_id` / `span_id` | OTel 技术链路 | OTel | W3C `traceparent` |
| `request_id` | 一次「业务请求」的根编号；同一件事在挂起、续跑、重试、子 Agent、下游事件中保持不变 | 入口生成（ULID，前缀 `req`）：HTTP 入口、IM 入站、Webhook、A2A、编排规则触发、定时器、业务系统事件 | HTTP 头 `X-Tioka-Request-Id`、OTel Baggage `tioka.request_id`、outbox 信封、`runtime_turn`、`runtime_approval` |
| `subject` | 业务主体引用，形如 `{类型}:{ID}`，如 `lease.contract:HT-2026-0012`、`kb.document:doc_x`、`agent:ag_x` | 入口或第一个能识别主体的环节设置（编排规则映射、业务连接器入站、工具声明） | 同上，Baggage `tioka.subject`；可多值（主主体 + 关联主体） |
| `parent_request_id` | 由另一个请求派生时的来源（如合同审查派生的通知请求） | 派生时设置 | 同上 |
| 运行键 | `thread_id`、`turn_id`、`run_id`、`flow_run_id`、`approval_id`、`change_set_id`、`grant_id` | 各自生成 | 进程内上下文；落库时写入各记录表 |

规则：

- **trace 可以断，request_id 不断**。续跑、重放、定时触发时恢复原 `request_id` 与 `subject`，同时新开 trace 并用 **span link** 指向原 trace。
- 外部传入的 `X-Tioka-Request-Id` 只在可信来源（服务账号、已签名 Webhook）时采纳，否则另生成并把外部值记在 `details.external_request_id`。
- Baggage 只放 `request_id`、`subject`、`origin` 三项，不放用户 ID 与业务内容；出网时剥离 Baggage，只发 `traceparent`（对方不可信时连 `traceparent` 也不发，按连接器配置）。

### 2. 每个边界怎么传

| 边界 | 做法 |
| --- | --- |
| HTTP 入站 | 过滤器读取或生成 `request_id`，放入上下文、MDC 与 OTel Baggage；响应头回写 |
| outbox / MQ | 信封增加 `requestId`、`subject`（M2-01 已加 `traceparent`、`replayOf`）；消费者恢复上下文；重放保留原 `requestId` |
| Turn 派发与续跑 | `runtime_turn` 增加 `request_id`、`subject`；他实例执行或审批续跑时恢复 |
| 审批 | `runtime_approval` 增加 `request_id`、`subject`；审批决定的 HTTP 请求有自己的 `request_id`，审计里两者都记（`details.target_request_id`） |
| 子 Agent 与委派 | 子 Turn 继承父的 `request_id`、`subject`；`grant_id` 链按 ADR-039 |
| 编排 | 规则触发时生成新 `request_id`，`parent_request_id` 指向触发事件的请求；Run 记 `request_id`、`subject` |
| Flow | `flow_run` 记 `request_id`、`subject`；节点调用 Agent 时传递 |
| 线程池与异步任务 | 统一用包装执行器复制上下文（OTel Context + MDC + 关联上下文） |
| 外部调用 | `EgressClient` 写外部交互台账（ADR-074）时带全部关联键 |

### 3. 落库的列

| 表 | 新增列 |
| --- | --- |
| `audit_event` | `request_id`、`subject`、`origin`、`user_agent`、`turn_id`、`run_id`、`approval_id`、`agent_version_id`、`policy_ids`、`run_as`（另见 ADR-072、ADR-039 的委派链列） |
| `model_usage` | `trace_id`、`request_id`、`subject` |
| `runtime_turn`、`runtime_approval`、`orch_run`、`flow_run` | `request_id`、`subject` |
| `sys_outbox` 信封 | `requestId`、`subject` |
| `ntf_delivery`、`trust_action` | `request_id`、`subject` |

索引：`(request_id)`、`(subject, ts)` 两类，在审计、外部交互与时间线表上建。

### 4. OTel 补充（GOV-08 修订）

- **采样**：应用侧 `parentbased_always_on`，由 Collector 做尾部采样：错误、超过时延阈值、带 `tioka.subject` 的业务请求、被审计为 DENIED 的请求全保留，其余按比例（缺省 10%）。不部署 Collector 的小规模私有化可直接全量。
- **span 属性**：增加 `tioka.request_id`、`tioka.subject`、`tioka.origin`；GenAI 属性按 OTel GenAI 语义约定（第 67 条）。
- **日志**：日志模式带 `trace_id`、`span_id`、`request_id`；缺省日志聚合为 Loki + Promtail（Helm 可选），可接客户 ELK；Grafana 中 trace 与日志互跳。
- **管道健康指标**：审计写入失败与降级数、转发积压、封印延迟、时间线投影积压、外部交互失败率，纳入 GOV-13 告警。

## 后果

- 任何记录都能按 `request_id` 或 `subject` 拉出来，时间线（ADR-075）有了可靠的键。
- trace 只承担排障职责，采样不再影响审计与追溯。
- 代价：M2 关联上下文与传播 3 人日；M4 完整 OTel（采样、日志、健康指标）4 人日。

## 未采用的备选

- 只用 `trace_id` 做关联（A1）：采样与断链问题无法解决。
- 用 OTel Baggage 传全部运行键：Baggage 会随 HTTP 出网，泄露内部 ID；也会增大每个请求的头。

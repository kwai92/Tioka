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

## 修订：与 agent-autoloop、dsh-superagent 对齐（2026-11-05）

S2 的对标对象有两个公开原型：[KKKKhazix/agent-autoloop](https://github.com/KKKKhazix/agent-autoloop)（MIT，事件→规则→领活→回执→卡片的单机内核，SQLite 事件溯源），以及 [deepseek-ai/dsh-superagent-prototype](https://github.com/deepseek-ai/dsh-superagent-prototype)（MIT，监听 GitHub / 飞书、按授权接单的主动式编码 Agent）。核对后，上文主链路与两者一致，以下 9 处按对标补齐，与上文冲突时以本节为准。dsh-feishu 没有公开资料，飞书部分仍按 ADR-021。

| # | 能力 | 对标做法 | Tioka 定案 |
| --- | --- | --- | --- |
| 1 | 处置与决定 | dsh：每个 Trigger 先得到 stored / duplicate / filtered；进 Eval 后得到 Decision（accept / stop / ignore / unavailable） | `ingest` 返回 `disposition`（STORED / DUPLICATE / FILTERED）。每条命中的规则写一条 `orch_decision`（ACCEPT / IGNORE / STOP / UNAVAILABLE，附原因与规则版本），why 时间线从决定开始 |
| 2 | 接单模式 | dsh：`off` / `observe` / `auto`；显式请求在任何模式下都生效 | 规则增加 `accept_mode`，默认 `observe`。事件分**显式**（在 IM 里 @ 机器人、指派给机器人、审批答复）与**推断**（仅靠规则条件命中）。`observe` 下推断事件只记一条「本会接单」的决定，不创建 Run；管理员审阅后切到 `auto`。机器人账号发出的事件只能算推断 |
| 3 | 授权记录 Mandate | dsh：Mandate 不可变，记录策略版本、范围、强度、命中规则、发起人身份、来由（addressed / followup / admission / bot）；只有接单创建、人工停止、复验失败三种变化 | 新增不可变表 `orch_mandate`，接单时与工单同事务创建。每次续跑（审批答复、later 到期、子任务回来）前按当前规则与权限复验，失败即撤销并停止工单。服务账号仍是执行身份（上文），Mandate 记录「凭什么接这单」 |
| 4 | 回执 | autoloop：结局 done / nothing / failed / cancelled 加 usage；回执整体校验后同事务生效；越界的 resolve 进 rejected；`emits` 未声明不能发事件 | `Receipt` 增加 `outcome`（DONE / NOTHING / FAILED / CANCELLED）。`failed` 只允许带 notice。规则声明 `emits` 白名单，回执里不在白名单的事件进 `rejected` 并记事实，不影响结局。回执作为整体校验后在一个事务里生效 |
| 5 | 回执重写 | autoloop：回执格式错时在原会话让 Agent 重写，最多两次；仍失败则保留业务结局、作废回执、发卡片说明 | 采用。重写用同一 thread 的新 Turn（origin=orch，input 为校验错误），最多 2 次；不触发业务重试 |
| 6 | 卡片去重 | autoloop：`ask.key` 在同一事项内、卡片未关时不重发；`later` 同 key 视为改时间；卡片答复只算第一次 | `AttentionSpec` 增加 `key`。同一工单同 key 且审批未决时复用原审批；`later` 同 key 更新 `orch_timer.next_fire_at`。`decideApproval` 只接受 PENDING → 终态一次，并发答复以先提交者为准（ADR-016 审批状态机） |
| 7 | 通知策略 | autoloop：规则 `tell` = always / needed / never，默认 needed | 规则增加 `tell`，默认 `needed`：只在需要人处理或有结果时通知。连续失败第 3 次通知一次（上文熔断阈值 5 次暂停规则不变） |
| 8 | 跟进订阅 | dsh：工单存活期间及结束后两周内持续跟踪原会话，后续消息归到同一工单（来由 followup） | 新增 `orch_subscription`：工单绑定外部会话（IM 话题、GitHub Issue/PR 等），存活期间及结束后 14 天内，该会话的新消息按 `subject_key` 归到原工单，来由记 followup |
| 9 | 别处任务 | autoloop `elsewhere`：在别处运行的定时任务只登记、不启动，可以主动通知 | P2。管理端可登记外部任务，外部任务凭服务账号 API Key 调 `POST /api/orch/tell` 发通知卡片，不进规则引擎 |

**刻意不同之处**：两者都是单机原型（SQLite、本机进程、无系统隔离）。Tioka 用 PostgreSQL + Redis 支持多实例，执行交给 Runtime 并在沙箱里跑代码（ADR-027），跨渠道身份通过 `sys_external_identity` 映射到同一用户（dsh 不做映射），预算是硬配额而不只是用量统计。autoloop 的事件溯源内核（纯函数 `decide` + 唯一投影写入者 `evolve`）不照搬，但借鉴它的验证方法：编排状态机的契约测试加入随机步进模拟与动作顺序穷举（ADR-030 第 4 条）。

**新增表**

```sql
CREATE TABLE orch_decision (id VARCHAR(32) PRIMARY KEY, event_id VARCHAR(32) NOT NULL, rule_version_id VARCHAR(32) NOT NULL,
  strength VARCHAR(16) NOT NULL,                 -- EXPLICIT / INFERRED
  decision VARCHAR(16) NOT NULL,                 -- ACCEPT / IGNORE / STOP / UNAVAILABLE / WOULD_ACCEPT
  reason TEXT, work_item_id VARCHAR(32), create_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE orch_mandate (id VARCHAR(32) PRIMARY KEY, work_item_id VARCHAR(32) NOT NULL,
  rule_version_id VARCHAR(32) NOT NULL, service_account_id VARCHAR(64) NOT NULL,
  strength VARCHAR(16) NOT NULL, via VARCHAR(16) NOT NULL,      -- ADDRESSED / FOLLOWUP / ADMISSION / BOT
  requester_source VARCHAR(64), requester_external_id VARCHAR(255), requester_user_id VARCHAR(64),
  scope_json TEXT NOT NULL, status VARCHAR(16) NOT NULL,        -- ACTIVE / REVOKED
  revoked_by VARCHAR(64), revoked_reason TEXT, create_at TIMESTAMPTZ NOT NULL DEFAULT now(), revoked_at TIMESTAMPTZ);
CREATE TABLE orch_subscription (work_item_id VARCHAR(32) NOT NULL, source VARCHAR(64) NOT NULL,
  external_thread_id VARCHAR(255) NOT NULL, until_at TIMESTAMPTZ, PRIMARY KEY (source, external_thread_id));
ALTER TABLE orch_rule_version ADD COLUMN accept_mode VARCHAR(16) NOT NULL DEFAULT 'observe';
ALTER TABLE orch_rule_version ADD COLUMN tell VARCHAR(16) NOT NULL DEFAULT 'needed';
ALTER TABLE orch_rule_version ADD COLUMN emits_json TEXT;
```

## 修订：可信放权（2026-11-05）

- `orch_mandate` 增加 `agent_version_id`；派工复验时同时检查该 Agent 的授权书状态为 ACTIVE，否则决定记为 UNAVAILABLE（[ADR-032](ADR-032-agent-charter.md)、ADR-029 第 36 条）。
- 规则增加 `shadow` 与 `actual_mapping_json`：`accept_mode = observe` 下的推断事件除记 `WOULD_ACCEPT` 外，可创建影子 Run（`origin = shadow`），结案后用 `actual_mapping` 取人工实际处理结果做比对（[ADR-034](ADR-034-autonomy-ladder.md)）。
- Run 进入终态时发 `tioka.orch.run_finished.v1`（与 `orch_fact` 同事务写 outbox），供价值账本记账（[ADR-035](ADR-035-value-ledger.md)）。
- 一个 Run 内的写操作组成一个撤销组，撤销产生的「无法撤销」清单与补偿失败以 Attention 交给 Agent 负责人（[ADR-033](ADR-033-reversible-actions.md)）。

## 修订：Agent 架构调研落地（2026-11-06）

来由：[调研 01](../research/01-Agent与多Agent架构调研.md)、[调研 02](../research/02-Agent执行策略与执行器插件调研.md)；负责人同意按调研建议设计，见 ADR-029 第 67 条。

- **与 SuperAgent 的关系**：dsh-superagent（主动接活）与 DeerFlow 2.0（通用执行底座）两种「SuperAgent」，在 Tioka 中分别由本 ADR（接单、研判、Mandate、回执、跟进）和 Runtime / 子 Agent / 沙箱 / Skill（ADR-023、ADR-027、ADR-038）承担，不另设 SuperAgent 模块。开箱即用的「通用助手」模板（AST-10）在 M4 之后提供。
- **长时目标**：跨天推进同一目标用 ADR-046 的 `GOAL_LOOP`（M4 之后），它复用本 ADR 的 `later` 与工单，不新增调度器。
- **外部执行器**：规则派工的目标 Agent 可以使用外部执行器（[ADR-047](ADR-047-external-executor.md)，M4 之后），编排侧无差别。

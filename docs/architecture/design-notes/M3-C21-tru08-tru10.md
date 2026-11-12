# M3-C21：TRU-08 爆炸半径与撤销熔断 + TRU-10 自动降级与通知

> 批次：C-21（G3 闸门攻坚第二期）。依据 `M3-05` §6 / §8 / §10、`ADR-033`、`ADR-034`、
> `M3-00` §8.1。目标：把 `G3UndoAutonomyGateIT` 从 `@Disabled` 骨架转为真实现，
> G3 真实现数 4/20 → 5/20。

## 一、范围与依赖复核

上游已收口：`TRU-07`（C-4，撤销 / 倒序补偿 / 当日累计扣回）、`TRU-09`（C-4，等级 / 判定表 /
观察期 / 抽检）、`TRU-02`（C-2b，有效权限与四眼）、`TRU-03`（C-19，负责人失效）、
`NTF-01`（C-10，通知渠道与 outbox）、`GOV-07`（C-2b，Cedar）。
本批不新开工任何上游工作项。

## 二、实现清单

### TRU-08（runtime-biz）

| 件 | 落点 |
|---|---|
| 第 11 步 | `BlastRadiusPipelineStep`（`order=ToolPipelineOrders.BLAST_RADIUS=11.0`）：按（Turn、动作类别）累计变更单 `records` / `amountMicros`，超过授权书 `perRunRecords` 或本次超过 `perCallAmountMicros` → `ESCALATE` |
| 累计存储 | `RunBlastRadiusStore` + `RedisRunBlastRadiusStore`（`trust:blast:{turnId}:{actionClass}`，TTL 2h） |
| 上下文传值 | `DefaultToolGateway.prepare` 把 `changeSet.totals()` 写入 `changeset.records` / `changeset.amountMicros`（K1b：步骤间只经 ctx 传值） |
| 熔断判定 | `UndoCircuitBreaker`：读 `trust_undo_circuit` 计数与空间策略，命中阈值后经 `opened_at` CAS 只开一次，发 `tioka.trust.undo_circuit_open.v1` |
| 阈值配置 | V226 `trust_undo_circuit_policy`（空间级 3 / 1 / 60 分钟缺省）+ `PUT /api/trust/undo/circuit-policy`；每次变化写 `trust.undo.circuit_policy_changed` 审计 |
| 计数窗口 | `TrustUndoCircuitRepository.recordUndo(..., windowMinutes)` 由 V202 硬编码 1h 改为按空间策略；`onUndo` 在 `UndoService.undo` 事务内调用 |
| 工具注册点 | `ToolRegistry.register(ToolProvider)`（CopyOnWriteArrayList）：供 ENG-04d 闸门基座运行时装载 FakeErp，同时是 FND-12 插件宿主的登记点 |

### TRU-10（governance-biz）

| 件 | 落点 |
|---|---|
| 消费者 | `AutonomyDemotionConsumer`（topic `tioka-governance`、tag `*` 按信封事件名分流） |
| 降级服务 | `AutonomyDemotionService`：`action_undone` → 该类别降一级（最低 L1）；`compensation_failed` / `undo_circuit_open` → 全部已登记类别降 L1 |
| 事件与审计 | 经 `AutonomyApi.setLevel` 写 `trust_autonomy_event`（from/to/reason）+ 审计 + `tioka.trust.autonomy_changed.v1` |
| 负责人通知 | 经 `NotificationPort`（NTF-01 桥接）发 `trust.autonomy.demoted` 卡片；owner 取 `AgentCharterPort.getAgent(agentId).ownerId()` |
| 幂等 | V226 `trust_autonomy_demotion` 主键 + `ON CONFLICT DO NOTHING`；一次撤销产生 N 条 `action_undone` 按（撤销范围、类别）只降一次 |
| 装配 | `AutonomyAutoConfiguration` 增 3 个 bean（台账 / 服务 / 消费者），沿用 `tioka.trust.autonomy.enabled` 开关 |

### 闸门

`G3UndoAutonomyGateIT` 一条用例串起 M3-05 §10 的 5 步：双实例 + Keycloak + 鉴权，
5 步 FakeErp `preview/apply` 真实落 `trust_action` → 撤销 → 补偿复原 / 累计扣回 →
降级 / 通知 / L1 转审批判定 → 第 3 次撤销熔断 → 补偿失败停组 + 人审。

## 三、裁决与偏离（逐条登记）

1. **单 Run 维度 = turnId**：`ToolCallContext`（M1 契约）无 `orchRunId`，生产
   `trust_action.orch_run_id` 也没有写入点（C-4 遗留）。M3-05 §6 允许「编排 Run **或** Turn」，
   故按 Turn 累计、撤销用 `scope=turn`。打通 orch_run_id 需改 M1 契约与 Orch→Runtime 派工链，
   留作后续批次。
2. **不经 LLM 剧本**：闸门 5 步写操作由 FakeErp `preview/apply` + `PreparedCallStore` 落库，
   聚焦撤销/降级链；工具循环本身由 G0/G1 闸门覆盖。
3. **第 11 步在 prepare 阶段累计**：审批被拒后累计值偏高（保守方向，只会多转审批）；
   Redis 不可用 `unknown=true` 同样转审批，与 `DailyCounterStore` 一致。
4. **Run 结束计数过期用 TTL 2h**，不引入显式清理事件（覆盖最长 Turn）。
5. **补偿失败不经过熔断计数**：M3-05 §8 要求消费 `compensation_failed` 立即全类别降 L1，
   由 TRU-10 消费者直接实现；`comp_fail_count` 仍写入并纳入下一次撤销判定的阈值。
6. **降级幂等键**：`undo:{scopeId}:{actionClass}` / `compfail:{agentId}:{actionId}` /
   `circuit:{agentId}:{windowStart}`；超 150 字符截断 + hash（V226 列宽 160）。
7. **「全部类别」= `trust_autonomy` 已登记行**：未登记类别 `levelOf` 缺省就是 L1，
   无需写入；闸门口径预置两个类别 L2/L3 验证。
8. **通知需要两个开关**：`InfraNotifyAutoConfiguration` 类级要求 `tioka.notify.enabled=true`
   才注册整个通知平台，内部类级还要求 `tioka.notify.bridge.enabled=true` 才注册 M2
   `NotificationPort` 桥接。只开 bridge 会让端口缺席、降级照常写库但卡片静默跳过（闸门第三轮
   暴露）。两者缺省 false（C-10 口径）；负责人取不到时收件人退化为 `workspace:{id}`，不静默丢通知。
9. **`AutonomyApi.setLevel` 四参口径**：`actorKind` 六参重载未暴露在 api 接口上，
   事件 `kind` 由 `DefaultAutonomyService` 按 from>to 判为 `DEMOTE`，`actor=system`；
   如需区分自动/手动降级来源，后续把 `actorKind` 提到 `AutonomyApi`。
10. **C-4 未尽事项 1（governance 不可达时整体降级联动）未做**：`AutonomyGatePipelineStep`
    判定异常已按 `REQUIRE_APPROVAL` 兜底、`levelOf` 失败按 L1 兜底；短暂 store 抖动即全类别
    永久降 L1 风险大于收益，保留人工裁决，不自动联动。
11. **V226 三张表都带 workspace_id**，不进 `workspace-scope-exempt` 清单；新建 DO 均继承
    `TechnicalRecordDO`，自定义 SQL 全部放 `mapper/*.xml`（九套架构规则）。
12. **迁移号**：顺序取 V226（V223 C-16 / V224 C-17 / V225 C-18 已占用），契约副本同步
    `docs/contracts/db/`；新事件契约 `docs/contracts/events/tioka.trust.undo_circuit_open.v1.json`。
13. **actionClass 口径仍不统一（既有遗留，本批登记不改）**：`DefaultToolGateway.prepare`
    给 `insertProposed` 传的 `actionClass` 是工具名 `name`，而授权书类别是
    `classify(toolName).code()`；`AutonomyDemotionConsumer` 以事件 `actionCategory` 作降级键。
    因此生产链路上 TRU-09/TRU-10 的等级键落在「工具名」，闸门按授权书类别（`ar.writeoff` /
    `contract.review`）验收的是规格口径。统一需在 `insertProposed` 前接 `classify` 并回归
    TRU-01/07/09 全链，超出 C-21 范围，留待 M4 裁决；本批已在闸门按类别 seed，未伪装成生产口径。

## 四、上线注意

- 存量 `trust_undo_circuit` 行无 `opened_at`，升级后首次达阈值会触发一次降级（符合预期）。
- 空间阈值从缺省改为自定义值后，只影响之后的计数窗口；已有 `window_start` 行按新窗口判定。
- `tioka.trust.blast-radius.enabled` 缺省 true，但只有授权书配了 `perRunRecords` /
  `perCallAmountMicros` 才生效，未配置限额的 Agent 行为不变。
- 通知需同时开启 `tioka.notify.enabled=true`（平台装配）与 `tioka.notify.bridge.enabled=true`
  （M2 端口桥接）；只开后者不发卡片。未开启时降级仍生效，只是不通知负责人。
- `PUT /api/trust/undo/circuit-policy` 要求 `workspace:manage`；空间管理员改阈值会写
  `trust.undo.circuit_policy_changed` 审计。不要把该端点下放给普通成员。
- 闸门/生产需 `tioka.messaging.relay-enabled=true` 才能把 outbox 事件投递给补偿、降级、
  通知消费者；gate profile 默认关闭该中继。
- `AutonomyChangeNotifier` 在非事务上下文调用 `AutonomyApi.setLevel` 时，
  `autonomy_changed` 事件因 outbox `Propagation.MANDATORY` 发布失败被 catch 忽略（闸门 seed
  阶段可见）。等级写库不受影响，但跨域事件会丢；属 C-4 既有遗留，本批未改，登记待裁决。

## 五、闸门转真暴露并修复的既有缺陷

1. **补偿永远收不了口（TRU-07 生产缺陷）**：`TrustCompensator.publishActionUndone` 把
   `row.getOrchRunId()` 原样放进事件 payload，而 `ToolCallContext` 不带 orchRunId、生产
   `trust_action.orch_run_id` 无写入点；`DomainEvent` 用 `Map.copyOf(payload)` 构造，遇到
   null value 抛无消息 NPE。`markCompensated` 已执行后抛异常 → 投递事务回滚 → 状态退回
   COMPENSATING → outbox 重投重复同一路径，`COMPENSATED` 永远到不了。修复：事件 payload
   统一经 `put(...)` 把 null 归一为空串（同时覆盖 `agentVersionId`），并在 `UndoIT` 增加
   `compensationWithoutOrchRunIdSucceeds` 回归用例。
2. **补偿重试语义**：`handle` 内层重试原本会在状态已推进到终态后继续撞
   `markCompensated`，把一次成功补偿误判为失败并生成负责人人审。修复：catch 中检查当前
   状态，已非 COMPENSATING 时抛回原异常让投递事务整体回滚，交给投递层的幂等重投
   （工具补偿本身幂等）；投递层耗尽重试后会写死信、动作可能停在 COMPENSATING 需人工介入，
   但不会再被误标为「补偿失败」。注入外部补偿失败的正常 3 次重试路径不变。
3. **测试桩回执 JSON 往返丢失类型**：`ApplyReceipt.compensationToken` 声明为
   `Map<String,Object>`，经 `trust_action.receipt_json` 序列化往返后 `changes` 元素退化为
   `LinkedHashMap`，`FakeErp.compensate` 原实现只认 `ChangeSet.Change` 而静默跳过所有变更，
   真实 DB 链路上撤销不会复原业务数据。修复：桩兼容两种形态并把 `amountMicros` 规范化回
   Long。连带发现 `UndoIT.fiveStepsRestored` 的快照取在 apply 之后（等于 SHIPPED），因此
   一直未真正断言复原；改为 seed 后 / apply 前的快照。
4. **离线桩与可配窗口不兼容**：`UndoService` 改调 4 参
   `recordUndo(..., windowMinutes)` 后，`UndoIT.InMemoryCircuit` 只覆写 3 参版本，落到父类
   空 mapper 抛 NPE 并被撤销的容错 catch 吞掉，熔断计数静默丢失。修复：桩同时覆写 3/4 参。

## 六、fresh-eyes 独立复核与处置

合并前做了一次独立只读复核（不修改文件、不跑构建），逐项核验 13 个修改文件 + 22 个新文件。
**已核验通过**：V226 迁移与契约副本逐字一致；新事件常量 ↔ JSON Schema ↔ 实际 payload 的 8 个
required 字段配对成立；runtime-biz / governance-biz 分层合规、自定义 SQL 均在 `mapper/*.xml`；
`sys_consumed` 消费幂等与 `opened_at` CAS 在 PG READ COMMITTED 下正确。

复核给出 3 个阻塞项，均已修复并纳入验证：

| # | 问题 | 处置 |
|---|---|---|
| 1 | `PUT /api/trust/undo/circuit-policy` 用 `@RequirePermission({})`（仅需登录），任意成员可把阈值调到 MAX 禁用熔断、或设成 1 恶意触发全类别降级 | 改 `Permissions.WORKSPACE_MANAGE`；principal 为空 / workspaceId 为空直接 403/400，避免写出永不被读取的孤儿策略行 |
| 2 | `TrustCompensator.onCompensationFailed` 走 3 参 `recordCompensationFailure`（固定 60 分钟），与 `UndoService` 的空间可配窗口不一致；自定义 120 分钟窗口下第 90 分钟的失败会误判窗口过期并清零累计撤销次数 | `TrustCompensator` 注入 `ObjectProvider<UndoCircuitBreaker>`，改调 4 参并复用 `windowMinutes(workspaceId)`；离线构造器传空 provider 保持兼容 |
| 3 | G3 步骤 5「全部类别 L1」在步骤 3/4 已是 L1 后为恒真，`demotionNotices()>=2` 只是累计值，且完全没断言「停止后续补偿」 | 步骤 5 前把 `contract.review` 抬回 L3；台账/通知改用**增量**断言；补 `countActions(turn4,"COMPENSATING")==1`；通知计数加 recipient 条件；`await` 超时把最后一次异常作为 cause |

同时修掉两项会放大风险的建议项：

- `UndoService.undo` 原本一个 catch 同时吞掉计数与熔断判定/事件发布异常。现拆开：仅计数写入
  保留 C-4 容错，判定失败抛回让撤销事务整体回滚（否则 `opened_at` 已消耗、降级事件却没发出去）。
- `UndoCircuitBreaker.evaluate` 在 `tryOpen` 之前先确认 `EventPublisher` 可用，缺席直接抛异常
  不消耗 CAS；新增 `UndoCircuitBreakerTest.missingPublisherDoesNotConsumeCircuitCas` 守护。

复核还列出若干观察/建议项，本批**不修**，登记为后续候选：熔断计数与 Redis 爆炸半径累计为
非原子 read-modify-write（并发丢计数，建议原子 UPDATE / Lua）；`deductDailyUsage` 是 Redis 扣减
不随 DB 事务回滚，重投可能重复扣（需扣回幂等台账）；第 11 步「依赖不可用」与「限额未配置」
未区分、bean 缺席时 fail-open；`tioka.trust.autonomy.enabled=false` 会连带关掉降级消费者；
等级无变化仍发降级卡片；`AutonomyDemotionConsumer` 超长幂等键用 `hashCode()` 而非稳定哈希；
`trust_undo_circuit_policy` upsert 非原子、`update_at` 不更新；`windowStart` 非严格 RFC3339。
以上不改变 C-21 的验收结论，但进入后续批次前需按风险排序裁决。

另：全量 `mvn -B verify` 第一轮暴露 `AuditCoverageIT.everyWriteEndpointAudited` 失败——新增写
端点未登记审计覆盖。服务层 `UndoCircuitBreaker.configure` 已写 `trust.undo.circuit_policy_changed`
明细审计，按既有 `COVERED_METHODS` 惯例登记，不重复套 `@Audited`。

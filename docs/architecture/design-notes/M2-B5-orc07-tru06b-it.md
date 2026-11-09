# M2 残余收口批次 B-5：ORC-07 三验收用例 + TRU-06b ChangeSetCardIT 测试补名

> 批次：M2 残余收口批次 B-5（纯测试代码 + docs，不改生产代码）
> 分支：`feature/m2-b5-orc07-tru06b-it`（基线 main = `adc51c9`）
> 收口范围：M2-00 第 75 行 ORC-07（`HopsIT`、`RulePauseIT`、`DeadLetterReplayIT`）、第 81 行 TRU-06b（`ChangeSetCardIT`）四个 IT 补名
> 硬约束遵守：零迁移、不改 `deploy/compose.yml`、不引新依赖、不涉及 workspaceId；生产逻辑缺失项只登记缺口不补码

## 一、生产逻辑调研结论（逐项）

| 行为 | 结论 | 证据 |
|------|------|------|
| 跳数熔断 | ✅ 已实现 | `OrchIngestService#ingest` 55–58 行：`event.hops() > maxHops` 时 `storeFiltered(event, INFERRED, "hops_exceeded")` 落 FILTERED 并终止；配置键 `tioka.orch.max-hops`（默认 8，44 行）；幂等键追加 `":filtered:hops_exceeded"`（174 行）；熔断后不进规则决定（82 行 `decideService.decide` 不可达）。设计口径：S2-Orchestration编排.md 第 28 行「熔断：hops TTL、连续失败停、预算熔断」；OrchApi.java:35「hops 派生深度，超过上限即丢弃（ORC-07）」 |
| 规则自动暂停（连续失败停） | ❌ 缺失 | orchestration-biz 全部 Java 源无 pause/resume/autoPause/暂停逻辑；规则版本 stage 仅 DRAFT/CANARY/STABLE/RETIRED（`OrchRuleAppService#publish` 94–139 行）；`OrchRuleAppService`/`OrchRuleController` 无 enable/disable/pause 接口，无失败阈值字段 |
| 死信写入 | ✅ 已实现 | `IdempotentDeliveryService#deliverOnce` 120–124 行：`reconsumeTimes >= maxAttempts-1` 时写死信并不再上抛；`writeDeadLetter` 276–298 行：落 `sys_dead_letter`，status=OPEN、attempts=重试次数+1、信封损坏时 eventId="unknown"；in-memory 侧 `deliverAndRetry` 96–112 行本进程重试至 max-attempts；RocketMQ 侧非末次失败上抛触发 broker 重投（`RocketMqConsumerBootstrap` 66–83 行 orderly SUSPEND） |
| 死信查询/重放/丢弃 | ❌ 缺失 | `SysDeadLetterRepository` 仅有 `insert`（16–18 行），无 list/replay/discard；FE-17（M2-00 第 100 行「死信列表、重放、丢弃；规则暂停与恢复」）依赖的 REST 出口不存在 |
| TRU-05 变更单域模型与 prepare/commit | ✅ 已实现 | `runtime-api spi/ChangeSet`（ADR-033）、`ChangeSetFactory`、`PreparedCallStore`（PROPOSED→APPLIED/STALE/FAILED）、`DefaultToolGateway#prepare:132`/`#commit:200`（含幂等 203、过期 STALE 207–210、漂移检测 242） |
| TRU-06b 变更单卡片后端 | ❌ 缺失 | `connector-api ImMessage` 无 ChangeTable 组件；M2-06 PK-25 指定的 `connector-biz/.../FeishuCardBuilder` 变更单分支不存在（全仓无命中）；`CardApprovalService#openOrReuse` 58–66 行 card_json 仅含 kind/key/title/summary/form/onTimeout，未按 M2-05 第 43 行带 `{changeSet, reversibility, estimated}`；审批台详情无变更单查询出口 |

## 二、测试交付物与覆盖点

| 测试类 | 位置 | 运行归属 | 覆盖点 | 结果 |
|--------|------|----------|--------|------|
| `HopsIT` | `tioka-module-orchestration-biz/src/test/.../application/ingest/` | 离线（纯 JUnit5 + Mockito，仿模块内 `PluginHostIT` 惯例：`*IT` 命名默认 surefire 排除，CI 用 `-Dtest` 承接） | ① 超限（hops=9>8）熔断：FILTERED 落库、strength=INFERRED、幂等键带 hops_exceeded、decide 不被调用；② 边界 hops=8 不熔断（STORED 落库后无规则转 FILTERED，幂等键不带原因）；③ 多跳派生链 0..8 逐跳接受、第 9 跳熔断；④ 有匹配规则时未超限派生事件正常入决定、超限熔断 | **4/4 通过** |
| `DeadLetterReplayIT` | `tioka-module-infra-biz/src/test/.../messaging/` | 离线写入部分同上；重放骨架 @Disabled | ① 非末次重试失败上抛且不写死信；② 末次失败写死信（consumer/topic/tag/eventId/payloadJson/error/attempts=3/status=OPEN/createAt）；③ in-memory deliverAndRetry 恰好 max-attempts 次、恰好 1 条死信；④ 成功不写死信；⑤ 信封损坏死信 eventId="unknown"；⑥ ❌ 缺口骨架 `ReplayAndDiscard`（@Disabled 嵌套类，实现后重写：list(OPEN)/replay 重投+状态流转/discard） | **5 通过 + 3 跳过（骨架）** |
| `RulePauseIT` | `tioka-server/src/test/.../orch/` | @SpringBootTest @Tag("it")（仿 `OrchTimerIT`）+ 类级 @Disabled 骨架 | ❌ 缺口骨架：验收口径注释于类头——连续失败达阈值→PAUSED；暂停期间 `findActiveRulesForEventType` 不返回、decide 不调用；恢复后继续；动作留审计 | 0 跑 / 3 跳过 |
| `ChangeSetCardIT` | `tioka-server/src/test/.../tru/` | 同上 | ❌ 缺口骨架：四种状态（PROPOSED/APPLIED/STALE/estimated，M2-05 第 111 行）卡片快照；飞书卡片规格（summary 标题、可逆性标签、前 10 条表格、合计、「查看全部」按钮）注释于类头 | 0 跑 / 4 跳过 |

## 三、运行验证记录

- `mvn -pl tioka-modules/tioka-module-orchestration/tioka-module-orchestration-biz -am test -Dtest=HopsIT -Dsurefire.failIfNoSpecifiedTests=false` → HopsIT **4/4 绿**
- `mvn -pl tioka-modules/tioka-module-infra/tioka-module-infra-biz -am test -Dtest=DeadLetterReplayIT -Dsurefire.failIfNoSpecifiedTests=false` → **5 通过 / 3 骨架跳过**
- `mvn -pl tioka-server -am test-compile` → 四个 IT 全部编译通过（@Disabled 类不启动上下文）
- `mvn -pl tioka-server test -Dtest='*Arch*'` → 架构测试 8 套 43 项全绿，无新违例
- 默认 `mvn test` 不受影响：`*IT.java` 由根 pom surefire excludes 排除（根 pom 64–66 行）；@Tag("it") 由 failsafe 承接、@Disabled 骨架跳过

## 四、缺口清单（登记到 M2 台账，本批不补生产代码）

1. **规则自动暂停/恢复**（ORC-07 中项）：连续失败阈值 → PAUSED、暂停期不派发、恢复入口、审计——阻塞 FE-17「规则暂停与恢复」。实现后移除 `RulePauseIT` @Disabled 补齐断言。
2. **死信查询/重放/丢弃入口**（ORC-07 后项）：`SysDeadLetterRepository` 仅 insert，需 list/replay/discard + REST 出口——阻塞 FE-17「死信列表、重放、丢弃」。实现后重写 `DeadLetterReplayIT$ReplayAndDiscard`。
3. **TRU-06b 变更单卡片后端**：ImMessage ChangeTable 组件、FeishuCardBuilder 变更单分支（PK-25）、card_json 带 changeSet/reversibility/estimated（M2-05 第 43 行）、审批详情变更单查询——阻塞 TRU-06 前端四种状态快照。实现后移除 `ChangeSetCardIT` @Disabled 补齐断言。
4. **真实 broker 重投语义**（RocketMQ orderly SUSPEND/重试节奏）与死信全链路（真库落行→重放→丢弃）：容器 IT 归属，随 CI 的 failsafe 承接；本批离线用例已覆盖投递服务纯逻辑。

## 五、M2-00 回写

- ORC-07（第 75 行）：标注「B-5 测试补名部分完成（跳数熔断离线 4/4 绿；缺口：规则自动暂停、死信查询/重放/丢弃入口）」。
- TRU-06b（第 81 行）：标注「B-5 测试补名部分完成（ChangeSetCardIT 骨架 @Disabled；缺口：ImMessage ChangeTable、FeishuCardBuilder 变更单分支、card_json 带 changeSet）」。

# M3 批次 C-1b：MH-06 四种路由模式、决策器 SPI、三个内置决策器、胜任度只记录（ADR-024）实现覆盖说明

> 批次：M3 批次 C-1b（MH-06 / MH-07 胜任度记录）
> 分支：`feature/m3-c1b-mh06`（基线 main = `5caea39`）
> 收口范围：V214 双表（路由事件时间线 + 胜任度）；路由计划 spec 模式结构（含 V1 平铺向后兼容）；`RoutingDecider` SPI（model-api）与三个内置决策器（启发式 / 大模型 / 外部 HTTP）；Turn 开始决策编排 + 1.5s 超时兜底；组内过滤与 preferPreviousInGroup；胜任度只写不读参与决策
> 硬约束遵守：全程无 tenantId（model_route_event / model_competence 按 workspace 隔离，口径同 model_usage）；业务 ID 一律 `Ids.newId()`；不引新依赖；不改 `deploy/compose.yml`；不动 MQ 信封契约；不动 M3-00 与 34 号文件

## 一、口径整理（ADR-024「路由计划」「一次选模」「胜任度」+ M3-06 §6）

| 项 | 口径 |
|------|------|
| 四种模式 | FIXED 不调决策器；DIFFICULTY 决策器输出难度按 `difficultyToGroup` 映射组；BRANCH 输出类别按 `categoryToGroup` 映射组；FREE_FIRST 先试免费组，失败升主力组（`escalation.mainGroup`）或直接失败 |
| 决策时机 | 只在 Turn 开始决策；同 Turn 工具续写沿用冻结（model_turn_freeze，V102 既有机制）；上下文压缩后允许重新决策（压缩属 RT-10 范围，本批提供重新决策入口＝新 Turn） |
| 决策超时 | 默认 1500ms（`tioka.model.routing.decide-timeout`；spec `decision.timeoutMs` 可覆盖）；超时或异常走 `decision.fallbackGroup`（缺省主力组）并写 FAIL 路由事件 |
| 组内过滤 | 按候选顺序：不存在 / 未启用 / 冷却中（经既有 `ModelBindingFilter` 链，ModelBindingCooldownService 等）跳过；能力过滤（工具/视觉）M3 以 binding.kind+enabled 承接，视觉能力字段归 MH-14/MH-12 |
| preferPreviousInGroup | ADR-024 修订：不得默认关闭 → spec 缺省 true；上一 Turn 选中 binding 在本组内且通过过滤 → 沿用（REUSE 事件 + 冻结 reason=reuse:<组>）；上一 Turn 记录读 model_route_event 最近 SELECT/REUSE |
| 胜任度 | Turn 结束打 0–1 分（`RoutingTelemetry.recordCompetence`），source 标注来源；只记录不参与决策（M3 口径）；`latestCompetenceScore` 仅作为 DecisionInput.previousCompetence 输入（ADR-024：存表并作为下一次决策的输入），不影响选模 |
| 发布生效 | 模式 spec 走 GOV-12 cfg_version（C-1a 已接入），执行侧读当前生效版本 → 计划发布新版本/回滚立即生效；路由事件带 plan_id + plan_version_no |

## 二、迁移 V214（预留号落表）

| 号 | 内容 | 备注 |
|------|------|------|
| V214 | `V214__m3_mh06_route_event_competence.sql`：① `model_route_event`（id/workspace_id/thread_id/turn_id/plan_id/plan_version_no/kind/mode/decider_id/decided_group/selected_binding_id/confidence/reason/latency_ms，kind ∈ DECIDE/SELECT/REUSE/FAILOVER/FAIL）+ 三个索引（thread/turn/workspace+时间）；② `model_competence`（id/workspace_id/binding_id/turn_id/score CHECK 0–1/source，UNIQUE(turn_id, binding_id)）+ binding 索引 | M3-00 L530 预留号；V117 不含两表故 V214 落号。kind 列名从 ADR（task 口径「step 类型」）。契约副本同步 `docs/contracts/db/V214__m3_mh06_route_event_competence.sql`；model-test-schema.sql 同步两表（H2 口径）。FlywayMigrationIT 8/8 实库验证 |

UNIQUE 语义按 ADR：ADR 数据模型 `model_competence` 以 turn_id 为 PRIMARY KEY；本表保留业务 id 主键 + `UNIQUE (turn_id, binding_id)` 承接「每 Turn 一条打分」（同 Turn 同 binding 幂等，服务层二次保护）。

## 三、spec 结构与向后兼容口径

MH-06 新 spec（cfg_version 内容）：

```json
{
  "mode": "DIFFICULTY",
  "groups": {"main": ["bnd_a", "bnd_b"], "free": ["bnd_c"]},
  "categoryToGroup": {"coding": "main"},
  "difficultyToGroup": {"EASY": "free", "MEDIUM": "main", "HARD": "main"},
  "decision": {"deciderId": "heuristic", "bindingId": "bnd_dec", "timeoutMs": 1500, "fallbackGroup": "main"},
  "escalation": {"freeGroup": "free", "mainGroup": "main"},
  "preferPreviousInGroup": true
}
```

- **兼容映射（`RoutingPlanSpecParser`）**：spec 含 `mode` 字段 → 新结构；否则视为 V151 存量平铺 spec（`ModelHubSpecs.planSpec`：type/primaryBindingId/fallbackBindingId/hardBindingId/fallbackChain）→ 等价 FIXED：`groups.main = [hardBindingId‖primaryBindingId] + fallbackChain`，preferPreviousInGroup=true；执行侧对 legacy 快照（`legacy=true`）整体回退既有 `resolver.resolve` 路径，行为不变（存量 C-1a 计划零影响）。mode 值非法/解析异常同样回退 legacy，不阻断调用。
- **管理模式计划的入口**：`createPlan` 投影列仍只承载平铺字段（保留 DIFFICULTY 列创建 501 与 `ModelRouterFreezeTest` 行为）；模式丰富的计划经 C-1a 已落 `/configs/model.routing_plan/{id}/draft|publish` 草稿流发布（cfg_version 为权威内容源）。主理人统一回写口径时可在 admin 面板层补「模式编辑器」。
- ADR `Difficulty` 取值 EASY/MEDIUM/HARD；`difficultyToGroup` 为本批登记的补充字段（ADR 的 RoutingPlanSpec 未列难度映射列名，组映射必须显式可配）。

## 四、实现决策

1. **SPI 放 model-api**（跨模块契约）：`com.tioka.model.api.routing` — `RoutingMode` / `Difficulty` 枚举 + `RoutingDecider`（`id()` / `decide(DecisionInput)`），`DecisionInput(userText, previousGroup, previousCompetence, hints)` / `Decision(category, difficulty, confidence, reason)` 与 ADR 签名一致。
2. **接入点：`DefaultModelGateway.chatTracked` 选模一步最小侵入**。`routeByPlan()` 前置调用 `RoutingPlanExecutor.select(ctx, request)`：返回 Resolution → 直接沿用（冻结已由执行器写入）；返回 null（FIXED 平铺 / 冻结命中 / 非会话调用 / purpose=DECISION）→ 既有 `resolver.resolve` 路径。网关以 `ObjectProvider<RoutingPlanExecutor>` 注入，编排器缺省装配时行为与基线完全一致；BizException（组耗尽）按 ADR 直接失败不静默回退，其余意外异常回退既有路径并打 span 标记。
3. **决策编排**（`RoutingPlanExecutor`，model-biz `application/routing`）：`planSelectionFor`（Resolver 新公开口）拿计划 + 别名/计划版本 → spec 源读 spec → 决策（单线程守护池 + Future 超时取消）→ 映射组 → 组内顺序过滤（enabled + ModelBindingFilter 链）→ preferPreviousInGroup 沿用判断（读 `model_route_event` 最近 SELECT/REUSE，最小实现，未引入缓存）→ 冻结 + Resolution（接力链 = 本组剩余候选 + 主力组候选）→ 每步写事件。
4. **三个内置决策器**：
   - `HeuristicRoutingDecider`（id=heuristic）：确定性纯函数。口径：category 关键词命中 coding/translation/summary/analysis 否则 general；difficulty userText ≥800 字 HARD、≥200 字 MEDIUM 否则 EASY，带工具调用至少 MEDIUM；confidence 固定 0.6。
   - `LlmRoutingDecider`（id=llm）：经 `ModelGateway` purpose=DECISION 自调用（`ObjectProvider<ModelGateway>` 防循环依赖）；固定提示词输出 JSON（剥代码围栏 + 花括号截取）；**口径偏差登记**：Prompt 域 sys.* 模板机制尚未建立，提示词先落类常量 `DECISION_PROMPT`，待 Prompt 域 sys 机制后迁移为 sys.routing_decision 模板。防递归双层：执行器对 purpose=DECISION 直接返回 null（决策调用不触发模式路由）；决策调用 ctx 的 threadId/turnId 置空（不污染冻结）。
   - `HttpRoutingDecider`（id 前缀 http）：经 `EgressClient` 出网（47-GL §1.8；HttpOAuthTokenClient 构造器注入先例），endpoint 配置化 `tioka.model.routing.http-decider.endpoint`，请求超时默认 1.5s；协议 POST {userText, previousGroup, previousCompetence, hints} → {category, difficulty, confidence, reason}。未配置 endpoint 时 decide 抛异常 → 执行器兜底组。
   - `RoutingDeciderRegistry`：heuristic/llm/http 前缀分发；未知 deciderId → 执行器兜底组 + FAIL 事件。
5. **持久化三层封装**（ArchUnit 合规）：`ModelRouteEventDO/ModelCompetenceDO` → `ModelRouteEventMapper/ModelCompetenceMapper`（BaseMapper）→ `RoutingTelemetryRepository` 接口 + `RoutingTelemetryRepositoryImpl`（infrastructure）→ `RoutingTelemetry`（domain @Repository 门面，仿 ModelEntities）；application 不直持 Mapper。
6. **胜任度入口**：`RoutingTelemetry.recordCompetence(workspaceId, bindingId, turnId, score, source)`（0–1 校验、无 turn 拒绝、同 Turn 同 binding 幂等忽略）；Turn 结束调用方（runtime/RH-XX 后续批次）负责触发打分；本批只提供入口与存储。
7. **ENG-05**：全组件 ObjectMapper 构造器注入；测试自持实例（测试代码豁免）。

## 五、测试清单

| 测试 | 结果 | 覆盖 |
|------|------|------|
| `RoutingModesIT`（model-biz，离线） | 9/9 | FIXED 组内直选+冻结+SELECT 事件；legacy 平铺计划回退既有路径（null）；DIFFICULTY 难度映射 + DECIDE 事件字段（deciderId/confidence）；BRANCH 类别映射；FREE_FIRST 免费组耗尽 FAILOVER→主力组 SELECT；免费组不可升级直接失败 FAIL；preferPreviousInGroup 跨 Turn REUSE；未启用候选按序跳过；未知决策器兜底 |
| `DeciderTimeoutFallbackIT`（model-biz，离线） | 3/3 | 决策器 sleep 600ms/timeout 120ms → 兜底组 + FAIL 事件含「超时」与延迟；决策器异常 → 兜底 + 原因；超时窗口内正常决策走映射组（非兜底） |
| `CompetenceRecordIT`（model-biz，离线） | 5/5 | 打分读写；同 Turn 重复打分幂等；分值 0–1 校验；无 Turn 拒绝；路由事件时间线按 Turn 查询 |
| `RoutingPlanSpecParserTest`（model-biz，离线） | 6/6 | 新结构解析；V1 平铺 → 等价 FIXED（main=primary+chain、preferPrevious 缺省 true）；投影行 flatSpec → legacy；mode 非法回退 legacy；空 spec；timeoutMs 缺省 1500 |
| `HeuristicRoutingDeciderTest`（model-biz，离线） | 5/5 | id；短文本 EASY/general；长文本 HARD；关键词类别 + 工具调用升档；翻译类别 |
| `RoutingGatewayIntegrationTest`（model-biz，离线） | 3/3 | 网关 chat 链路经执行器按 DIFFICULTY 选中映射组 binding + 事件落库；编排未装配回退既有解析零事件；平铺 spec → legacy 口径 |
| model-biz surefire 全量 | 47/47 | 含 C-1a 既有 ModelRouterFreezeTest / DefaultModelGatewayRerankTest / ModelBudgetGuardTest / UsageRecorderCostTest / RetryBackoffTest / StructuredRequestBuilderTest / ProviderClient*Test 等回归 |
| model-biz 显式 IT（-Dtest） | 3+2 | ModelAliasIT 3/3、UsageCarriesVersionIT 2/2（C-1a 回归） |
| 新增 IT 合计 | 17 | RoutingModesIT 9 + DeciderTimeoutFallbackIT 3 + CompetenceRecordIT 5 |
| ArchUnit 六件套（server） | 46/46 | PersistenceArchTest 6、TiokaArchitectureTest 11、M2ArchitectureTest 5、M3ArchitectureTest 10、ModuleDependencyArchTest 5、RuntimeApiContractTest **9/9（保持）** |
| `FlywayMigrationIT`（server，Testcontainers pgvector:pg16） | 8/8 | V214 对真实 PostgreSQL 全量迁移链验证 |

运行方式备注：*IT.java 根 pom 默认 surefire 排除；离线验证用 `mvn -pl …model-biz -am test -Dtest='…'`（surefire 3.5 下 -Dtest 覆盖 excludes），与 C-1a 一致。

## 六、未覆盖项（如实登记）

- **真实 LLM 决策器联测**：purpose=decision 的 `sys.decision` 别名与真实供应商联测归 CI IT（本批以 FakeDecider/固定输出覆盖编排路径）。
- **外部 HTTP 决策器联测**：endpoint 协议为约定 JSON 契约，真实决策服务（含 HiRoute Jev）归 CI；出网通道（EgressGuard 白名单）配置项未登记到 ops 文档。
- **能力过滤粒度**：工具/视觉能力过滤以 binding.kind + enabled 承接；binding 视觉/工具能力位（ADR-024 capabilities_json）归 MH-14/MH-12 扩展。
- **competence 打分触发方**：Turn 结束打分入口已就绪，runtime 侧调用接线（用户反馈 / 裁判模型打分）归后续批次（ADR-068 校准前不参与决策）。
- **模式计划管理面**：createPlan 投影列仍平铺；模式编辑与发布校验（候选存在/决策 binding 已配/阈值合法）归 FE-24 与 admin 面板批次；本批仅经 cfg 草稿流生效。
- **`RoutingPlanRollbackIT`（M3-06 §6 提及）**：回滚后新 Turn 用旧计划版本——机制由 GOV-12 rollback + 执行侧读当前生效版本天然保证（spec 源每次 Turn 现读 cfg_version），专项 IT 归 MH-08/GOV-12 回滚测试体系。
- **向量匹配决策器（RTE-02）**：M4 在同一 SPI 上实现（ADR-024 修订），本批未含。

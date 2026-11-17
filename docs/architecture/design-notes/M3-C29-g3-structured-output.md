# M3 批次 C-29：G3 闸门攻坚（`G3StructuredOutputGateIT` 转真，SCH-06〜10）

- 分支：工作树（本批未执行 git 提交，合入后回填）
- 日期：2026-11-15
- 权威输入：**M3-00 §8.1**（`G3StructuredOutputGateIT` 行）、**ADR-066 §10.3**（异常逃逸测试）/ **§10.4**（闸门表）/ **§12**（SCH-06〜12）、ADR-043「修订：结构化输出的结局端口（2026-11-07）」
- 范围（M3-00 §8.1 原文）：`G3StructuredOutputGateIT` —— SCH-06〜10：合同 Schema 最终失败率 ≤ 0.5%、异常逃逸为零、未连 `failed` 端口的 Flow 发布被拒
- 前置：SCH-06（能力记录 V177 + 生成方式选择）、SCH-07（V232 结局/原因码列、拒答信号端到端）、SCH-08（`x-tioka-rules`/`x-tioka-ref`/领域钩子 WARN）、SCH-09（V233 降级阶梯/兜底值/人工补录/Flow 三端口，批次 C-26）、SCH-10（V234/V235 指标与告警，批次 C-28）
- 说明：本批设计说明按 design-notes 既有的**批次台账号**命名，取 C-28（SCH-10）之后的 **C-29**；本批只做闸门转真，**不改生产代码、不新增迁移**。

## 实现映射

| 模块 | 文件 | 内容 |
| --- | --- | --- |
| tioka-server(gate) | `src/test/java/com/tioka/server/gate/G3StructuredOutputGateIT.java` | 去 `@Disabled` 骨架转真实现（470 行）。步骤 1：真实 Runtime Turn 上的失败率 + 零逃逸 + 修复闭环；步骤 2：`DefaultFlowDslValidator` 规则级正/反/旁路三组断言（无 Spring 上下文） |

## 闸门接线（步骤 1）

`try (GateCluster cluster = GateCluster.start(1, SCRIPT, Map.of("tioka.ontology.schema.enabled", "true"), false))`：

- **`extraProps` 是唯一开关入口**——`GateCluster.baseProps` 不含 `tioka.ontology.schema.enabled`，故经四参 `start(int, String, Map<String,Object>, boolean)` 显式打开；`false` 即不开 Keycloak（本闸门只验结构化输出链，鉴权由其它 G3 用例覆盖）。
- **先发布 Schema 再发布 Agent**——`OntologyAutoConfiguration` 仅在 `tioka.ontology.schema.enabled=true` 时装配 `SchemaApi`（`@Bean` 返回 `SchemaRegistry`），故 `ctx.getBean(SchemaApi.class)` 实际类型即 `SchemaRegistry`，直接 cast 后 `publish("lease.finding", 1, findingSchema(), "lease")`。顺序不可颠倒：`OutputContractPublishCheck` 在 `publishSpec` 期要求 `schemaRef` 已 `PUBLISHED`，未登记 → `REF_NOT_FOUND`，未开开关 → `REF_UNAVAILABLE`。
- **契约来自已发布 AgentVersion**——`GateSeed.seedPublishedAgent(ctx, llmBaseUrl, false, output)` 的四参重载把 `OutputContract` 落在 `AgentSpec` 的 `output` 槽（第 12 参）；运行期 `KernelLoopConfiguration` 组装的 `OutputStage` 经 `AgentVersionId -> snapshots.get(id).spec().output()` 取契约、经 `ObjectProvider<SchemaApi>` 取校验器，两条接线点均由本闸门真实闭合。
- **校验走真实 `SchemaRegistry`**——`tioka.ontology.schema.enabled=true` 使 `OutputStage` 拿到非 null `schemas`，从而进入 `onLoopExit` 的结构化分支（`contract==null || schemas==null` 直通）。
- **契约末步须终局**——`contract(maxRepair)` 的 `fallback = [REPAIR, FAIL]`，末步 `FAIL.terminal()` 满足 `OutputContractPublishCheck.checkFallbackTerminal`（否则 `FALLBACK_TERMINAL_REQUIRED`）。

## 步骤 1 三段断言

| 段 | 桩剧本 | 断言 |
| --- | --- | --- |
| 1. 生产类比语料（失败率） | `contract(0)`（`maxRepair=0`）+ `loopScript` 8 个合法载荷（按模循环，与 Turn 顺序解耦） | 8 个 Turn 每个断言 `schemaRef==lease.finding@1` / `outputMode=="FINAL"` / `modelRef!=null` / `repairRounds==0`；`failures==0`（0/8 = 0% ≤ 0.5%）；`VALID` 载荷过双校验器 |
| 2. 变异集（零逃逸） | `contract(0)` + `script`（`loop=false`）9 条变异（索引 0 为合法对照） | 每输入恰 1 个 `data` item；`outcome ∈ 四结局`；顺序锚点 `assertEquals(i==0?"VALID":"FAILED", outcome)`；`VALID` → 交付载荷过校验器，其余 → `payload==null` 且 `reasonCode!=null`；`observed` 同时含 `VALID` 与 `FAILED` |
| 3. 修复闭环 | `contract(2)` + `script(["{\"findingId\":\"F-1\"}", VALID_PAYLOAD])` | `outcome=="VALID"` 且 `repairRounds >= 1`（首轮非法 → 修复轮合法） |

**「零逃逸」的三条硬约束**（`runTurn`）：

1. `TurnStatus.COMPLETED`——结构化失败也须以 `COMPLETED + error_code=output_invalid` 收口（`LoopResult.completedWithReason`），**不得** `FAILED`（异常逃逸）；
2. `TurnView.errorCode()` 非空时只允许 `output_invalid`；
3. 每次输入**恰好** 1 个 `type='data'` 的 `runtime_item`。

**断言取数口径**：走 `JdbcTemplate` 直查 `SELECT schema_ref, outcome, reason_code, model_ref, output_mode, repair_rounds, content_json FROM runtime_item WHERE turn_id=? AND type='data' ORDER BY seq`——**不能**走 `ItemRepository.listByTurn`：其 `RuntimeItemMapper.listByTurn` 的 SELECT 未投影 `schema_ref`/`outcome`/… 列（见 C-12 修复记录），断言字段会恒 null。

**失败率统计口径**：按 `turn_id` 局部统计（`RuntimeItemStatsMapper` 全库聚合无空间隔离，会被历史数据污染）；真实值以 `[M3-C29 实测]` 打印，最终率值待 CI 回填。

**独立第二校验器**：`structuralViolations`/`walk`/`typeMatches` 手写 JSON 结构复核（覆盖 `type`/`required`/`properties`/`additionalProperties=false`/`items`/`enum`），**刻意不复用 networknt**，与权威校验器 `SchemaApi.validate` 互为佐证（§10.3 口径，逐字照搬 `OutputStageIT`）。

## 步骤 2：未连 `failed` 端口的 Flow 发布被拒（ADR-043 §3.7）

`DefaultFlowDslValidator` 是 ADR-043 结局端口规则的唯一实现点；`checkOutcomePorts`：节点 `declaresOutputContract`（`config.schema` 非空白或 `config.output.schemaRef` 非空白）且无 `fromPort=="failed"` 出边且 `config.onOutcomeFailure != "fail_fast"` → `err("OUTCOME_FAILED_PORT_MISSING", …)`。本步骤直接 `new DefaultFlowDslValidator()` + `FlowPublishContext.permissive()` 做规则级断言（三组）：

| 组 | 夹具 | 期望 |
| --- | --- | --- |
| 正例 | `baseFlow + node("se","transform",{schema:"order@1"}) + wireThrough` | `hasErrors()` 且 `hasCode("OUTCOME_FAILED_PORT_MISSING")`，且该码为 **ERROR 级**（发布拒绝而非告警） |
| 反例 1 | 同上但 edges 加 `se.failed → out.in` | `!hasCode("OUTCOME_FAILED_PORT_MISSING")` |
| 反例 2 | 同上但 `config{schema, onOutcomeFailure:"fail_fast"}` | `!hasCode("OUTCOME_FAILED_PORT_MISSING")` |

## 偏差清单

1. **DSL 校验器未接入 `FlowService.publish` 发布路径（关键口径说明）**：`DefaultFlowDslValidator` 无 `@Component`、容器内无 `FlowDslValidator` Bean，生产发布路径当前只剩 `FlowGraphValidator`（不含 `OUTCOME_FAILED_PORT_MISSING`）。故步骤 2 是**规则级**断言而非端到端发布拒绝，javadoc 明写「尚未接入发布路径」并**不制造「已拦截」的假象**；接线缺口登记为遗留（下节）。与 `G3FlowPublishGateIT`（FLW-06/DAT-04，仍 `@Disabled`）分工，不越界实现其范围。
2. **「30 份评测合同」语料在仓内不存在**：ADR-066 §10 的「30 份评测合同」为规划口径，仓内无语料文件。本闸门以「重复合法载荷的生产类比语料」承载失败率分母（8 Turn），**最终率值待 CI 回填**。
3. **`lease.*` 真实 Schema 由 pack 加载，闸门内改用内联最小版**：`tioka-pack-lease/schemas/lease.finding@1.json` 经领域包加载，闸门内嵌最小版 `findingSchema()`（`findingId`/`conclusion`，`x-tioka-evidence=required`）以便手写第二校验器复算，结构与命名对齐真实 Schema。
4. **`TRUNCATED / REFUSED / CONTENT_FILTERED` 在桩端不可造**：`LlmStubServer` 对非 `tool_calls` 一律 `finish_reason="stop"`、`tool_calls` 一律 `"tool_calls"`，且 `Call` 无 `refused` 字段——三条原因码在闸门端到端路径**不可造**，由 `OutputStageIT` 直构 `ModelPort.Result` 覆盖（§10.3）；闸门侧只覆盖桩可达的变异集（缺必填/类型错/枚举外/额外字段/非 JSON/多段 JSON/语法错/空输出）。
5. **本机 Docker 不可用**：闸门端到端绿/红判定归 CI（`-Pgate`），本地只做静态编译与离线断言。

## 测试清单

| 测试 | 用例 | 断言 |
| --- | --- | --- |
| `G3StructuredOutputGateIT`（server，`@Tag("gate")`） | `contractSchemaFinalFailureRateAndMutationEscape` | 三段：失败率 0/8 ≤ 0.5% + 每 Turn 契约列完整；变异集零逃逸（每输入 1 个 data item + 四结局 + Turn 不 FAILED + FAILED 无 payload 有原因码）；修复闭环（`VALID` + `repairRounds≥1`） |
| | `flowPublishRejectedWhenFailedPortMissing` | 正例 `OUTCOME_FAILED_PORT_MISSING`（ERROR 级）+ 两个反例（连 `failed` 端口 / `fail_fast` 旁路）不报该码 |

## 实测结论

- 编译：`$env:JAVA_HOME='C:\Program Files\Java\jdk-17'; mvn -o -am -q -pl tioka-server test-compile -DskipTests` **exit 0**（`SchemaRegistry`/`DefaultFlowDslValidator` 经静态 import 成立——**`tioka-server` 的 `-biz` 依赖虽是 `runtime` scope，但 runtime 依赖进入 test classpath**，无需反射；`G3KnowledgeRecallGateIT`/`GateSeed` 亦直接 import biz 类，佐证同口径）。
- 首跑编译错误 1 处并修复：变异集循环里 `() -> "变异 " + i + …` 引用的 `i` 非 effectively final → 捕获 `int idx = i`。
- **G3 进度**：20 用例中 **8 真实现**（InfraSmoke / Delegation / DataPermission / Charter / UndoAutonomy / KnowledgeRecall / Timeline / StructuredOutput），本批 **7/20 → 8/20**；其余 12 骨架仍带精确阻塞理由。
- 端到端闸门判定（Testcontainers/真实 Turn）归 CI。

## 环境阻塞

- 本机 Docker 不可用（`npipe:////./pipe/dockerDesktopLinuxEngine` 连接失败），Testcontainers 相关 IT 无法本地执行，故 `G3StructuredOutputGateIT` 只完成静态编译与离线断言，**端到端判定归 CI**——不得以静态验证替代闸门判定（同 C-24〜C-28）。

## CI 判定

待合入后回填。

## 遗留（归后续批次）

- **接线缺口**：把 `DefaultFlowDslValidator` 接入 `FlowService.publish`（或以 `@Component` 装配为 `FlowDslValidator` Bean 并在发布流程调用），使 `OUTCOME_FAILED_PORT_MISSING` 成为真正的发布拒绝路径——与 FLW-06（`G3FlowPublishGateIT`）一并处置。Flow 域按 ADR-043（2026-11-10）降级、FLW-06 挂起，本批不越界。
- **评测语料**：「30 份评测合同」语料与真实率值（分母 > 8）待补，最终失败率回填。
- **真实 Schema 端到端**：以 pack 加载的真实 `lease.finding@1` 替代内联最小版做闸门验收（需领域包加载链路就绪）。

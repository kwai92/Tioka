# M3 批次 C-6：SCH-02 结构化输出接线最小闭环 + SCH-03 校验、证据绑定、修复循环、data item

- 分支：`feature/m3-c6-sch03`（worktree `/Users/songkaifang/Documents/Documents/Mime/tioka-wt/p3-c6-sch03`，基于 main = ffcef78）
- 日期：2026-11-10
- 权威输入：M3-03 §5（L312-356，L314 修订注记「修复循环以 ADR-066 第 6、7 节为准」）、ADR-066 §5/§6、M3-00 L204（SCH-03 验收「伪造引文进入修复」）

## 实现映射

| 模块 | 文件 | 内容 |
| --- | --- | --- |
| runtime-api | `kernel/ItemType.java` | 加 `DATA("data")` |
| runtime-api | `schema/runtime-event-v1.json` | itemType examples 加 `"data"`（v1 内扩，见偏差 1） |
| ontology-biz | `application/schema/SchemaRegistry.java` | `checkEvidence` stub → 真实现：schema/payload 同步遍历 `x-tioka-evidence` 标注对象，对 `evidence`/`sources` 数组中引文（`unitId`+`quote`）逐个调 `EvidenceBinder.check`，Violation → `SchemaError(pointer=证据路径, code=violation code 名)`；`EvidenceBinder` 经 `ObjectProvider` 弱依赖（新构造器，旧构造器保留） |
| ontology-biz | `application/schema/OutputValidator.java` | x-tioka-path 最小实现：`xTiokaPathValues(schema, payload)` 提取标注字段取值（含数组内嵌套）；「路径存在」检查依赖 ONT-01 路径目录，未接线前跳过（偏差 6） |
| ontology-biz | `config/OntologyAutoConfiguration.java` | `schemaApi` Bean 注入 `ObjectProvider<EvidenceBinder>`；双开关独立（`tioka.ontology.schema.enabled` / `tioka.ontology.doc.enabled`），doc off 时 `checkEvidence` 返回空清单 + 日志「证据检查跳过」 |
| model-api | `constraint/StructuredDemand.java`（新） | Runtime 侧结构化需求：`schemaRef` + 权威 Schema + `emitAsTool`；方言编译留在 Model Hub |
| model-api | `gateway/ModelGateway.java` | `ChatRequest` 加第 5 组件 `structured`（4 参兼容构造器保留，既有调用方零改动） |
| model-biz | `gateway/DefaultModelGateway.java` | `invokeChat`：`structured != null` 时经 `StructuredRequestBuilder.build(profile, schema, false)` 编译方言 → responseFormat 进请求体 / systemPromptSuffix 前插 system 消息 / emit_output 工具追加进 tools；16 参构造（`@Autowired`）+ 15 参兼容构造（内建 builder，既有测试零改动） |
| model-biz | `infrastructure/provider/ProviderClient.java` | `streamComplete`/`httpComplete` 增加 `responseFormat` 参数（旧签名保留），HTTP body 写 `response_format`；mock 模式忽略 |
| runtime-biz | `application/loop/OutputStage.java`（新） | 输出阶段主体（见下） |
| runtime-biz | `kernel/domain/AgentLoop.java` | 10 参构造挂接 OutputStage（旧构造器保留）；「无 toolCalls → COMPLETED」之前与 max_steps 正常退出点调 `onLoopExit`；chat 调用携带 `structuredDemand`；TOOL 模式拦截 `emit_output`（先 `sink.toolCall` 再校验 → `sink.toolResult` 交回模型自修，仅 emit 时 `continue`） |
| runtime-biz | `kernel/domain/LoopPorts.java` | `ModelPort.chat` 增加 5 参 default 方法（携带 `StructuredDemand`，缺省降级 4 参） |
| runtime-biz | `kernel/domain/LoopResult.java` | 加 `completedWithReason(reason, ...)`——底层复用 `errorCode`（TurnRunner 已写 `runtime_turn.error_code`），不新增 TurnStatus |
| runtime-biz | `kernel/model/GatewayModelPort.java` | 覆写 5 参 chat → `ChatRequest` 5 参进 ModelGateway |
| runtime-biz | `kernel/config/KernelLoopConfiguration.java` | 装配 OutputStage：`OutputContract` 经 `ObjectProvider<AgentSnapshotApi>`（`snapshots.get(versionId).spec().output()`，与 TurnRunner 读 AgentSpec 同口径）；`SchemaApi` 经 `ObjectProvider`（schema.enabled=false → OutputStage 直通） |
| runtime-biz | `pom.xml` | 加 `tioka-module-ontology-api`（M3-03 §5 改动清单本就要求 OutputStage 调 SchemaApi）与 test 依赖 `tioka-module-ontology-biz`（IT 用真实 Registry + Binder 离线跑） |

## OutputStage 行为

- **FINAL**（M3-03 §5 伪代码 + ADR-066 §6）：解析最后模型输出为 JsonNode（解析失败 = `NO_JSON` 校验错误）→ `SchemaApi.validate`（networknt 2020-12 全部错误不首错停止）+ `requireEvidence=true` 时 `checkEvidence(visibleDocIds)` → 空则写 `data` item(valid=true) 按原路径 COMPLETED；错误集合与上一轮完全相同 → 早停；`round==maxRepair`（缺省 2）或修复 token 累计 ≥ `maxRepairTokens`（首次输出 completionTokens × 2，先到为准）→ 写 data item(valid=false) + `COMPLETED(errorCode="output_invalid")`；否则错误清单以 user MESSAGE 追加（只给错误不给答案），再调模型（同 StructuredDemand = 同方言同生成方式），token 累计进 Turn。
- **TOOL**：`emit_output` 不注册进本地 tools（由 Model Hub 按 `emitAsTool=true` 的 toolRequest 形态追加，避免重复定义）；AgentLoop 拦截 emit_output 调用，`handleEmit` 做同样的 validate + evidence 校验，失败以 tool_result（含错误清单 JSON）交回模型自修，成功记录到 ledger；Turn 正常退出点汇总——有有效提交 → data item(valid=true, payload=最后有效输出)；全无效 → data item(valid=false) + output_invalid。emit_output 校验不进审批流（平台内部结构化提交工具，无副作用）。
- **直通**：AgentSpec 无 `output` 段 / schema.enabled=false / schemaRef 未登记 → `onLoopExit` 返回 null，AgentLoop 走原路径，行为与现状完全一致。
- **data item contentJson**：`{type:"data", schemaRef, valid, outcome:"VALID"|"FAILED", errors:[{pointer,code,message}], payload, repairRounds}`——payload 字段名按 ADR-037「data item」节（任务描述写 `output`，按 ADR 口径落 `payload`）；`valid`/`outcome` 双字段保证 VALID/FAILED 结局可判。
- **visibleDocIds 最小来源**：扫本 Turn TOOL_CALL items 中 tool 名为 `doc.unit` 的调用，从 argumentsJson 的 unitId（`{docId}:{pageNo}:{seq}`，与 DocToolProvider 解析约定一致）提取 docId 集合。

## SCH-02 接线说明（ModelPort → ModelGateway）

`LoopPorts.ModelPort.chat` 5 参 default（StructuredDemand）→ `GatewayModelPort` 覆写 → `ModelGateway.ChatRequest.structured` → `DefaultModelGateway.invokeChat` 在**绑定解析后**按实际路由到的模型能力经 `StructuredRequestBuilder` 编译方言并进请求体（response_format / system 消息 / emit_output 工具）。故障接力时新候选按其能力重编译（本批能力记录未接线，见偏差 4）；修复轮复用同一 `StructuredDemand` 对象，同一绑定下方言确定性一致（缓存命中随 SCH-06 落地）。

## 偏差清单

1. **事件契约 v1 不升版**：M3-03 L328 写「Schema 版本 +1」，实际按 v1 内扩处理——`itemType.examples` 加 `"data"` 只放宽示例，不破坏校验；`RuntimeEventSchemaContractTest.itemTypeExamplesMatchJava` 自然锁定一致性。
2. **双开关降级**：`SchemaRegistry.checkEvidence` 经 `ObjectProvider<EvidenceBinder>` 弱依赖；`tioka.ontology.doc.enabled=false` 时返回空清单并在日志标明「证据检查跳过」（返回结构不复用 SchemaError 表达「跳过」，避免污染错误清单）。既有装配组合与 `SchemaRegistry(ObjectMapper)` 单参构造器均保留，不破坏既有测试。
3. **visibleDocIds 最小来源**：本 Turn `doc.unit` 调用参数聚合（见上）；完整方案（space 级 ACL 预授权、跨 Turn 记忆）归 SCH-06+。
4. **能力记录未接线**：`DefaultModelGateway` 以 `StructuredOutputCapability.legacy("schema"/"tool")` 缺省能力编译方言（STRICT_SCHEMA → responseFormat；TOOL → emit_output toolRequest）；per-model 能力档案（`modes/keywords/limits`）随 SCH-06 接线。
5. **修复错误清单载体**：M3-03 L342 写「追加 tool_result」，实际 FINAL 模式（schemaRequest，无工具调用）以 user MESSAGE 承载错误清单——tool_result 需配套 assistant.tool_calls 消息，schema 模式无此前置；TOOL 模式按原文走 tool_result。
6. **x-tioka-path**：ADR-037 语义是「校验时检查路径存在（ONT-01 路径目录）」；本批按任务口径补最小实现（提取标注字段取值，`OutputValidator.xTiokaPathValues`），存在性检查跳过随 ONT-01 接线。
7. **data item 字段名**：任务描述写 `{valid, errors, output}`，按 ADR-037「data item」节落 `payload`（另加 `outcome`/`schemaRef`/`repairRounds`）。
8. **max_steps 正常退出点**：TOOL 模式汇总挂接于「无 toolCalls」与「max_steps_reached」两个正常退出点；FINAL 修复循环挂接于「无 toolCalls」点（M3-03 指定插入点）；forceFinalAnswer / repeat_call_stopped 后若以无 toolCalls 文本收尾同样进入输出阶段。
9. **SCH-06/07/09 边界**（本批不做）：方言编译缓存、严格档发布检查、per-model 能力记录（SCH-06）；完成状态识别（拒答/截断/空内容）、提取规整、四种结局全量、DataItemReader 与 ArchUnit 规则、异常逃逸变异（SCH-07）；降级阶梯 SWITCH_MODE/SWITCH_MODEL/HUMAN、兜底值、人工补录（SCH-09）。本批 `NO_JSON`/`SYNTAX` 合并为 `NO_JSON` 单码。
10. **前端**：DataItem.vue / dataItemRegistry.ts 骨架已在（SCH-04 范围），本批不动。
11. **SchemaRegistry InMemory 持久化接线**：沿用 C-3 登记口径，随 SCH 持久化批次（V171 落地时）接线。
12. **emit_output 审批**：emit_output 拦截在审批分类之前，不进 approval 流（内部工具无副作用）；其余工具路径不变。

## 测试清单

| 测试 | 用例 | 断言 |
| --- | --- | --- |
| `OutputStageIT`（runtime-biz，离线：InMemory registry + 桩 ModelPort） | `repair` | 首次漏 required → 1 修复轮 → valid=true，repairRounds=1；修复轮带同 StructuredDemand；错误清单消息不含正确答案 |
| | `exhausted` | 三轮各不同错误 → valid=false + outcome=FAILED + COMPLETED(errorCode=output_invalid)，maxRepair=2 用尽 |
| | `earlyStopOnIdenticalErrors` | 错误集合与上轮相同 → 1 轮即停 |
| | `repairTokenBudgetStopsBeforeExhaustion` | 首次 10 token → 预算 20，1 轮 20 token 后不再发起第二轮 |
| | `fakeQuote` | 引文改一字 → 错误码 QUOTE_MISMATCH 进入修复消息 → 修复后 valid=true（伪造引文进入修复而非直接失败） |
| | `toolModeRepairRoundTrip` | emit_output 缺字段 → tool_result 错误清单交回模型 → 第二次合法 → Turn 结束汇总 1 个 data item(valid=true, payload=有效输出) |
| | `toolModeAllInvalid` | 全部 emit_output 无效 → data item(valid=false) + output_invalid |
| | `passthroughWithoutContract` | 无 output 契约 → onLoopExit/structuredDemand/toolMode 均 null（现状不变） |
| | `visibleDocIdsFromDocUnitCalls` | 本 Turn doc.unit 调用 → docA/docB 提取 |
| `SchemaRegistryCheckEvidenceTest`（ontology-biz） | 6 例 | 合法引文过 / 改一字 QUOTE_MISMATCH（pointer=/evidence/0）/ UNIT_NOT_VISIBLE / UNIT_NOT_FOUND / doc off 跳过（空清单）/ SCHEMA_NOT_FOUND |
| `OutputValidatorPathTest`（ontology-biz） | 3 例 | x-tioka-path 顶层与数组内取值 / 缺字段跳过 / 无标注为空 |
| `RuntimeEventSchemaContractTest`（runtime-api） | 既有 | ItemType.DATA 与 examples 一致，自然通过 |

## 实测结论

- `OutputStageIT` 9/9 绿；`SchemaRegistryCheckEvidenceTest` 6/6、`OutputValidatorPathTest` 3/3 绿。
- 回归：runtime-biz / ontology-biz / model-biz / agent-biz 全量 + tioka-server 架构八套（PersistenceArchTest / TiokaArchitectureTest / M2ArchitectureTest / M3ArchitectureTest / ModuleDependencyArchTest / RuntimeApiContractTest / M2B4VaultContractTest / M3C1aMigrationContractTest）——数字见提交信息与 M3-00 回写。

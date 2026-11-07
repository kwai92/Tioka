# ADR-037 — 结构化输出与 data item

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：SCH-01〜SCH-05（见 34 号计划「业务本体与合同场景」）
- 对应设计：无（2026-11-05 合同场景暴露的平台缺口第 4 项）
- 阅读版：[商业租赁合同本体与风险审查 Agent 设计](https://claude.ai/artifact/PxdZR5xfAkyauCJ7AUQfAd)
- 施工设计：[M3-03-业务本体与文档层](../construction/M3-03-业务本体与文档层.md) 第 SCH 节
- 相关：[ADR-016](ADR-016-runtime-kernel.md) Runtime、[ADR-023](ADR-023-agent-skill-assets.md) AgentSpec、[ADR-028](ADR-028-hci-frontend.md) item 类型、[ADR-031](ADR-031-agent-eval-experience.md) 评测、[ADR-036](ADR-036-business-ontology-domain-pack.md) 业务本体、[ADR-038](ADR-038-multi-agent-team.md) 黑板；ADR-029 第 54 条
- 约束：与 ADR-029 定案冲突时以定案为准；修改本 ADR 走 ADR-030 的变更流程。

## 背景

现在 Agent 的产出只有 message（自由文本）和 card。合同场景每一步都要结构化结果：抽取步骤输出对象属性，审查子 Agent 输出 Finding，协调者把 Finding 汇总后提交业务系统。没有平台级的结构化输出时，每个场景都会在 Prompt 里要求「请输出 JSON」，再各自解析、各自容错，校验口径不一，评测也没法按字段比对。ADR-038 的黑板（`team.publish`）也需要一个按类型校验产物的地方。

## 决定

1. 平台新增 **Schema 登记表**。Schema 是 JSON Schema 2020-12 的子集，加少量 `x-tioka-*` 扩展，按 `schemaKey@version` 引用，发布后不可改。
2. AgentSpec 增加 `output`（输出契约）。声明了输出契约的 Agent，每个 Turn 的最终产出是一个 **`data` item**，平台负责约束生成、校验、修复和证据检查。
3. ADR-028 的 item 类型增加 `data`（第 54 条）。前端按 `schemaKey` 找渲染器，找不到就用通用的 JSON 树。
4. ADR-038 的 `team.publish`、业务系统的提交工具（如 `biz.lease.submitReviewDraft`）都引用同一个登记表里的 Schema，校验代码只有一份。
5. ADR-031 的评测增加**字段级比对**，按 Schema 声明的比对字段算准确率与召回率。

## Schema 登记表

```java
public record SchemaDef(
    String schemaKey,                 // lease.finding
    int version,
    JsonNode jsonSchema,              // 2020-12 子集：type、properties、required、enum、items、$ref（只能引用登记表内）、oneOf、pattern、minimum/maximum
    String ownerPack,                 // 由哪个领域包发布；平台内置的为 platform
    DataLabel defaultLabel,           // ADR-040；缺省 INTERNAL
    String contentHash) {}
```

`x-tioka-*` 扩展只有四个：

| 扩展 | 位置 | 作用 |
| --- | --- | --- |
| `x-tioka-evidence` | 对象 | 该对象的 `sources` / `evidence` 字段是否必填；必填时平台做证据绑定检查 |
| `x-tioka-compare` | 根 | 黑板冲突判定与评测比对用的字段，如 Finding 的 `["checkpointId"]` 为键、`["conclusion"]` 为值 |
| `x-tioka-identity` | 数组 | 数组元素的身份字段，用于差异计算和评测时对齐元素 |
| `x-tioka-path` | 字符串字段 | 该字段的值是 ADR-036 的模型路径，校验时检查路径存在 |

来源有三处：平台内置（如 `platform.citation`）、领域包随包发布（如 `lease.finding`）、ADR-036 由模型定义派生（如 `lease.contract.feeItem`）。空间管理员不能在界面上随手建 Schema，只能随领域包或 Agent 资产发布，避免出现大量互不兼容的一次性 Schema。

## AgentSpec 的输出契约

```java
public record OutputContract(
    String schemaRef,                 // lease.finding@3；数组输出用 lease.findingList@3
    OutputMode mode,                  // FINAL：Turn 最终产出；TOOL：通过 emit_output 工具提交，可多次
    int maxRepair,                    // 校验失败后的修复轮数，缺省 2
    boolean requireEvidence) {}       // 覆盖 Schema 的 x-tioka-evidence，只能从 false 改成 true
```

AgentSpec 增加字段 `OutputContract output`（可空，空表示自由文本 Agent，行为不变）。发布校验（AST-02）增加一条：`schemaRef` 存在且已发布。

## 生成、校验与修复

每次需要结构化产出时，Runtime 按以下顺序处理：

1. **约束生成**。Model Hub 按路由到的模型能力选方式：支持 JSON Schema 约束的（百炼部分模型的 `response_format: json_schema`）直接传 Schema；只支持 JSON 模式的（DeepSeek 的 `json_object`）传 JSON 模式并把 Schema 放进系统提示；都不支持的走工具调用，把 Schema 作为 `emit_output` 工具的参数 Schema。模型能力记在 MH 的模型档案里（`structuredOutput: SCHEMA | JSON | TOOL`）。
2. **Schema 校验**。用 networknt json-schema-validator 校验，再检查 `x-tioka-path` 路径存在。
3. **证据绑定检查**。对需要证据的对象，逐个检查 `unitId` 存在且属于本次运行可见的文档；`quote` 归一化（去空白、全半角、标点统一）后必须是该单元 `norm_text` 的子串。
4. **修复**。任一步失败时，把错误清单（JSON Pointer + 原因）作为一条 tool\_result 交回模型重新生成，最多 `maxRepair` 轮。修复轮的 token 计入同一个 Turn。
5. **结果**。通过时写 `data` item，`valid = true`；修复用尽仍失败时写 `data` item，`valid = false` 并附错误清单，Turn 仍以 `COMPLETED(reason=output_invalid)` 结束（沿用 ADR-016 带 reason 的写法，不新增状态）。调用方（Flow 节点、协调者、编排派工器）自己决定重试还是转人工，平台不擅自丢弃。

## data item

```json
{
  "type": "data",
  "schemaRef": "lease.finding@3",
  "valid": true,
  "errors": [],
  "label": "CONFIDENTIAL",
  "payload": { "...": "..." },
  "evidence": [{ "unitId": "9f3a…:12:4", "quote": "免租期为三个月" }],
  "repairRounds": 1
}
```

- 事件族不变，仍是 `item.started` / `item.completed`；`data` item 不做增量流式，只在完成时发一次完整负载。
- 数据标签取 Schema 的 `defaultLabel` 与本 Turn 读入数据的最高标签中较高者（ADR-040 的传播规则）。
- 前端渲染器注册表：`schemaKey` → Vue 组件。合同场景注册 `lease.finding`（结论卡）和 `lease.contract.*`（模型树）；未注册的用通用 JSON 树，证据字段一律渲染成可点击的原文定位。

## 与黑板、业务提交、评测的关系

- **黑板**：`team.publish(type, subjectKey, payload, evidence)` 中的 `type` 就是 `schemaKey@version`，校验走上面的第 2、3 步，不做第 1、4 步（发布方自己保证）。冲突判定读 `x-tioka-compare`。
- **业务提交**：业务连接器工具的入参 Schema 引用登记表（`$ref: lease.findingList@3`），ToolGateway 在 `prepare` 阶段校验，不合格的调用不出 Tioka。
- **评测**：EVO-02 的批次评测增加 `FieldMatchEvaluator`：按 `x-tioka-identity` 对齐数组元素，按 `x-tioka-compare` 的值字段算一致率，再分字段出准确率、召回率。合同场景的四态一致率、「不满足」召回率都由它算出。

## 不做的事

- 不支持 JSON Schema 的全部特性（`if/then/else`、`dependentSchemas`、远程 `$ref`）。需要条件约束时由领域包校验钩子处理。
- 不做 Schema 的自动迁移。新版本 Schema 下的旧 `data` item 保持原版本号，读取方按版本处理。

## 工作项

| ID | 工作项 | 里程碑 | 优先级 | 人日 | 依赖 | 验收 |
| --- | --- | --- | --- | --- | --- | --- |
| SCH-01 | Schema 登记表、`x-tioka-*` 扩展、发布与引用校验 | M3 | P0 | 3 | AST-01 | 远程 `$ref` 发布被拒 |
| SCH-02 | AgentSpec `output`、Model Hub 三种约束生成方式、模型档案 `structuredOutput` | M3 | P0 | 4 | MH-01、SCH-01 | 三种方式在同一 Schema 上产出都能通过校验 |
| SCH-03 | 校验、证据绑定检查、修复循环、`data` item 与事件契约 | M3 | P0 | 4 | RT-08、SCH-01、DOC-03 | 伪造引文在第 3 步被拒并进入修复 |
| SCH-04 | 前端 `data` 渲染器注册表与通用 JSON 树 | M3 | P1 | 3 | SCH-03 | 未注册 Schema 正常显示，不报错 |
| SCH-05 | `FieldMatchEvaluator` 字段级评测 | M4 | P1 | 3 | EVO-02、SCH-01 | 合同样本集出四态一致率与分字段报告 |

## 修订：结构化输出可靠性（2026-11-07）

见 [ADR-063](ADR-063-guardrail-pipeline-input-safety.md)〜[ADR-065](ADR-065-output-safety-guardrail-eval.md) 与 ADR-029 第 72 条。负责人 2026-11-07 提出「如何保证模型的输出一定能满足后续程序的需要，且不出现异常」，[ADR-064](ADR-064-structured-output-reliability.md) 在本 ADR 之上细化，冲突时以 ADR-064 为准：

- **保证的定义**：平台保证交付契约，每次结构化产出恰好以 `VALID`、`VALID_WITH_UNKNOWN`、`HUMAN_PENDING`、`FAILED` 之一交付，不交付部分对象，失败是值而不是异常；`data` item 增加 `outcome`、`unknownPaths`、`failure`、`producedBy`、`dialectHash`、`taint`。原 `COMPLETED(reason=output_invalid)` 保留，对应 `FAILED`。
- **两层 Schema**：登记表中的是权威 Schema；发给模型的是按模型能力编译的生成方言。校验永远用权威 Schema。用于模型产出的 Schema 须符合严格档（全部必填、封闭对象、不递归、规模上限、字段说明、理由在前、声明兜底）。
- **能力记录**取代 `structuredOutput` 三档（见 ADR-024 修订）。第 1 步「约束生成」按 ADR-064 第 4.1 节的五种方式依次选择；百炼 `json_schema` 仅 Qwen3.7 / 3.8 系列可用，DeepSeek 官方无 `json_schema`，其严格工具调用为 Beta。
- 第 2〜4 步扩展为八步流水线（完成状态、提取、严格解析、白名单规整、Schema 校验、语义规则 `x-tioka-rules`、证据与引用 `x-tioka-ref`、领域钩子）；修复按原因分类处理，截断与拒答不进入格式修复，错误相同时早停；修复用尽后按 `OutputContract.fallback` 降级（换方式、换模型、分解、兜底值、人工补录、失败）。
- `x-tioka-*` 扩展由四个增加到十个：`x-tioka-rules`、`x-tioka-ref`、`x-tioka-fallback`、`x-tioka-coerce`、`x-tioka-reasoning`、`x-tioka-group`（另有 `x-tioka-enum-doc`、`x-tioka-open-enum`、`x-tioka-tier` 为说明与分级标注）。
- 「不做 Schema 的自动迁移」保持：存储中的旧数据不改写；增加兼容组、发布时兼容性判定、消费方版本声明与读时升级（ADR-064 第 9 节，M4）。
- 「需要条件约束时由领域包校验钩子处理」改为：跨字段条件优先写成 `x-tioka-rules`（CEL），需要外部数据的才写钩子。
- SCH-02 验收补充：能力不支持的关键字被编译掉后，平台校验仍拦住违反该约束的值。SCH-03 验收补充：截断不进入格式修复；变异测试零异常逃逸。

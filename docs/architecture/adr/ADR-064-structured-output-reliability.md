# ADR-064 — 结构化输出可靠性：生成方言、交付契约、降级兜底与 Schema 演进

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-07
- 覆盖工作项：SCH-06〜SCH-12、FE-41（新增）；SCH-01〜SCH-03 的验收补充
- 来由：[调研 06](../research/06-安全护栏与结构化输出可靠性调研.md) 选项 T2、U2、V2；负责人 2026-11-07 提出「如何保证模型的输出一定能满足后续程序的需要，且不出现异常」；ADR-029 第 72 条
- 相关：[ADR-016](ADR-016-runtime-kernel.md)、[ADR-024](ADR-024-model-hub-routing.md)、[ADR-028](ADR-028-hci-frontend.md)、[ADR-031](ADR-031-agent-eval-experience.md)、[ADR-036](ADR-036-business-ontology-domain-pack.md)、[ADR-037](ADR-037-structured-outputs.md)、[ADR-038](ADR-038-multi-agent-team.md)、[ADR-041](ADR-041-config-versioning-observability.md)、[ADR-043](ADR-043-flow-orchestration-detail.md)、[ADR-046](ADR-046-execution-strategy.md)、[ADR-063](ADR-063-guardrail-pipeline-input-safety.md)
- 约束：本 ADR 在 ADR-037 之上细化，不改变 Schema 登记表、`OutputContract`、`data` item 的基本形态；两者冲突时以本 ADR 为准（ADR-037 已加修订节）。与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。
- 写法说明：只用文字和表格，不附代码。

## 背景

ADR-037 定了结构化输出的骨架：Schema 登记表、三种约束生成方式、校验、证据绑定、最多 2 轮修复、`data` item 可为 `valid=false`。调研 06 对照各供应商与研究结果，发现这套骨架还不足以回答「下游程序能否放心使用」：

- 供应商的「严格模式」各有例外：拒答、长度截断、空内容、多模态降级、枚举大小写；受限解码引擎本身也有「约束不足」。
- 国内供应商能力差异大且变化快：百炼只有 Qwen3.7 / 3.8 系列支持 `json_schema` 严格模式；DeepSeek 只有 `json_object` 与 Beta 严格工具调用；火山方舟严格模式仍是 Beta。可移植的关键字子集很窄。
- 修复用尽后只有 `valid=false`，调用方怎么处理没有约束，「不出现异常」无从保证。
- Schema 演进只有「发布后不可改、不自动迁移」，没有兼容性判定。
- 字段顺序会影响推理质量（结论在理由之前时推理被跳过）。

## 决定

1. **保证的定义**：平台不保证模型一次就输出正确，平台保证**交付契约**（第 1 节）——每一次结构化产出都恰好以四种结局之一交付，下游永远不接触模型原始文本，失败是一个值而不是异常。
2. **Schema 两层**（第 2 节）：登记表保存权威 Schema（完整约束 + 语义规则）；发给模型的是按模型能力编译出的**生成方言**。校验永远用权威 Schema。
3. **Schema 严格档**（第 3 节）：平台与领域包发布的、用于模型产出的 Schema 必须符合可移植严格子集，发布时检查。
4. **能力档案细化**（第 4 节）：`structuredOutput` 由三档枚举改为能力记录，并由一致性测试测定。
5. **输出处理流水线八步**（第 5 节）：完成状态 → 提取 → 严格解析 → 白名单规整 → Schema 校验 → 语义规则 → 证据与引用 → 领域钩子。
6. **修复与降级阶梯**（第 6、7 节）：修复按错误类别区别对待；修复用尽后按契约声明的阶梯降级；兜底值只能是 Schema 声明的「未知 / 待复核」状态。
7. **消费方必须处理每种结局**（第 8 节）：Flow 节点、协调者、编排派工器、业务提交工具都有强制的结局处理，发布校验检查。
8. **Schema 演进**（第 9 节）：兼容组、发布时自动判定兼容性、消费方声明可接受的版本范围、读时升级。
9. **可观测与测试**（第 10 节）：分 Schema × 模型的指标；模型接入与夜间的一致性测试；「异常逃逸为零」的变异测试；闸门阈值。

## 1. 交付契约

### 1.1 四种结局

| 结局 | 含义 | `data` item | 下游可做的事 |
| --- | --- | --- | --- |
| `VALID` | 权威 Schema 与全部语义规则通过 | `valid=true` | 按 Schema 类型直接使用 |
| `VALID_WITH_UNKNOWN` | 通过校验，但部分字段取了 Schema 声明的兜底值（第 7.3 节），或领域钩子给出 `WARN` | `valid=true`，`unknownPaths` 非空 | 使用；兜底字段按「未知」处理，通常进入人工复核 |
| `HUMAN_PENDING` | 自动链路失败，已生成人工补录任务，等待人补全 | `valid=false`，`humanTaskId` 非空 | 挂起等待（ADR-016 的 `WAITING`），补录后得到 `VALID` |
| `FAILED` | 自动链路失败，契约声明不转人工 | `valid=false`，`failure` 含原因码 | 走调用方声明的失败处理（第 8 节） |

- 任何结局下，`payload` 要么是通过权威 Schema 的完整对象，要么为空。**不交付部分对象**：`FAILED` 与 `HUMAN_PENDING` 时，最后一次候选输出作为 `lastCandidate` 存档供排查与人工补录预填，标记为不可消费，任何消费接口都不返回它。
- `data` item 增加字段：`outcome`、`unknownPaths`、`failure`（原因码、阶段、错误清单）、`producedBy`（模型、生成方式、修复轮次、降级步骤）、`schemaRef`（精确到版本）、`dialectHash`（第 2 节）、`taint`（ADR-063）。

### 1.2 原因码

| 原因码 | 阶段 | 含义 |
| --- | --- | --- |
| `TRUNCATED` | 完成状态 | 达到输出长度上限 |
| `REFUSED` | 完成状态 | 模型拒答（供应商拒答字段或停止原因） |
| `EMPTY` | 完成状态 | 内容为空或只有空白 |
| `CONTENT_FILTERED` | 完成状态 | 供应商内容过滤 |
| `NO_JSON` / `MULTIPLE_JSON` | 提取 | 找不到或有多个顶层 JSON 值 |
| `SYNTAX` | 解析 | JSON 语法错误、重复键、超深嵌套 |
| `SCHEMA` | Schema 校验 | 附 JSON Pointer 与关键字 |
| `RULE` | 语义规则 | 附规则 ID 与路径 |
| `EVIDENCE` / `QUOTE_MISMATCH` / `PATH_NOT_FOUND` / `REF_NOT_FOUND` | 证据与引用 | 沿用 ADR-037 并补充 |
| `UNKNOWN_PLACEHOLDER` | 证据与引用 | 假名化占位符无法还原（ADR-063 第 6.3 节） |
| `HOOK` | 领域钩子 | 领域包校验失败 |
| `BUDGET` | 修复 / 降级 | Turn 预算或修复 token 上限用尽 |
| `NO_ELIGIBLE_MODEL` | 降级 | 阶梯中没有满足标签、能力与配额的模型 |

### 1.3 「不出现异常」的工程约束

- 输出流水线内任何步骤抛出的异常（包括校验器自身的缺陷、规则表达式求值错误）都被流水线捕获，转为 `FAILED(reason=INTERNAL)` 并告警，不向 Turn 循环与调用方传播。
- 消费方只能经 `DataItemReader`（按 `schemaRef` 返回类型化对象）读取 `VALID` / `VALID_WITH_UNKNOWN` 的 payload；ArchUnit 规则禁止在 runtime 与 ontology 模块之外直接解析 `data` item 的 `content_json`。
- 领域包可从 Schema 生成类型化的值对象供 Java 代码使用；生成物随 Schema 版本发布，不手写。

## 2. 权威 Schema 与生成方言

| 层 | 内容 | 用途 |
| --- | --- | --- |
| 权威 Schema | ADR-037 的 2020-12 子集 + `x-tioka-*` 扩展 + 本 ADR 的 `x-tioka-rules`、`x-tioka-fallback`、`x-tioka-coerce` | 平台校验、评测、消费方类型生成；唯一的事实来源 |
| 生成方言 | 由方言编译器按「模型能力记录」从权威 Schema 编译 | 发给模型（`response_format`、严格工具参数或提示中的 Schema 说明） |

方言编译器的规则：

| 权威 Schema 中有、目标能力不支持 | 编译处理 |
| --- | --- |
| 长度、数量、数值范围、`pattern`、`format` | 删除关键字，把约束改写成自然语言追加到该字段 `description`（如「长度 1〜200 字」） |
| 可选字段（不在 `required` 中） | 严格档 Schema 不允许出现（第 3 节）；非严格档时编译为 required + 可空 |
| `oneOf` | 能力只支持 `anyOf` 时编译为 `anyOf`；「恰好一个」由权威校验保证 |
| 本地 `$ref` / `$defs` | 能力不支持引用时内联展开；展开后超出能力上限（深度、属性数）则编译失败，该模型不可用于此 Schema |
| `x-tioka-*` | 全部删除；`x-tioka-rules` 中标了 `hint` 的规则改写进根 `description` |
| `const` | 能力不支持时编译为单值 `enum` |

- 编译结果按（权威 Schema 内容哈希、能力记录版本）缓存，记 `dialectHash`。同一（Schema、模型）在一个 Turn 内与跨 Turn 都得到相同的方言文本，保证 ADR-049 的提示缓存与供应商的文法编译缓存（如 Claude 的 24 小时文法缓存）都能命中。
- 编译失败（Schema 超出某模型能力）在**发布时**就能发现：Agent 发布校验对其路由计划中每个候选模型做一次编译，失败的模型从该 Agent 的结构化输出候选中排除并提示；全部候选都失败则发布被拒。

## 3. Schema 严格档

用于模型产出（`OutputContract.schemaRef`、`emit_output`、Flow 结构化抽取节点）的 Schema 必须符合严格档，发布时由 SCH-06 检查；只用于工具入参或黑板校验、不让模型直接产出的 Schema 不受此限。

| 规则 | 要求 | 理由 |
| --- | --- | --- |
| S1 全部必填 | 每个对象的所有属性都在 `required` 中；可缺省的值用可空类型表达 | DeepSeek 严格工具、Claude、OpenAI 的公共要求；字段永远存在，消费方不用判断缺失 |
| S2 封闭对象 | 每个对象 `additionalProperties: false` | 同上；防止多余字段被悄悄忽略 |
| S3 不递归 | 不允许自引用 | Claude 等不支持递归 |
| S4 规模上限 | 嵌套深度 ≤ 5；单个 Schema 属性总数 ≤ 100；单个枚举 ≤ 200 项；可空或联合类型的字段 ≤ 16 个 | 取主流供应商公开上限的较小者并留余量 |
| S5 字段说明 | 每个属性必须有 `description`，枚举每个值要有说明（`x-tioka-enum-doc`） | 生成方言把约束写进说明，模型才知道 |
| S6 理由在前 | 根对象或带结论的对象中，标了 `x-tioka-reasoning` 的字段（如「依据」「理由」）必须排在结论字段之前 | 字段按 Schema 顺序生成，结论在前会跳过推理 |
| S7 声明兜底 | 带结论语义的枚举字段，若契约允许兜底，必须在枚举中包含一个「未知 / 待复核」类取值，并以 `x-tioka-fallback` 指明 | 兜底只能取声明的状态，不能编造 |
| S8 不依赖供应商校验 | 可以使用长度、范围、`pattern`、`format`，但它们只在权威校验中生效 | 见第 2 节 |
| S9 枚举值规范 | 枚举值只用小写字母、数字、下划线或固定中文词，不依赖大小写区分 | Claude 等不保证枚举大小写；第 5 节规整时可安全地按大小写不敏感匹配 |

不符合严格档的 Schema 仍可发布为「非严格档」（`x-tioka-tier: relaxed`），但只能被声明了 `allowRelaxedSchema` 的 Agent 引用，且该 Agent 的结构化指标单独统计；平台内置与合同领域包的 Schema 全部为严格档。

## 4. 模型能力记录

ADR-037 的 `structuredOutput: SCHEMA | JSON | TOOL` 改为能力记录，放在模型档案（ADR-024 / ADR-042 的模型版本）中，属于版本化配置：

| 字段 | 含义 | 例 |
| --- | --- | --- |
| `modes` | 支持的生成方式集合：`STRICT_SCHEMA`（原生严格 Schema）、`STRICT_TOOL`（严格工具调用）、`JSON_OBJECT`、`TOOL`（非严格工具）、`PROMPT` | 百炼 Qwen3.8-Max：`STRICT_SCHEMA`、`JSON_OBJECT`、`TOOL`、`PROMPT` |
| `keywords` | `STRICT_SCHEMA` / `STRICT_TOOL` 下接受的关键字集合 | DeepSeek 严格工具：不含 `minLength`、`maxLength`、`minItems`、`maxItems` |
| `limits` | 深度、属性数、枚举数、联合类型数、严格工具数上限 | Claude：严格工具 ≤ 20、可选参数 ≤ 24、联合类型 ≤ 16 |
| `thinkingCompat` | 每种方式能否与思考模式同用：`YES` / `NO` / `UNVERIFIED` | 百炼多数模型的 `JSON_OBJECT` 仅非思考模式 |
| `multimodalDowngrade` | 有图片等多模态输入时 `STRICT_SCHEMA` 是否降级 | 百炼：降级为 `JSON_OBJECT` |
| `promptKeyword` | `JSON_OBJECT` 是否要求提示中出现「json」字样 | 百炼、DeepSeek：是 |
| `maxTokensAdvice` | 结构化模式下 `max_tokens` 的设置建议：`UNSET` / `ESTIMATE` | 百炼：`UNSET`；DeepSeek：`ESTIMATE` |
| `stability` | `GA` / `BETA` | 火山方舟 `STRICT_SCHEMA`：`BETA`；DeepSeek 严格工具：`BETA` |
| `refusalSignal`、`truncationSignal` | 如何从响应中识别拒答与截断 | 供应商适配器填写 |
| `verifiedAt`、`conformance` | 最近一次一致性测试的时间与结果（第 10.2 节） | — |

- **能力以一致性测试为准**：适配器可预填文档声明的能力，但 `STRICT_SCHEMA` / `STRICT_TOOL` 只有在一致性测试通过后才可被选用；测试不通过的方式自动从 `modes` 中摘除并告警。
- `BETA` 的方式缺省不被选用；空间可对具体模型开启，需在管理页确认「供应商标注为 Beta」。
- 私有化 vLLM / SGLang 部署：能力由部署时选择的结构化后端决定，适配器按后端填写；后端更换视为能力记录新版本，需重新测试（vLLM 文档提示换后端会改变允许的 token 与输出）。

### 4.1 生成方式的选择

每次结构化产出时，按以下顺序选第一个可用的方式：

1. `STRICT_SCHEMA`（若本次有多模态输入且 `multimodalDowngrade`，跳过）；
2. `STRICT_TOOL`（以 `emit_output` 工具承载）；
3. `JSON_OBJECT` + 提示中的方言 Schema 说明 + 一个由 Schema 生成的示例对象；
4. `TOOL`（非严格）；
5. `PROMPT`。

- 本 Turn 若开启思考模式而该方式 `thinkingCompat` 为 `NO`，跳过该方式；全部跳过时，结构化产出改用「先推理后成形」（第 4.2 节）。
- `max_tokens`：`UNSET` 时不设；`ESTIMATE` 时按方言 Schema 的规模估计（数组按契约声明的最大元素数）并乘 1.5，且不低于 2,000。
- 选定的方式与 `dialectHash` 写入 `producedBy`。

### 4.2 先推理后成形

`OutputContract` 增加 `strategy`：

| 取值 | 做法 | 适用 |
| --- | --- | --- |
| `DIRECT`（缺省） | 一次调用直接产出结构化结果 | 抽取、分类、字段填充 |
| `REASON_THEN_FORMAT` | 第一步不加格式约束，自由推理并作答（可用思考模式与工具）；第二步以第一步的结论为输入、不带工具，只做格式转换，按第 4.1 节选方式 | 需要多步推理的综合判断 |

- 第二步可使用契约指定的另一个模型别名（`formatModelAlias`，通常是便宜且支持 `STRICT_SCHEMA` 的模型）。
- 第二步的输入只含第一步的最终回答，不含原始上下文中的 `EXTERNAL` 段；但污点照常继承（ADR-063）。
- 证据绑定仍对最终 payload 做；第二步编造的引文会在第 5 节第 7 步被拒。

## 5. 输出处理流水线

| 步 | 名称 | 做法 | 失败时 |
| --- | --- | --- | --- |
| 1 | 完成状态 | 按适配器的 `refusalSignal`、`truncationSignal` 识别拒答、截断、内容过滤；内容为空或只有空白记 `EMPTY` | 记原因码，进入第 6 节对应的处理，不进入后续步骤 |
| 2 | 提取 | `STRICT_*` 方式直接取结构化字段；`JSON_OBJECT` / `PROMPT` 方式去掉首尾空白、BOM 与单个 Markdown 代码围栏，取唯一的顶层 JSON 值 | `NO_JSON`、`MULTIPLE_JSON` |
| 3 | 严格解析 | 标准 JSON（不接受注释、尾逗号、单引号、NaN）；重复键拒绝；嵌套深度 ≤ 32；数字按十进制精确值保存（金额不丢精度）；单个字符串 ≤ 64 KB | `SYNTAX` |
| 4 | 白名单规整 | 只做以下确定性规整，每次规整记入 `producedBy.normalizations`：① 枚举值按大小写不敏感且唯一匹配时改为规范值；② 字符串首尾空白；③ Schema 中标了 `x-tioka-coerce: number` 的字段，把只含数字、小数点、千分位逗号、正负号的字符串转为数字；④ 标了 `x-tioka-coerce: date` 的字段，把 `YYYY年M月D日`、`YYYY/M/D` 转为 ISO 日期 | 规整不了的留给第 5 步报错。**禁止**：补缺失字段、截断超长字符串、猜测枚举（如把「基本满足」映射为「满足」）、中文数字转换 |
| 5 | Schema 校验 | 用权威 Schema 完整校验（networknt，2020-12） | `SCHEMA`，附全部错误（不在第一个错误处停止） |
| 6 | 语义规则 | 执行 `x-tioka-rules`（第 5.1 节） | `RULE` |
| 7 | 证据与引用 | ADR-037 的证据绑定；`x-tioka-path` 路径存在；`x-tioka-ref` 引用存在（第 5.2 节）；假名化还原检查 | `EVIDENCE` 等 |
| 8 | 领域钩子 | 领域包注册的校验器，返回 `ERROR` / `WARN` | `HOOK`；`WARN` 不阻断，结局为 `VALID_WITH_UNKNOWN` 并记录 |

第 1〜8 步都在 `OUT` 检查点内执行（ADR-063），永远 `FAIL_CLOSED`。ADR-063 第 6.3 节的假名化还原在第 2 步之后、第 3 步之前执行。

### 5.1 语义规则 `x-tioka-rules`

- 位置：Schema 的任意对象上，值为规则数组。每条规则：`id`、`expr`（CEL 表达式，变量为当前对象，可用 `root` 访问根）、`message`（给模型与人看的说明）、`severity`（`ERROR` / `WARN`）、`hint`（是否写进生成方言的说明）。
- 例（文字描述）：租期对象上「到期日不早于起租日」；费用明细数组上「各期金额之和等于合同总额，误差不超过 1 元」；Finding 上「结论为不满足时，风险等级不能为无」。
- CEL 环境与 ADR-036 / LSE-04 的规则引擎共用（同一套函数库，含日期与金额函数），发布时编译，编译失败则 Schema 发布被拒。求值超时 50 ms，超时视为 `ERROR(reason=RULE_TIMEOUT)`。
- 规则只读 payload 本身，不读外部数据；需要外部数据的检查（如「承租人存在于主数据」）写成领域钩子。

### 5.2 引用完整性 `x-tioka-ref`

字符串字段可标 `x-tioka-ref`，声明它引用的对象类型：`doc.unit`（文档单元）、`doc.clause`（条款）、`kb.chunk`、`model.path`（等同 `x-tioka-path`）、`dataitem`（本 Turn 内的其他 `data` item）。第 7 步检查被引用对象存在且对本次运行可见。合同场景中「条款编号不存在」「引用了别的合同的单元」在此被拒。

## 6. 修复

| 原因 | 处理 |
| --- | --- |
| `TRUNCATED` | 不按「格式错误」修复。若可提高输出上限（未达模型上限），提高一档重试一次；否则直接进入降级阶梯的「分解」（第 7 节）或下一步 |
| `REFUSED` / `CONTENT_FILTERED` | 不修复、不追问；直接进入降级阶梯的下一步（换模型），并写 `guardrail.checked`（`FLAG`）供审计 |
| `EMPTY` | 原样重试一次（DeepSeek 文档的建议），仍为空则进入下一步 |
| `NO_JSON` / `MULTIPLE_JSON` / `SYNTAX` / `SCHEMA` / `RULE` / `EVIDENCE` / `HOOK` | 修复：把上一次候选输出与错误清单（JSON Pointer、原因、规则 `message`）作为一条 `tool_result` 交回模型，要求输出完整的修正版本 |

修复的约束：

- 轮数上限仍为 `maxRepair`（缺省 2），另加 token 上限 `maxRepairTokens`（缺省为首次输出 token 的 2 倍），先到者为准。
- **早停**：某一轮的错误集合与上一轮完全相同（同路径、同原因），说明模型修不好，立即停止修复进入降级。
- 修复轮使用与首次相同的生成方式与方言，保证方言缓存命中。
- 修复只给错误信息，不给「正确答案」提示（避免把校验器的推断当成模型输出）。

## 7. 降级阶梯与兜底

### 7.1 契约声明

`OutputContract` 增加 `fallback`，为有序步骤列表，缺省为 `[REPAIR, SWITCH_MODE, SWITCH_MODEL, HUMAN]`：

| 步骤 | 做法 |
| --- | --- |
| `REPAIR` | 第 6 节 |
| `SWITCH_MODE` | 同一模型换更严格的生成方式（如 `JSON_OBJECT` → `STRICT_TOOL`）；没有更严格的方式则跳过 |
| `SWITCH_MODEL` | 按 Agent 路由计划的结构化候选（ADR-024 别名的故障接力列表中、能力可编译且满足标签与配额的模型）换下一个，优先 `STRICT_SCHEMA` 能力；最多换 `maxModelSwitches`（缺省 1）次 |
| `DECOMPOSE` | 把根对象的顶层字段按 `x-tioka-group` 分组，各组单独生成并校验，再组装后整体校验（语义规则在组装后执行）；数组输出按批（缺省每批 20 个元素）生成；仅当 Schema 声明了分组时可用 |
| `FALLBACK_VALUE` | 第 7.3 节 |
| `HUMAN` | 生成人工补录任务，结局 `HUMAN_PENDING`（第 7.4 节） |
| `FAIL` | 结局 `FAILED` |

- 每一步都受本 Turn 预算与配额约束；预算不足时跳到列表中第一个不调用模型的步骤（`FALLBACK_VALUE` / `HUMAN` / `FAIL`），原因码 `BUDGET`。
- 每一步的执行写入 `producedBy.fallbackSteps`，并发 `item.progress` 事件，前端显示「正在换用其他模型重试」之类的状态。
- 列表最后一步必须是 `FALLBACK_VALUE`、`HUMAN` 或 `FAIL` 之一，发布校验检查。

### 7.2 生成方式与模型的降级不能突破标签

`SWITCH_MODEL` 只在数据标签允许的模型中选择（ADR-040）；没有可用模型时原因码 `NO_ELIGIBLE_MODEL`，继续下一步。

### 7.3 兜底值

- 只有 Schema 用 `x-tioka-fallback` 声明了兜底的字段可以取兜底值，取值必须是 Schema 声明的「未知 / 待复核」状态（严格档 S7），例如 Finding 的 `conclusion = pending_review`、`riskLevel = unknown`。
- 兜底的触发条件：其余字段全部通过校验、只有声明了兜底的字段有错误。此时把出错字段替换为兜底值，重新执行第 5〜8 步；通过则结局为 `VALID_WITH_UNKNOWN`，`unknownPaths` 列出这些字段。
- 没有声明兜底的字段出错时 `FALLBACK_VALUE` 步骤不适用，继续下一步。
- 平台永远不填「看起来合理」的默认值（如 0、空字符串、「满足」）。

### 7.4 人工补录

- 生成一条审批台任务（ADR-028、HCI-05），类型 `data.complete`：显示由 Schema 生成的表单，预填 `lastCandidate` 中通过校验的字段，出错字段高亮并附错误说明与证据定位。
- 人提交的数据同样经第 3〜8 步校验（人也会填错）；通过后写新的 `data` item（`outcome=VALID`，`producedBy.human = 用户 ID`），原 Turn 从 `WAITING` 续跑。
- 补录任务有超时（缺省 3 个工作日），超时结局 `FAILED(reason=HUMAN_TIMEOUT)`。

## 8. 消费方的结局处理

| 消费方 | 规定 |
| --- | --- |
| Flow 结构化抽取节点、Agent 节点（声明了输出契约时） | 节点有三个输出端口：`valid`（含 `VALID_WITH_UNKNOWN`）、`pending`（`HUMAN_PENDING` 时节点挂起，补录后从 `valid` 继续）、`failed`。Flow 发布校验要求 `failed` 端口有连线，或节点配置「失败时终止并通知」；未处理即发布被拒（ADR-043 的 DSL 校验清单增加一条） |
| 协调者 Agent（ADR-038） | 子 Agent 的 `data` item 作为工具结果返回时，`FAILED` 以结构化的错误工具结果交回协调者，由协调者决定重派或上报；平台不把 `lastCandidate` 交给协调者 |
| 编排派工器（ADR-020） | 规则可匹配 `outcome`；未匹配 `FAILED` 时按缺省规则生成待办给 Agent 负责人 |
| 业务提交工具 | 入参引用登记表 Schema（ADR-037 已定）；ToolGateway `prepare` 只接受 `VALID` / `VALID_WITH_UNKNOWN` 的 `data` item 引用或通过同一流水线第 3〜8 步的参数；`unknownPaths` 非空时，提交是否允许由工具声明（`acceptsUnknown`），缺省不允许并转审批 |
| 黑板 `team.publish` | 只接受 `VALID` / `VALID_WITH_UNKNOWN` |
| 外部调用方（Flow 发布为 API、A2A） | 响应体固定包含 `outcome`；`FAILED` 时 HTTP 仍为 200，`outcome=FAILED` 与原因码，不返回 500（500 只用于平台自身故障） |

## 9. Schema 演进

ADR-037 的「发布后不可改」「不做自动迁移（存储中的旧数据不改写）」保持不变，增加以下规则：

### 9.1 兼容组与兼容性判定

- Schema 增加 `compatGroup`（缺省等于 `schemaKey`）。同一兼容组内的新版本必须与前一版本**读兼容**，由登记表在发布时自动判定：

| 变更 | 判定 |
| --- | --- |
| 新增可空字段（必填但可为 null） | 兼容：旧数据读时补 null |
| 新增枚举值 | **不兼容**（旧消费方不认识新值），除非该枚举声明了 `x-tioka-open-enum`（消费方承诺把未知值当作兜底值处理） |
| 删除字段、改类型、改名、收窄枚举、新增非空必填字段 | 不兼容 |
| 放宽长度、范围等校验约束 | 兼容 |
| 收紧校验约束、增加 `ERROR` 级语义规则 | 生成侧兼容、读侧兼容，但要求重跑该 Schema 的评测（第 10 节）后才能发布 |
| 修改说明文字、`x-tioka-enum-doc` | 兼容；生成方言变化，会使提示缓存与文法缓存失效一次 |

- 不兼容的变更必须开新兼容组（如 `lease.finding` → `lease.finding.v2` 组），由领域包同时发布读时升级器（第 9.3 节）或说明不提供。

### 9.2 消费方声明

- 生产方（AgentSpec `output.schemaRef`）钉住精确版本。
- 消费方（Flow 节点输入、业务工具入参、`DataItemReader` 的调用点、前端渲染器）声明可接受的范围：兼容组 + 最低版本。登记表维护「谁在消费哪个兼容组」的引用表；被引用的版本不能删除，只能标 `DEPRECATED`。
- Agent 发布校验：产出的 Schema 版本必须落在其下游（同一 Flow、同一团队内）消费方声明的范围内。

### 9.3 读时升级

- 同一兼容组内，消费方读取旧版本数据时，`DataItemReader` 按登记表的字段差异自动补 null，得到消费方声明版本的形态；存储中的数据不变。
- 跨兼容组的读取需要领域包注册的升级器（声明式：字段映射、常量、枚举映射表；不允许任意代码），升级后的对象再按目标版本的权威 Schema 校验，失败则该条记 `FAILED(reason=UPCAST)`，不交给消费方。

## 10. 可观测、测试与闸门

### 10.1 指标

按（`schemaRef`、模型、生成方式）统计，进入 ADR-041 内置统计与告警：

| 指标 | 定义 |
| --- | --- |
| 首次通过率 | 不经修复即 `VALID` 的比例 |
| 最终通过率 | `VALID` + `VALID_WITH_UNKNOWN` 的比例 |
| 兜底率、人工补录率、失败率 | 各结局比例 |
| 平均修复轮数、修复 token 占比 | — |
| 原因码分布 | 按第 1.2 节 |
| 规整次数 | 按第 5 节第 4 步的类别 |
| 时延 | 结构化产出端到端 P50 / P95 |

告警缺省：某（Schema、模型）首次通过率 7 日均值下降超过 5 个百分点，或失败率超过 1%。

### 10.2 一致性测试

- **模型接入测试**：每个模型版本在启用结构化能力前，对平台的一致性测试集（覆盖严格档全部关键字组合、深度与规模上限、枚举、可空、引用、中文长文本、多模态输入、思考模式开关）逐方式运行，测出 `modes`、`keywords`、`limits`、`thinkingCompat`、`multimodalDowngrade` 的实际值，写回能力记录。
- **Schema 发布测试**：严格档 Schema 发布时，对其路由计划中的候选模型各跑一次合成样例（由 Schema 生成的提示），确认能产出 `VALID`；失败的模型从候选中排除。
- **夜间回归**：真实模型对合同场景的 Schema 跑固定样本（ADR-031 的批次评测），记录首次通过率与失败率；CI 用录制回放的模型桩。

### 10.3 异常逃逸测试

对输出流水线做变异测试：以合法 payload 为种子，自动生成截断、错位括号、重复键、超大数字、超深嵌套、非法 Unicode、错误类型、缺字段、多余字段、伪造引文、未知占位符等变异，断言：每个输入都恰好得到四种结局之一，没有任何异常逃出流水线，且 `VALID` 结局的 payload 都能通过独立的第二个校验器（另一个 JSON Schema 实现）复核。该测试进 CI。

### 10.4 闸门

| 闸门 | 条件 |
| --- | --- |
| G3（合同场景） | 合同 Schema（`lease.finding`、`lease.contract.*`）在 30 份评测合同上：最终失败率 ≤ 0.5%；异常逃逸为零；`VALID` 结局中证据绑定错误为零；注入样本见 ADR-063 GRD-08a |
| Schema 发布 | 第 10.2 节的发布测试通过 |
| 模型启用结构化能力 | 第 10.2 节的接入测试通过 |

## 11. 不做的事

- 不承诺模型一次产出正确；不以供应商的「严格」声明代替平台校验。
- 不对部分对象做「尽力交付」；不自动填充业务默认值。
- 不做 `data` item 的增量流式交付（ADR-037 已定）；前端可显示阶段进度。
- 不改写存储中的历史数据。

## 12. 工作项

| ID | 工作项 | 里程碑 | 优先级 | 人日 | 依赖 | 验收 |
| --- | --- | --- | --- | --- | --- | --- |
| SCH-06 | 严格档发布检查、生成方言编译器与缓存、能力记录（取代 `structuredOutput` 三档）、发布时按候选模型试编译 | M3 | P0 | 后端 3 | SCH-01、SCH-02 | 含 `minLength` 的 Schema 发给 DeepSeek 严格工具时被编译掉，平台校验仍拦住超长值 |
| SCH-07 | 完成状态识别、提取、严格解析、白名单规整、四种结局与原因码、`DataItemReader` 与 ArchUnit 规则、异常逃逸变异测试 | M3 | P0 | 后端 2 | SCH-03 | 截断不进入修复；变异测试零逃逸 |
| SCH-08 | `x-tioka-rules`（CEL，与 LSE-04 共用环境）、`x-tioka-ref` 引用完整性、领域钩子 `WARN` | M3 | P0 | 后端 2 | SCH-03、ONT-04 | 起租日晚于到期日被拒并进入修复 |
| SCH-09 | 修复分类与早停、降级阶梯 `REPAIR` / `SWITCH_MODE` / `SWITCH_MODEL` / `FALLBACK_VALUE` / `HUMAN` / `FAIL`、人工补录任务、Flow 节点三端口与发布校验 | M3 | P0 | 后端 3 | SCH-07、FLW-11a、HCI-05 | 结论字段出错时得到 `pending_review` 且 `unknownPaths` 正确；Flow 未连 `failed` 端口发布被拒 |
| SCH-10 | 一致性测试集与模型接入测试、指标与告警 | M3 | P1 | 后端 1 | SCH-06、GOV-13 | 百炼与 DeepSeek 各一个模型测出能力记录 |
| SCH-11 | 兼容组、发布时兼容性判定、消费方声明与引用表、读时升级与跨组升级器 | M4 | P1 | 后端 3 | SCH-06 | 新增枚举值在同组发布被拒 |
| SCH-12 | `DECOMPOSE` 分解生成、`REASON_THEN_FORMAT` 两步法与 `formatModelAlias` | M4 | P1 | 后端 3 | SCH-09、EXE-02 | 200 条费用明细分批生成后整体通过 |
| FE-41 | 结构化输出指标页、`data` item 结局与降级步骤展示、人工补录表单 | M4 | P1 | 前端 2 | SCH-09、SCH-10 | — |

人工补录表单的最小版本（只显示 JSON 与错误、可编辑提交）随 SCH-09 在 M3 由 SCH-04 的通用 JSON 树扩展实现，不另计前端人日；FE-41 做正式的 Schema 表单。

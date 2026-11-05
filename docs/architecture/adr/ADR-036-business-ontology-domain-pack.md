# ADR-036 — 业务本体与领域包

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：ONT-01〜ONT-09、DOC-01〜DOC-03（见 34 号计划「业务本体与合同场景」）
- 对应设计：《租赁合同模型》规格 v2.3（业务方脑图，2026-10-13，四层、五支、@model.build 十步）
- 阅读版：[商业租赁合同本体与风险审查 Agent 设计](https://claude.ai/artifact/PxdZR5xfAkyauCJ7AUQfAd)
- 施工设计：[M3-03-业务本体与文档层](../construction/M3-03-业务本体与文档层.md)、[M3-04-租赁合同风险审查](../construction/M3-04-租赁合同风险审查.md)
- 相关：[ADR-018](ADR-018-knowledge-ingest-retrieval.md) 知识、[ADR-019](ADR-019-flow-engine-publication.md) Flow、[ADR-026](ADR-026-foundation.md) 插件宿主、[ADR-037](ADR-037-structured-outputs.md) 结构化输出、[ADR-040](ADR-040-dataset-policy-data-label.md) 数据标签；ADR-029 第 50〜53 条
- 约束：与 ADR-029 定案冲突时以定案为准；修改本 ADR 走 ADR-030 的变更流程。

## 背景

商业租赁合同风险审查只是第一个场景，后面还有租金结构化、执行风险预警、权益受损预警。业务方的规格（下称「规格」）已经说明：如果每个任务都直接从原文召回证据，各任务会各抽各的押金、租金、免租期，没有一份可确认、可复用的合同事实。规格的解法是在文档和任务之间加一层**合同模型**，下游只读模型和租约状态。

Tioka 目前只有 Knowledge（切块 + 向量检索），没有「业务对象 + 取值 + 证据 + 版本」这一层。本 ADR 把这一层做成**平台能力**，租赁特有的部分做成**领域包**。换成采购合同、加盟合同时只换领域包。

## 决定

1. 平台新增**业务本体**模块（`ontology-api` / `ontology-biz`），提供四样东西：模型定义资产、文档层、实例投影与版本、读模型工具。规格「划界」一节列给平台的能力全部落在这里。
2. 租赁特有的内容放进**领域包**：对象类型、陈述词表、主题分类、Prompt、校验钩子、折叠引擎、投影钩子。领域包是 ADR-026 插件宿主加载的第十类插件（`domain-pack`），独立类加载器、签名校验。
3. **已确认的模型实例以业务系统为准**（ADR-029 第 50 条）。Tioka 产出草稿；确认在业务系统里完成；Tioka 按确认事件保存只读投影，供 Agent 和规则读取。租约状态是派生数据，由 Tioka 的折叠引擎计算后写回业务系统，任何时候都可以从已确认模型重算。
4. **文档层和 Knowledge 分开**（第 51 条）。合同原文切成证据单元，按位置引用，不做向量召回。规格的约束「需要原文时通过证据引用定位，不重新召回」由此保证。
5. 规则在模型上用 **CEL** 求值，采用三值语义：取值未知时结论是「缺失」，不是「不满足」（第 53 条）。

## 四层与归属

| 层 | 内容 | 产生方 | 权威方 | Tioka 里的形态 |
| --- | --- | --- | --- | --- |
| 文档层 | 文件、页、文字层与 OCR 合并、条款树、证据单元 | Tioka | Tioka（原文件以业务系统为准） | `doc_*` 表，按文件哈希缓存 |
| 合同模型 | 每份合同一个实例：骨架、标的与期限、商务对象、权利义务、修改声明 | Tioka 抽取草稿，法务确认 | **业务系统** | 草稿表 + 已确认版本投影 |
| 租约状态 | 门店级时间轴，按合同链折叠 | Tioka 折叠引擎 | 派生数据，可重算 | `ont_state` 表，写回业务系统 |
| 下游任务 | 风险审查、租金结构化、执行与权益预警 | 各场景 Agent | 业务系统（结论） | 只有运行记录与 `data` item |

**为什么草稿在 Tioka、确认版本在业务系统**：草稿是模型产物，要和证据、抽取轨迹、评测放在一起；确认是业务动作，要进业务系统的审计和权限。规格要求「实例存储与确认界面属于平台」，这里的折中是确认界面由 Tioka 提供 Vue 组件，嵌进业务系统页面，写操作走业务系统自己的接口（见 M3-04）。

## 模型定义（资产）

模型定义与 AgentSpec 一样是版本化资产（ADR-023 的草稿、发布、回滚、diff 机制照用），场景发布时锁定版本。

```java
public record ModelDefinition(
    int schemaVersion,
    String defKey,                        // lease.contract
    String entityType,                    // @contract.model
    BusinessKeyRule businessKey,          // 门店编码 × 合同号；缺合同号时用首份文件哈希
    List<ObjectType> objectTypes,         // 商务对象：费用项、押金、跨项规则、标的、当事方……
    List<StatementType> statementTypes,   // OBLIGATION / RIGHT / CONSEQUENCE / CONDITION / PROCEDURE 及参数结构
    List<Topic> topics,                   // 19 个主题，多标签；每个主题期望出现哪些对象与陈述
    Map<String, Vocabulary> vocabularies, // 动作、权利类型、后果类型、归责原因、合同种类、关系类型
    ModificationVocabulary modifications, // 修改类型 → 变更操作（@TERM_RENEW、@ITEM_ADD……）
    List<String> validatorIds,            // 校验规则集，由领域包实现
    List<FieldView> baseViews) {}         // 基础字段视图（扁平字段 → 模型路径）

public record ObjectType(
    String typeKey,                       // feeItem / deposit / crossRule / subject / party
    List<String> identityKeys,            // feeItem: [subject, feeCode]；deposit: [subject, depositType, originalName?]
    List<AttrGroup> groups,               // feeItem: CALC / SCHED / PAY / TAX / ABATE
    List<EvidenceRole> evidenceRoles,     // 事实 / 期间 / 金额 / 当事方 / 其他
    List<Ref> refs) {}                    // 对其他对象的引用，如 feeItem.subject → subject
```

模型定义同时生成两份派生物，不手写：

- **JSON Schema**：每个对象类型一份，注册到 ADR-037 的 Schema 登记表，抽取步骤用它约束模型输出。
- **路径目录**：每个属性一条路径（如 `feeItems[RENT@S1].sched.segments[*].amount`），供 `@model.query`、CEL 规则和字段视图引用；发布时校验所有规则、视图引用的路径都存在。

## 取值单元

每个叶子取值都是同一个结构，规格「通用类型与取值」一节逐项对应：

```java
public record ValueCell(
    JsonNode value,                       // 归一化后的值；金额另存原值与原单位
    ValueMethod method,                   // E 抽取 / D 推导 / C 配置默认 / I 上游输入
    ValueStatus status,                   // 明示 / 推导 / 默认 / 继承 / 未知 / 不适用 / 空白 / 待复核
    String reviewReason,                  // 待复核时必填
    List<Source> sources) {}              // 一个取值可以有多个来源

public record Source(
    String unitId,                        // 证据单元
    String quote,                         // 引文，归一化后必须能在单元原文里找到
    EvidenceRole role,
    Extractor extractor,                  // LLM / RULE / HUMAN / BASELINE
    double confidence) {}
```

两条硬约束由平台执行，不依赖领域包：

- `method = E` 的取值必须至少有一个 `Source`，且引文校验通过（ADR-037 的证据绑定检查）。
- `status = 空白` 的金额在 CEL 里不等于 0（见「规则求值」）。

## 文档层

| 对象 | 关键字段 | 说明 |
| --- | --- | --- |
| 文件 `doc_document` | `file_hash`、`mime`、`source_ref`（业务系统文件 ID）、`parse_status`、`parser_version` | 同哈希同解析器版本只解析一次 |
| 页 `doc_page` | `page_no`、`text_layer`、`ocr_text`、`merged_text`、`ocr_model` | PDF 有文字层且字符密度足够就不 OCR；扫描页、图片走 OCR；两者都有时按位置合并 |
| 证据单元 `doc_unit` | `unit_id`（`{docHash}:{page}:{seq}`）、`kind`（段落 / 表格单元格 / 标题 / 签章区）、`bbox`、`text`、`norm_text`、`clause_path`、`section`（定义 / 专用 / 通用 / 附件） | 引用的最小粒度；`norm_text` 用于引文校验 |
| 条款 `doc_clause` | `clause_path`（如「第二十二条」「4.1.1」）、`title`、`unit_ids`、`status`（有效 / 空白待填 / 已被修改）、`topics` | 条款树；主题由 @model.build 第 1 步打标 |

解析器按格式分三路：Word（`.docx` 走 POI，`.doc` 先转 docx）、PDF（PDFBox 取文字层）、图片与扫描页（OCR）。OCR 是 Model Hub 的一种能力（`capability = ocr`），按路由计划调用百炼的 OCR / 视觉模型，和对话模型一样受配额、审计和数据标签约束。这部分从 KNW-09 中拆出提前到 M3（第 52 条）。

## 实例、版本与投影

- **业务键**：`{domain}:{门店编码}:{合同号}`；没有合同号时用首份文件哈希，合同号补齐后由业务系统发更名事件。
- **草稿**：`ont_draft` 一份合同一行当前草稿，`model_json` 为整份模型（JSONB），附 `build_run_id`、`def_version`、`validation_json`。草稿改动不发事件。
- **提交确认**：草稿通过业务系统的 `proposeModelDraft` 推过去，业务系统返回 `draftRef`。确认界面在业务系统里打开，确认时业务系统生成版本号。
- **已确认版本**：业务系统发 `contract.model.confirmed`（含版本号与内容哈希）。Tioka 拉取完整模型写入 `ont_instance_version`，状态 CONFIRMED，前一版标 SUPERSEDED，再发平台事件 `tioka.ontology.instance_confirmed.v1`。内容哈希不一致时拒收并告警，不做合并。
- **差异**：每个确认版本保存与上一版、与对应草稿的路径级差异，供评测（草稿 vs 确认 = 人工更正）和审查重跑判断。
- **跨合同引用**：补充协议引用同门店之前的对象和条款时，记被引用实例的版本号；被引用实例出新版本后，引用方不自动改，只在折叠时按新版本计算并标记「上游已更新」。
- **下游订阅**：`instance_confirmed` 事件触发三件事：门店重新折叠、领域包投影钩子、编排规则（比如审查重跑）。三者都幂等，键为 `instanceKey + version`。

## @model.build 建模预设

@model.build 不是一段写死的代码，而是平台按模型定义展开的 **Flow 模板**（ADR-019），领域包只提供 Prompt 和校验钩子。十步与规格一一对应：

| 步 | 内容 | 执行方式 | 输出 |
| --- | --- | --- | --- |
| 0 | 解析文档 | 文档层服务；缓存命中直接跳过 | 证据单元、条款树 |
| 1 | 条款单元与主题 | 模型批量打标，每批约 30 个条款 | 条款主题（多标签） |
| 2 | 对象清点 | 模型按对象类型清点身份键 | 对象清单（只有身份，不抽属性） |
| 3 | 逐对象抽属性 | **map 节点**，每个对象一个分支；输出受该对象类型的 JSON Schema 约束 | 属性取值 + 来源 |
| 4 | 逐条款抽陈述 | map 节点，范围是第 1 步打上主题的条款 | 陈述 |
| 5 | 修改声明 | 只对补充协议、变更协议执行 | 修改声明（新内容 + 生效期 + 去向） |
| 6 | 校验 | 领域包校验钩子；拒收的对象回到第 3 步重抽，最多 2 次，仍不过的取值标「待复核」 | 校验报告 |
| 7 | 写草稿 | 平台 | `ont_draft` |
| 8 | 人工确认 | 业务系统确认界面（Tioka 组件嵌入） | 确认版本 |
| 9 | 发布 | 收到确认事件后写投影 | `instance_confirmed` 事件 |

两点说明：

- 第 3、4 步需要 Flow 的**并行 map 节点**（FLW-08，见 34 号计划）。分支并发上限按模型路由计划的 QPS 配，默认 8。
- **增量**：同门店已有确认模型时，新合同只建自己的实例；旧合同不重抽。单份审查不需要合同链也能用。

## 读模型工具

三个平台工具，注册进 ToolGateway，所有 Agent 可用（受授权书和数据集策略约束）：

| 工具 | 入参 | 返回 |
| --- | --- | --- |
| `@entity.read` | `instanceKey` 或 `storeKey`；`version`（缺省最新确认版，可指定 `draft`） | 一份模型或一个门店的全部模型 |
| `@model.query` | `instanceKey`；`paths[]` 或 `expr`（CEL） | 每项：值、取值状态、来源（单元 ID + 引文） |
| `@model.coverage` | `instanceKey`；`topics[]`（缺省全部） | 每个主题：有 / 无 / 空白 / 不适用，以及缺了哪些期望的对象与陈述 |

`@model.query` 返回的证据只是单元 ID 和引文；Agent 要看上下文时用 `@doc.unit`（按单元 ID 取前后各 N 个单元），不做检索。

## 规则求值

`rule.evaluate` 是平台工具，也是 Java API，在模型上执行 CEL 表达式（与 ADR-040 的行谓词同一个 CEL 运行时）。一条规则的结构：

```yaml
ruleId: lease.D.017
applies: has(m.feeItems.RENT)                       # 适用条件；false → 不适用
requires: [m.feeItems.RENT.abate]                   # 依赖路径；任一未知 → 缺失
check: m.feeItems.RENT.abate.freeMonths() >= cfg.minFreeMonths
onBlank: NOT_SATISFIED                              # 依赖路径为空白时的结论，缺省 NOT_SATISFIED
params: [minFreeMonths]                             # 阈值来自业务系统配置，记配置版本
```

三值语义（第 53 条）：

1. `applies` 为 false → 不适用。
2. `requires` 中任一路径状态为「未知」→ 缺失；为「不适用」→ 不适用；为「空白」→ `onBlank`。
3. `check` 求值时访问到未知值，结果也是缺失，而不是抛错或当成 false。实现上 CEL 的取值函数返回 `optional`，`check` 结果为 `optional.none` 时记缺失。
4. 结论附上所有访问过的路径、值、状态和来源，直接作为 Finding 的 `modelRefs`。

CEL 环境内置的函数（平台提供，领域包可以注册更多）：`status(x)`、`isKnown(x)`、`months(period)`、`segments(item)`、`sum(list)`、`annualRate(segments)`；租赁领域包注册 `freeMonths()`、`escalationRate()`、`depositMonths()` 等。

## 领域包 SPI

```java
public interface DomainPack {
    String packKey();                          // lease
    String version();
    ModelDefinition definition();
    PromptBundle prompts();                    // @model.build 各步的提示词，按步骤与对象类型索引
    List<ModelValidator> validators();         // 校验规则集
    Optional<FoldingEngine> folding();         // 有合同链折叠的领域才实现
    List<ProjectionHook> projections();        // 确认后的投影，如写回业务系统、外部台账
    List<CelFunction> celFunctions();
}

public interface ModelValidator {
    String validatorId();                      // lease.amount-traceable
    List<Violation> validate(ModelDraft draft, DocumentView docs);   // 纯函数
}

public interface FoldingEngine {
    LeaseState fold(String storeKey, List<ConfirmedModel> chain);    // 纯函数，结果与到达顺序无关
}
```

约束：

- 钩子都是**纯函数**：只读入参，不访问网络与数据库。插件清单里声明的权限只有 `cpu`，宿主拒绝其他权限。
- 校验结果分两种：`REJECT`（回第 3 步重抽）和 `REVIEW`（取值标待复核并写原因）。规格的 16 条校验规则在领域包里逐条实现，规则编号与规格对应。
- 折叠引擎按规格的五个阶段执行（结构 → 建对象 → 取值 → 续约补齐 → 边界），R-1〜R-5 与 11 条折叠规则在领域包里实现。平台只负责调用时机和存储：同门店任一实例确认后全量重折叠，输入为该门店所有确认版本，输出写 `ont_state`，以「门店键 + 输入版本集合哈希」去重。

## 权限与数据标签

- 实例投影是一个数据集（ADR-040）。业务系统在确认事件里带门店所属区域与经办人，Tioka 写入 `acl_principals`；`@entity.read` 等工具按 `on_behalf_of` 的权限过滤（ADR-039）。
- 合同模型与证据单元的数据标签缺省 CONFIDENTIAL。按 2026-11-05 的业务决定，本部署的空间配置允许 CONFIDENTIAL 进入百炼、DeepSeek 等云端模型，不做脱敏；如果以后有客户不允许，按 ADR-040 的去向许可切换路由计划，不改本设计。

## 后续场景如何复用

| 场景 | 读什么 | 新增什么 |
| --- | --- | --- |
| 风险审查 | 模型 + 覆盖 | 审查要点（业务系统）、Finding Schema |
| 租金结构化 | 租约状态 | 字段模板（模型路径 / CEL / 回原文抽取三种取值方式），回原文的值标「模型外」 |
| 执行风险预警 | 租约状态 + 履约事件 | 事件到时间轴的对齐规则、`orch_timer` 定时 |
| 权益受损预警 | 模型的权利陈述 + 履约事件 | 权利 → 触发条件的映射 |

四个场景共用文档层、模型、规则求值和 Finding 回路，只有各自的规则与 Schema 不同。

## 不做的事

- 不做通用知识图谱和图查询。模型是按合同组织的文档型数据，跨合同只有「同门店」和「引用」两种关系，用表就够。
- 不让 Tioka 修改已确认版本。更正一律在业务系统里产生新版本。
- 第一期不做模型定义的可视化编辑器，模型定义以 YAML 随领域包发布。

## 工作项

| ID | 工作项 | 里程碑 | 优先级 | 人日 | 依赖 | 验收 |
| --- | --- | --- | --- | --- | --- | --- |
| DOC-01 | 文档层：Word / PDF 解析、证据单元、条款树、按哈希缓存 | M3 | P0 | 5 | KNW-01 | 3 种格式的样本合同都能定位到条款与单元 |
| DOC-02 | OCR 能力接入 Model Hub、文字层与 OCR 合并（KNW-09 的 OCR 部分） | M3 | P0 | 4 | MH-01、DOC-01 | 扫描件条款路径识别率 ≥ 95%（10 组样本） |
| DOC-03 | `@doc.unit` 工具与引文归一化校验 | M3 | P0 | 2 | DOC-01 | 篡改一字的引文被拒 |
| ONT-01 | 模型定义资产、路径目录、Schema 派生 | M3 | P0 | 5 | AST-01、ADR-037 | 规则引用不存在的路径时发布失败 |
| ONT-02 | 草稿、确认投影、版本与差异、`instance_confirmed` 事件 | M3 | P0 | 5 | ORC-02、ONT-01 | 内容哈希不一致时拒收 |
| ONT-03 | `@entity.read`、`@model.query`、`@model.coverage` | M3 | P0 | 4 | ONT-02、DAT-01 | 无门店权限的经办人读不到实例 |
| ONT-04 | `rule.evaluate` 与三值语义 | M3 | P0 | 4 | ONT-01 | 未知 → 缺失、空白 → onBlank 表驱动用例全过 |
| ONT-05 | 领域包 SPI 与插件加载（`domain-pack`） | M3 | P0 | 3 | 插件宿主 | 声明网络权限的领域包加载被拒 |
| ONT-06 | @model.build Flow 模板展开 | M3 | P0 | 5 | FLW-08、ONT-01、ADR-037 | 一份 30 页合同端到端出草稿 |
| ONT-07 | 折叠调度、`ont_state`、去重 | M3 | P1 | 3 | ONT-02、ONT-05 | 乱序到达的确认事件得到相同状态 |
| ONT-08 | 确认界面 Vue 组件（可嵌入业务系统） | M3 | P0 | 6 | ONT-02 | 只显示差异、空白、低置信度与必备要点依赖的取值 |
| ONT-09 | 模型层评测：对象 / 属性 / 陈述的准确率与召回率、主题覆盖率、证据绑定率 | M4 | P1 | 4 | EVO-02 | 每次领域包发布自动出报告 |

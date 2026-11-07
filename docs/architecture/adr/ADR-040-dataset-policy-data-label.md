# ADR-040 — 数据集策略与数据标签

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：DAT-01〜03（新增）
- 对应设计：设计 Q（多智能体与运行时权限）
- 阅读版：[Tioka 多智能体自主协作与权限管控设计](https://claude.ai/artifact/Qy9taJVV8cz2B7VBGewkJA)
- 相关：[ADR-015](ADR-015-identity-tenant-rbac.md)、[ADR-018](ADR-018-knowledge-ingest-retrieval.md)、[ADR-024](ADR-024-model-hub-routing.md)、[ADR-025](ADR-025-governance.md)、[ADR-031](ADR-031-agent-eval-experience.md)、[ADR-039](ADR-039-delegation-grant.md)
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

核心决定有两条：

1. **取数时过滤**：数据权限分四层，前三层已有，本篇新增第四层「行与字段」，在连接器数据平面执行。看不到就不会泄露。
2. **取数后管去向**：每份数据带标签，标签只升不降、随产物流动，决定子 Agent 能否读、能调哪些模型、能否发到某个渠道。多智能体比单 Agent 多出来的风险就在这里：一个子 Agent 合法读到的数据，经黑板传给另一个子 Agent，再被发到群里或外部模型。

## 四层取数过滤

| 层 | 粒度 | 机制 | 出处 |
| --- | --- | --- | --- |
| L1 空间 | 空间整体 | MyBatis-Plus 拦截器自动加 `workspace_id` | ADR-015，不变 |
| L2 资源 | 知识库、数据集、MCP Server、Flow | `ResourceGuard` USE ∩ 授予 `resources` | ADR-015 + ADR-039 |
| L3 文档 | 知识库内单篇文档 | `aclPrincipals` 与知识库权限取交集，SQL 下推 | ADR-018、ADR-029 第 23 条，不变 |
| L4 行与字段 | 业务数据的行、列 | 数据集策略：行谓词 + 列掩码 | 本 ADR |

## 数据集与数据集策略（DAT-01）

通用连接器（JDBC、HTTP/REST、业务插件如 `biz.lease`）把一张表、一个视图或一个查询接口登记为**数据集**，声明列、列类型与列密级。Agent 不写 SQL 直连业务库，只调内置工具 `data.query(dataset, filter, columns, limit)` 或业务插件声明了 `datasetId` 的查询工具。

```java
public record DatasetPolicy(String datasetId, int version,
    List<RowRule> rows,          // 多条取并集；一条也不命中 = 一行都看不到（默认拒绝）
    List<ColumnRule> columns) {} // 未列出的列按列密级的缺省掩码
record RowRule(String when,      // CEL，只引用 principal（链根，含 on_behalf_of 交集后的属性）
               String predicate) {}  // CEL 子集，引用 row.*，编译为 SQL WHERE 或 API 过滤参数
record ColumnRule(String column, String when, Mask mask) {}
enum Mask { NONE, PARTIAL, HASH, NULLIFY, DROP }
```

示例：`when: principal.roles.contains("lease_legal")`，`predicate: row.region in principal.attrs.regions`；承租方银行账号缺省 `PARTIAL`，`when: principal.roles.contains("finance")` 时 `NONE`。

**执行**：

- JDBC 类：谓词编译进外层 `SELECT … FROM (原查询) t WHERE …`，全部参数化绑定；CEL 子集只允许比较、`in`、`&&`、`||`、`!` 与列引用，不能拼字符串。
- REST 类与业务插件：优先映射为接口过滤参数；接口不支持时在插件内后过滤，并记一条「后过滤」事实提示性能。
- 列掩码在结果离开连接器前完成，模型拿到的就是掩码后的值。
- 授予的 `scope`（ADR-039）与行谓词 AND 合并，委派时只能再加条件。
- DELEGATED 模式下外部系统自己也会按用户过滤；数据集策略仍执行，作为外部系统权限粗糙时的兜底。SERVICE 模式下数据集策略是唯一的行级防线，因此 SERVICE 模式的 Agent 绑定的数据集必须有策略，否则发布校验失败。

**为什么用 CEL 而不是 Cedar**：Cedar 的结果是允许 / 拒绝，给不出「哪些行」；其部分求值能产出残差，但在 cedar-java 中仍是实验特性。CEL 已用于编排条件（ADR-020）和业务规则，不增加语言。分工：Cedar 管「能不能查这个数据集」，CEL 管「查到哪些行、哪些列」。若日后 cedar-java 部分求值稳定，可再评估统一。

策略随数据集版本化；修改走资产发布审批（GOV-06，资产类型增加 DATASET_POLICY），新策略可先 DRYRUN：同时计算新旧结果，只记录差异行数。

## 数据标签（DAT-02）

四档：`PUBLIC < INTERNAL < CONFIDENTIAL < SECRET`。客户已有数据分级标准时，按客户的档名映射到这四档。标签来源：数据集列密级（取结果中出现的列的最高值）、知识库或文档密级、MCP Server 配置、上传文件的空间缺省值（缺省 `INTERNAL`）。

规则三条：

1. **只升不降**。每个工具结果 item 带 `dataLabel`；Turn 标签取已读内容的最高值；子 Agent 产物继承其 Turn 的标签写上黑板（ADR-038）；读了该产物的 Turn 标签也升上去。压缩摘要、记忆、经验条目继承来源标签。只有人可以通过「降密审批」降低某个产物的标签，写审计。
2. **读取要许可**。授予的 `maxDataLabel` 低于产物或数据标签时读不到；黑板读取、知识检索、`data.query` 都检查。
3. **去向要许可**。每个去向有许可密级：模型路由计划、IM 渠道（区分内部群与外部群）、邮件与短信、外部 MCP、写工具参数。Turn 标签高于去向许可时：
   - 模型调用：只在允许该密级的路由计划内选模（DAT-03，叠加在 ADR-024 路由之上）；没有可用模型则 Turn 以 `NO_ELIGIBLE_MODEL` 失败并提示。
   - 消息类去向：先经 ContentSafetyHook 脱敏（ADR-025），脱敏后仍超则拒绝或转审批（按空间配置）。

去向许可由空间管理员配置。默认：`SECRET` 只允许私有化部署的模型且不允许任何外发；`CONFIDENTIAL` 允许空间批准过数据出境的云端模型供应商（如客户已签数据处理协议的百炼、DeepSeek），只允许内部 IM 渠道；`INTERNAL` 及以下不限。是否允许 `CONFIDENTIAL` 数据进云端模型是客户的合规决定，不是平台默认放行。

## 共享记忆与经验

Agent 级经验（ADR-031）跨用户共享，是最容易漏的口子。规定：经验库只接受 `INTERNAL` 及以下的条目；来源更高的候选经验先脱敏，再经负责人审核才能入库；用户记忆只属于本人，子 Agent 只读链根用户的记忆。

## 执行点汇总

| 执行点 | 检查 | 本 ADR 新增 |
| --- | --- | --- |
| 连接器数据平面 | 行谓词、列掩码、结果打标签 | 是 |
| 知识检索 | 知识库 ACL ∩ 文档 ACL ∩ 授予资源 ∩ `maxDataLabel` | 增补标签 |
| ModelGateway | Turn 标签 → 可用路由计划 | 是 |
| 对外去向 | 去向许可 + ContentSafetyHook | 是 |
| 黑板读写 | 读取方 `maxDataLabel` | 是 |

审计：行级过滤不记行内容，只记策略版本、返回行数、被掩码的列名；`audit_event.data_label` 记该次调用后的 Turn 标签。

## 数据模型

```sql
CREATE TABLE data_dataset (id VARCHAR(32) PRIMARY KEY, workspace_id VARCHAR(64) NOT NULL, connector_id VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL, source_json TEXT NOT NULL,            -- 表 / 视图 / 查询 / 接口
  columns_json TEXT NOT NULL,                                       -- 列名、类型、密级、缺省掩码
  owner_id VARCHAR(32) NOT NULL, status VARCHAR(16) NOT NULL, UNIQUE (workspace_id, name));
CREATE TABLE data_dataset_policy (dataset_id VARCHAR(32), version INT, rows_json TEXT NOT NULL, columns_json TEXT,
  mode VARCHAR(8) NOT NULL,                                         -- DRYRUN / ENFORCE
  status VARCHAR(16) NOT NULL, created_by VARCHAR(32), created_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (dataset_id, version));
CREATE TABLE gov_label_sink (workspace_id VARCHAR(64), sink_kind VARCHAR(16), sink_ref VARCHAR(128),  -- MODEL_PLAN / IM / MAIL / SMS / MCP
  max_label VARCHAR(16) NOT NULL, on_exceed VARCHAR(16) NOT NULL,   -- REDACT_THEN_DENY / REDACT_THEN_APPROVE / DENY
  PRIMARY KEY (workspace_id, sink_kind, sink_ref));
ALTER TABLE kb_base ADD COLUMN data_label VARCHAR(16) NOT NULL DEFAULT 'INTERNAL';
ALTER TABLE kb_document ADD COLUMN data_label VARCHAR(16);
ALTER TABLE mcp_server ADD COLUMN data_label VARCHAR(16) NOT NULL DEFAULT 'INTERNAL';
ALTER TABLE runtime_item ADD COLUMN data_label VARCHAR(16);
ALTER TABLE runtime_session ADD COLUMN data_label VARCHAR(16) NOT NULL DEFAULT 'PUBLIC';
-- AgentSpec 增加 dataClearance（缺省 INTERNAL）；资产类型增加 DATASET_POLICY
```

ID 前缀：`dset` 数据集。新增错误码：`NO_ELIGIBLE_MODEL`、`DATA_LABEL_EXCEEDED`。

## 工作项

| 编号 | 工作项 | 阶段 | 优先级 | 人日 | 依赖 | 验收 |
| --- | --- | --- | --- | --- | --- | --- |
| DAT-01 | 数据集登记、DatasetPolicy、连接器 SDK 行列执行（JDBC 与通用 REST；业务插件随 CON-07） | M3 | P1 | 6 | 通用连接器 SDK | G3：两位不同区域用户问同一问题，得到不同的行与掩码 |
| DAT-02 | 数据标签传播、黑板与对外去向许可 | M3 | P1 | 5 | GOV-10、MAS-01 | G3：CONFIDENTIAL 结果不能原文发到外部群 |
| DAT-03 | 标签 → 可用路由计划 | M3 | P1 | 2 | MH-06 | G3：读到 SECRET 后不再调用未获许可的模型 |

## 修订：记忆与上下文工程调研落地（2026-11-06）

来由：[调研 03](../research/03-Agent记忆与上下文工程调研.md)；按负责人「有选项时按推荐方案直接写设计」的要求落地，见 ADR-029 第 68 条。

- **记忆的可移植与注入**（[ADR-050](ADR-050-memory-system.md) 第 2.3 节）：USER 记忆中只有标签 ≤ `INTERNAL` 的偏好与指令类可跨空间生效，事实类与 `CONFIDENTIAL` 及以上只在来源空间生效；标签高于当前 Turn 可用路由计划许可密级的记忆不注入，避免因记忆导致 `NO_ELIGIBLE_MODEL`。
- 空间记忆与经验库同样只接受空间配置上限（默认 `INTERNAL`）以内的条目，并必须人审。
- 来源 Turn 标签为 `SECRET` 时不抽取记忆。
- 子 Agent `WITH_SUMMARY` 传入模式下，父会话摘要标签超过子授予 `maxDataLabel` 时降为 `ISOLATED`（[ADR-048](ADR-048-context-engineering.md) 第 3.1 节）。

## 修订：文档解析引擎的去向（2026-11-06）

见 [ADR-061](ADR-061-parse-engine-spi-external-ocr.md) 第 5 节与 ADR-029 第 71 条：文档解析引擎实例是一种去向。`PRIVATE` 实例等同私有化模型，`CLOUD` 实例等同云端模型供应商；留存或训练条款未声明的云实例不能处理 `CONFIDENTIAL` 及以上文档，空间管理员填写数据处理协议编号后可放行并记审计；`SECRET` 文档只能用 `PRIVATE` 实例。解析方案中不满足去向的引擎在路由时跳过，整条链不满足时解析失败，不静默降级。

## 修订：可逆假名化（2026-11-07）

见 [ADR-063](ADR-063-guardrail-pipeline-input-safety.md)〜[ADR-065](ADR-065-output-safety-guardrail-eval.md) 与 ADR-029 第 72 条：

- 模型调用去向增加可选处理「可逆假名化」（[ADR-063](ADR-063-guardrail-pipeline-input-safety.md) 第 6.3 节），**缺省关闭**，由空间开启。开启后空间可配置：仅因 PII 被标为 `CONFIDENTIAL` 的内容，假名化后按 `INTERNAL` 判定去向许可；因其他原因达到的标签不受影响。
- 阿里云 AI 安全护栏等外部检测服务是云端去向，受去向许可约束。
- 消息类去向的脱敏改由 `SINK` 检查点的 `pii.detect` 执行，规则不变。

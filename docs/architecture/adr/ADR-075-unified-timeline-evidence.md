# ADR-075 — 统一时间线、运行清单与证据包

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-07
- 覆盖工作项：OBS-06、OBS-08、OBS-12、FE-46、FE-47（新增）；修订 HCI-07、ORC（why 时间线）、FLW-14a、GOV-12 变更记录时间线
- 来由：[调研 09](../research/09-审计与可观测缺口分析.md) 第 3 节 D2、D10，第 6 节，选项 A2；ADR-029 第 75 条
- 相关：[ADR-020](ADR-020-orchestration.md)、[ADR-033](ADR-033-reversible-actions.md)、[ADR-036](ADR-036-business-ontology-domain-pack.md)、[ADR-041](ADR-041-config-versioning-observability.md)、[ADR-048](ADR-048-context-engineering.md)、[ADR-071](ADR-071-feedback-loop-improvement.md)、[ADR-072](ADR-072-audit-event-model-coverage.md)、[ADR-073](ADR-073-correlation-trace-propagation.md)、[ADR-074](ADR-074-external-interaction-ledger.md)、[M2-04](../construction/M2-04-审批台与轨迹.md)、[M3-04](../construction/M3-04-租赁合同风险审查.md)
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。
- 写法说明：只用文字和表格，不附代码。

## 背景

现有三个「时间线」各管一段：Turn 轨迹（HCI-07）、编排 Run 的 why 时间线、配置变更记录时间线（ADR-041）。负责人问能否用 Trace + Timeline 从头串到尾。按 ADR-073，所有记录已带 `request_id` 与 `subject`；本 ADR 定义怎么把它们排成一条可读、可下钻、可导出的时间线，以及 Turn / Run 级的「运行清单」。

## 决定

### 1. 两种视角

| 视角 | 键 | 用户 | 典型问题 |
| --- | --- | --- | --- |
| 请求时间线 | `request_id`（含派生请求，经 `parent_request_id` 展开） | 运维、开发、支持 | 「这次请求为什么慢 / 为什么失败」 |
| 主体时间线 | `subject`（如一份合同、一个知识文档、一个 Agent） | 法务、业务、审计员 | 「这份合同从进来到现在发生了什么、依据是什么」 |

### 2. 时间线条目 `timeline_entry`（投影）

| 字段 | 说明 |
| --- | --- |
| `id`、`occurred_at`、`seq` | `seq` 为来源内序号，用于同一毫秒内排序 |
| `request_id`、`subject`（可多值，另有关联表）、`parent_request_id` | — |
| `category` | `INBOUND`、`RUN`（Turn / Run / Flow 开始结束）、`MODEL`、`TOOL`、`DECISION`（策略、规则求值、护栏）、`APPROVAL`、`CHANGE`（变更单、提交、撤销）、`OUTBOUND`、`NOTIFY`、`FEEDBACK`、`CONFIG`、`SECURITY` |
| `title`、`summary` | 人读的一行与摘要（不含 CONFIDENTIAL 原文） |
| `actor` | 人、Agent（带版本）、系统 |
| `outcome` | OK / DENIED / ERROR / WAITING |
| `source_type`、`source_id` | 指回原记录：`audit_event`、`ext_interaction`、`runtime_item`、`model_usage`、`runtime_approval`、`trust_action`、`orch_decision`、`flow_node_run`、`cfg_change`、反馈项 |
| `trace_id` | 可跳 Jaeger |
| `data_label` | 用于查看权限过滤 |

- **投影，不是事实源**：由一个消费者从各来源的领域事件（经 outbox）异步生成；可按时间段重建；条目本身不进哈希链，校验时回到来源记录。
- 粒度：模型调用、工具调用按「步」合并为一条（展开时再查明细）；Turn 内的文本流不进时间线。
- 配置变更：主体时间线显示「期间生效的配置版本变化」——按该主体涉及的 Agent、别名、要点规则、授权书，在时间范围内查 `cfg_change`，作为背景条目（灰色）插入。

### 3. 运行清单 `run_manifest`

每个 Turn、编排 Run、Flow Run 开始时写一条（续跑时如有变化追加一条）：

| 内容 | 来源 |
| --- | --- |
| Agent 版本与 spec 哈希、Skill 版本列表、工具快照哈希 | AgentSpec（AST-01） |
| 授权书版本、Cedar 策略集版本、授予 ID 与链、自治等级 | ADR-032、GOV-07、ADR-039、ADR-034 |
| 护栏策略版本、数据集策略版本 | ADR-065、ADR-040 |
| 别名与路由计划版本（开始时的解析结果；单次调用的实际 binding 仍以 `model_usage` 为准） | ADR-041 |
| 要点规则包与 Schema 版本（业务场景） | ADR-036、ADR-037 |
| 关联键 | ADR-073 |

清单进 ADR-072 的哈希链；时间线中 `RUN` 条目可展开查看清单，并与上一次同类运行做差异对比（回答「为什么昨天放行、今天拦截」）。

### 4. 查询与界面

- 接口：按 `request_id` 或 `subject` 分页取条目（游标），按类别过滤，下钻取来源记录；按权限过滤（`data_label`、空间、`trajectory:read:content`）。
- 界面（FE-46，M3）：
  - 合同详情（业务系统内嵌或 Tioka 管理端）「审查过程」页：纵向时间线，分组显示每一轮审查；条目可展开到轨迹视图、审计详情、外部交互详情、Jaeger。
  - 请求时间线：管理端输入 `request_id`、对方单号（经 ADR-074 反查）或 trace_id 进入。
  - 现有入口的跳转：轨迹视图、审批详情、Run 详情、审计记录都加「查看完整时间线」。
- M4 扩展（FE-47）：Agent 主体时间线（版本、上线阶段、评测、处置统计）、知识文档主体时间线（入库、解析、版本、被引用）、用户主体时间线（管理员调查用，需要 `audit:read:all`）。

### 5. 证据包（M4）

- 对一个 `subject` 或 `request_id` 导出证据包：时间线、相关审计、外部交互、运行清单、审批与处置、相关配置版本，以及对应的哈希链封印记录与校验结果；打包后对包整体计算哈希并由平台密钥签名。
- 导出写审计（`timeline.evidence.export`），受 `audit:export` 权限控制；包内 CONFIDENTIAL 内容按导出人权限决定是否包含。
- 用途：内部审计、纠纷举证、监管检查。

### 6. 与现有设计的关系

| 现有 | 处理 |
| --- | --- |
| HCI-07 轨迹视图 | 保留，作为时间线中 Turn 条目的下钻页 |
| 编排 Run 的 why 时间线 | 保留为 Run 详情内视图；`orch_decision` 同时投影到时间线 |
| ADR-041 变更记录时间线 | 保留；配置对象本身作为 `subject`（如 `model.alias:sys.chat`）也可用统一时间线查看 |
| Jaeger | 时间线条目带 `trace_id` 跳转；不再要求业务人员使用 Jaeger |

## 后果

- 合同场景影子期即可逐份复盘；纠纷与监管可一键导出证据。
- 代价：M3 运行清单 2、时间线投影与查询 5、前端 5 人日；M4 证据包与全量主体 4、前端 3 人日。

## 未采用的备选

- 时间线直接按关联键联合查询多张表、不建投影：开发快，但跨 8〜10 张表的分页排序复杂、慢，且权限过滤难统一；作为排期备选 D2 的 M3 最小版。
- 外部事件平台（A3）：私有化部署成本高。

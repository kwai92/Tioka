# ADR-025 — Governance 余项

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：GOV-06〜11（GOV-05 成本归集见 ADR-024）
- 对应设计：设计 K
- 阅读版：[Tioka 推进规划与详细设计](https://claude.ai/artifact/V3wtabWCVSwAC3ewNerFNi)
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

覆盖 GOV-06〜11（GOV-05 成本归集已在设计 J）。核心决定是：**治理能力全部挂在已有的卡点和表上，不新增执行路径**。发布审批复用通用审批表，策略引擎挂在 ToolGateway，内容安全挂在 Runtime 的输入输出处，多环境复用空间。

## 资产发布审批（GOV-06）

- 空间按资产类型配置是否需要审批和审批角色。类型包括 Agent、Skill、Flow 发布、MCP Server、路由计划、市场上架。
- 发布时版本进入 `PENDING_APPROVAL`，写一条通用审批（subject\_type=asset\_version），审批人在审批台看到与上一版本的 diff。
- **执行侧只认 PUBLISHED 版本**：Runtime 创建 thread 时、ToolGateway 解析工具时都校验状态，满足验收「未审批的资产无法被会话使用」。

## 工具调用策略引擎（GOV-07）

选用 Cedar（`cedar-java`，进程内求值，不需要额外部署服务）。ToolGateway 在权限检查之后、审批判定之前求值：

| Cedar 要素 | 取值 |
| --- | --- |
| principal | 用户或服务账号，带角色与部门属性 |
| action | 工具名，如 `Action::"biz.erp.createOrder"` |
| resource | 工具及其参数中声明为可策略化的字段（如金额、目标路径） |
| context | Agent、空间、会话来源（web / im / orch）、时间 |

结果三种：允许、拒绝、转人工审批。RBAC 是基线，策略只能收紧不能放宽。策略按租户和空间版本化，新策略先在「仅记录」模式跑一段时间，确认误拦率后再切换到「强制」。每次拒绝都写审计，带命中的策略 ID。`cedar-java` 依赖本地库，需确认目标部署架构（x86 / ARM）都有可用构建。

## 可观测（GOV-08）

- 用 OpenTelemetry SDK，span 层级：HTTP 请求 → Turn → Step → 模型调用 / 工具调用；Flow 的节点运行、编排 Run 作为独立 trace 并链接到它们触发的 Turn。
- span 属性只带 ID、模型、token 数、耗时、结果；**默认不带提示词和工具参数正文**，需要时按空间开启并经内容安全脱敏。
- 指标走 Micrometer → Prometheus：Turn 耗时与排队长度、模型错误率、工具错误率、配额拒绝数、outbox 积压。
- 用 OTLP 导出到客户的采集器；Helm chart 可选带一套默认后端。自建的 `trace_span` 表废弃。`audit_event` 和 `model_usage` 都记录 traceId，可互相跳转。

## 审计防篡改与外送（GOV-09）

写审计时不算哈希，避免写入串行化。一个封印任务每分钟按 ID 顺序为新记录计算 `hash = sha256(prev_hash + 规范化后的记录)`，每个租户一条链，并把每批的末尾哈希写入封印表（可同时外送到 SIEM，作为外部锚点）。提供校验接口重算整条链。外送经 `AuditSinkAdapter`（syslog、HTTP、Kafka），从 outbox 投递，客户 SIEM 不可用时不影响业务。

## 内容安全（GOV-10）

`ContentSafetyHook` SPI 挂在三个点：用户输入进入 Turn 前、模型输出 item 完成时、工具参数发往外部系统前。动作：放行、脱敏、拦截。流式输出无法事前审查，所以在 item 完成时检查，命中拦截则发 `item.discarded` 并替换为提示（与定案 2 同一机制）。内置正则 PII 脱敏（身份证、手机号、银行卡、邮箱），客户自有审核服务以适配器接入。

## 多环境（GOV-11）

**环境就是带标签的空间**：空间增加 `env`（dev / staging / prod）和「晋升到哪个空间」。晋升 = 把某个资产版本的快照复制到目标空间，按名称重新绑定引用（路由计划、MCP、知识库），绑定不到则拒绝，并走目标空间的发布审批。这样不用在每张业务表加环境字段。

## 数据模型

```sql
CREATE TABLE gov_publish_policy (workspace_id VARCHAR(64), asset_kind VARCHAR(16), require_approval BOOLEAN NOT NULL,
  approver_role VARCHAR(64), PRIMARY KEY (workspace_id, asset_kind));
CREATE TABLE gov_policy (id VARCHAR(32) PRIMARY KEY, tenant_id VARCHAR(32) NOT NULL, workspace_id VARCHAR(64),
  version INT NOT NULL, cedar_text TEXT NOT NULL, mode VARCHAR(8) NOT NULL,     -- DRYRUN / ENFORCE
  status VARCHAR(16) NOT NULL, created_by VARCHAR(32), created_at TIMESTAMPTZ NOT NULL);
CREATE TABLE gov_audit_seal (tenant_id VARCHAR(32), upto_id VARCHAR(32), chain_hash VARCHAR(64) NOT NULL,
  sealed_at TIMESTAMPTZ NOT NULL, PRIMARY KEY (tenant_id, upto_id));
CREATE TABLE gov_safety_policy (workspace_id VARCHAR(64), hook_point VARCHAR(16), adapter VARCHAR(64) NOT NULL,
  action VARCHAR(8) NOT NULL, config_json TEXT, PRIMARY KEY (workspace_id, hook_point, adapter));
ALTER TABLE sys_workspace ADD COLUMN env VARCHAR(16) NOT NULL DEFAULT 'prod';
ALTER TABLE sys_workspace ADD COLUMN promotes_to VARCHAR(64);
-- 通用审批表的 subject_type 增加 asset_version
```

## 修订：可信放权（2026-11-05）

- GOV-07 提前到 M3 并升为 P0（ADR-029 第 39 条）。策略结果在 ALLOW / DENY 之外正式增加 ESCALATE（转审批），用 `@effect` 注解区分两组 forbid 策略。
- 岗位授权书（[ADR-032](ADR-032-agent-charter.md)）发布时编译成 Cedar 策略写入 `gov_policy`，`source = charter:{agentVersionId}`。
- resource 优先取预演变更单的汇总（金额、记录数、对象类型），见 [ADR-033](ADR-033-reversible-actions.md) 的工具调用管线。
- GOV-11 多环境延后到 M4 之后（ADR-029 第 42 条）。
- Cedar context 增加委派链 `chain`、`depth`、`onBehalfOf`（[ADR-039](ADR-039-delegation-grant.md)，ADR-029 第 46 条）；行列级数据权限用 CEL，在连接器执行（[ADR-040](ADR-040-dataset-policy-data-label.md)，第 48 条）。
- GOV-10 的内置正则 PII 脱敏提前到 M3，供数据标签的对外去向许可使用（ADR-029 第 49 条）。

## 修订：管理面版本化与可观测（2026-11-06）

见 [ADR-041](ADR-041-config-versioning-observability.md) 与 ADR-029 第 62 条：

- GOV-06 的资产类型增加供应商、binding、别名、Prompt 模板。
- GOV-08 的模型与配额指标以 ADR-041 第 5.2 节为准（含基数规则）；不依赖 Prometheus 的内置运行状态见 MH-10。
- 新增 GOV-12（统一配置版本框架）与 GOV-13（告警规则与通知）。

## 修订：安全护栏（2026-11-07）

见 [ADR-065](ADR-065-guardrail-pipeline-input-safety.md)〜[ADR-067](ADR-067-output-safety-guardrail-eval.md) 与 ADR-029 第 73 条：

- `ContentSafetyHook` 的三个挂点并入 [ADR-065](ADR-065-guardrail-pipeline-input-safety.md) 的护栏管线（`IN`、`OUT`、`TOOL` / `SINK`），动作「放行 / 脱敏 / 拦截」对应 `ALLOW` / `REWRITE` / `BLOCK`，另增 `FLAG`、`ESCALATE`。
- `gov_safety_policy` 由版本化配置 `guardrail_policy` 取代（ADR-041 资产类型），迁移按 `(workspace_id, hook_point)` 映射。
- GOV-10 的内置正则仍随 DAT-02 在 M3 交付；M4 的 GRD-04 替换为带校验位的识别器，并增加可逆假名化（缺省关闭）与密钥检测。
- 输出渲染安全、生成内容标识与护栏评测见 [ADR-067](ADR-067-output-safety-guardrail-eval.md)。

## 修订：审计、关联与时间线（2026-11-07）

见 [ADR-072](ADR-072-audit-event-model-coverage.md)〜[ADR-075](ADR-075-unified-timeline-evidence.md) 与 ADR-029 第 75 条：

- GOV-08：`audit_event`、`model_usage` 之外的记录表也带关联键；除 `trace_id` 外增加 `request_id`、`subject`；采样改为 Collector 尾部采样，日志聚合缺省 Loki（[ADR-073](ADR-073-correlation-trace-propagation.md)）。
- GOV-09：提前到 M4 前段、P1；链覆盖审计、外部交互台账、运行清单；应用账号对这些表只有 INSERT 与 SELECT；外部锚点增加对象存储；保留与归档按 ADR-072 第 7 节。
- 跨系统往来统一记入外部交互台账（[ADR-074](ADR-074-external-interaction-ledger.md)）；按请求与业务主体的时间线见 [ADR-075](ADR-075-unified-timeline-evidence.md)。

## 修订：施工设计评审（2026-11-11，ADR-029 第 79、81 条）

见 [36 号评审](../implementation/36-M3M4施工设计多维度评审.md)。本 ADR 原文与以下各条冲突时，以下列为准：

- GOV-09：v1 链（V219，按 ID 顺序、按空间分链）已在 P3 批次实现。M4 起改为独立的只增链表，按时间窗口封印，无空间维度；v1 冻结，v2 以创世记录接续（M4-06 修订节）。原文「封印任务按 ID 顺序」「每租户一条链」只适用于 v1。
- GOV-06：版本状态增加 `GATING`（评测门禁运行中）与 `CANDIDATE`。发布前置步骤的编排器放在 `governance-api`，Agent、Flow、Skill、MCP 的发布入口都调用它（M4-01 修订节）。
- GOV-06：放宽类操作（关闭某类资产审批等）需要申请人加安全管理员两人，申请人不能自审；MCP Server、模型 binding、Skill 有平台级「必须审批」下限。
- `gov_safety_policy` 从未建表，由 `guardrail_policy` 取代（ADR-065）。

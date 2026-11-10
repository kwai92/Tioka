# M3 批次 C-5：数据集与行列策略 + 标签传播与去向许可 + 按标签选模 + Flow 授予透传实现覆盖说明

> 批次：M3 批次 C-5（M3-02 DAT-01/02/03/04，ADR-040「数据模型」+ 2026-11-07 修订 + ADR-029 第 47/48/49 条）
> 分支：`feature/m3-c5-dat`（基线 main = `84e8a55`，worktree `p3-c5-dat`）
> 收口范围：迁移 V163（数据集与策略）/V164（标签传播与去向）/V165（Flow 授予）；DatasetPolicyCompiler（CEL 子集白名单）/DatasetGateway/data.query 内置工具/AgentSpec datasets + dataClearance 解冻/PublishValidator SERVICE 模式 ENFORCE；gov_label_sink + 五表 data_label/TurnLabelTracker MONOTONIC/DefaultSinkGuard 四 verdict/GOV-10 内置正则/IM+KB+MCP 出口挂接；plan 级标签闸 + LabelModelPlanFilter/NO_ELIGIBLE_MODEL(42210)+DATA_LABEL_EXCEEDED(40332)；V165 flow_run.grant_id + flow_publication.required_capabilities_json/CapabilityCollector/启动前授予检查/节点 grant 透传
> 硬约束遵守：workspaceId 权威（data_dataset/data_dataset_policy/gov_label_sink 均带 workspace_id，data_dataset_policy 复合主键表经 ScopeLine exempt 登记）；不引新运行时依赖（dev.cel 0.9.1 版本入 tioka-dependencies 统一管理）；不改 deploy/compose.yml；MQ 信封契约不动；业务 ID 用 `Ids.newId`；ArchUnit 既有规则套件全过（本批修正三处违规后归零）

## 一、迁移计划

| 号 | 内容 | 破坏性 | 备注 |
|------|------|--------|------|
| V163 | `V163__m3_dataset_policy.sql`：CREATE TABLE data_dataset（id dset_ 前缀/workspace_id/connector_id/name/source_json/columns_json/owner_id/status，UNIQUE(workspace_id,name)）；data_dataset_policy（PK(dataset_id,version)，rows_json/cols_json，mode DRYRUN·ENFORCE）；idx_data_dataset_ws；契约副本 `docs/contracts/db/` | 否 | 策略随数据集版本化；运行期取最高 ACTIVE 版本 |
| V164 | `V164__m3_label_sink.sql`：CREATE TABLE gov_label_sink（PK(workspace_id,sink_kind,sink_ref)，max_label/on_exceed REDACT_THEN_DENY·REDACT_THEN_APPROVE·DENY）；`ALTER TABLE` kb_base/kb_document/mcp_server/runtime_item/runtime_thread ADD COLUMN data_label（runtime_thread NOT NULL DEFAULT 'PUBLIC'）；契约副本 | 否 | 表名按 ADR-040 数据模型为 runtime_thread（非 runtime_session，见偏差 1）；runtime_item 为行级标签、runtime_thread 为 Turn 级 MONOTONIC 汇聚 |
| V165 | `V165__m3_flow_grant.sql`：`ALTER TABLE` flow_run ADD COLUMN grant_id；flow_publication ADD COLUMN required_capabilities_json；契约副本 | 否 | Flow 以 Agent 工具形态被调用时写入授予快照（ADR-029 第 47 条） |

## 二、范围 → 实现映射

### DAT-01 数据集与行列策略（V163）

| 项 | 实现 |
|------|------|
| framework | `DataLabel`（既有）：PUBLIC < INTERNAL < CONFIDENTIAL < SECRET（max/min/atMost）；`ErrorCode` 新增 `DATA_LABEL_EXCEEDED(40332, 403)` / `NO_ELIGIBLE_MODEL(42210, 422)` |
| connector-api | `dataset/`：`ColumnSpec`（name/type/label/defaultMask + Mask 枚举 NONE/PARTIAL/HASH/NULLIFY/DROP）；`DatasetDescriptor`；`DatasetQuery`（limit ≤ 1000 归一）；`DatasetResult`（rows/label/maskedColumns/postFiltered）；`PlannedQuery`（whereSql+params / restParams / postFilter / selectColumns / postFilterNeeded / masks 七组件）；`spi/DatasetSource`（datasetId/describe/execute）；`DatasetPolicyQueryApi`（exists/hasEnforcePolicy） |
| CEL 编译 | `DatasetPolicyCompiler`（connector-biz）：白名单 = row.col / principal.attrs.* / principal.roles 引用、字面量、`_==_ _!=_ _<_ _<=_ _>_ _>=_ @in _&&_ _||_ !_ startsWith`（cel-java 0.9.1 运算符函数名带下划线包裹，javap + AST 探针实证）；`validate` 编译期 AST 遍历拒绝白名单外语法；`compile` 按伪码 ①②③④：rows 命中 when 的 predicate 取 OR、一条不中→`1 = 0` 零行默认拒绝；AND 用户 filter AND grant.scope 同名列常量 IN；principal.* 编译期代入常量（参数绑定）；列取 q.columns ∩ 描述列、DROP 剔除；JDBC 模式生成参数化 WHERE（`t."col"` 引用，零字面量拼接）；REST 模式等值下推 restParams + 不可下推部分 postFilter 运行期求值 |
| 掩码 | `ColumnMasker`（@Component，tenantSalt 经 `tioka.connector.dataset.tenant-salt` 配置）：PARTIAL 字符串前 3 后 4 中间 *（≤7 位全 *）/数字首 2 位量级（partialFor 按 int/decimal/numeric 分派）；HASH sha256(tenantSalt+value) 前 16 位；NULLIFY/DROP 属编译期与网关语义 |
| 网关 | `DatasetGateway`（唯一入口）：ACL（store.find 按 workspaceId）→ 策略编译 → 执行（静态注册源优先，kind=jdbc 经 `JdbcDatasetSourceFactory` 动态构建）→ 掩码（maskedColumns 只记列名）→ 打标签（= select 列密级最高值）→ 审计（AuditApi 可缺省；dryrunWouldFilter/filteredRows 分列）；DRYRUN 行过滤只记录不拦截、ENFORCE 拦截（登记简化，见偏差 8） |
| JDBC 源 | `jdbc/JdbcDatasetSource` 实现 DatasetSource：SELECT/WITH-only + 禁分号多语句 + 禁注释夹带 + 禁写关键字；`SELECT {cols} FROM ({source.sql}) t WHERE {whereSql} LIMIT ?`；**JDK PreparedStatement 参数绑定，不依赖 spring-jdbc**（PersistenceArchTest#noSpringJdbcInProductionCode 硬规则，见偏差 9） |
| 内置工具 | `DataQueryToolProvider`（connector-biz，ToolProvider）：data.query(dataset/filter/columns/limit)，经 ToolCallContext.grantId → scope 取授予；结果带 DatasetResult.label（落 runtime_item.data_label 传播链） |
| 发布校验 | `agent-api` AgentSpec 尾加 `List<String> datasets`（18 参兼容构造）；`agent-spec-v1.json` dataClearance 改四档 enum + datasets 数组；`SchemaPublishCheck` 解冻 dataClearance（RESERVED_FIELD 检查解除 → 合法枚举校验）+ datasets 条目非空；`DatasetPublishCheck`（@Order(21)）：所有模式查存在性（REF_NOT_FOUND）、SERVICE 模式（charter.actingAs=SERVICE）须 ENFORCE（SERVICE_MODE_NEEDS_POLICY，ADR-040「执行」节：SERVICE 模式数据集策略是唯一行级防线）；回滚发布同样执行 |

### DAT-02 标签传播与去向许可（V164）

| 项 | 实现 |
|------|------|
| 传播链 | `ToolResult`（runtime-api）尾加 `String dataLabel`（2/3 参兼容构造 + withDataLabel）→ `GatewayToolPort.invokeOne` 透传 → `ToolOutcome` 尾加第 8 组件 → `MemoryItemSink.toolResult`：item 落库（RuntimeItemDO/RuntimeItemMapper.xml data_label 列）+ `TurnLabelTracker.record`；`ItemWrite` 尾加第 10 组件 + `appendLabeled` 工厂；`ItemRepository.appendWithoutLease/newItem` 透传 |
| MONOTONIC | `TurnLabelTracker`（runtime-biz）：`record(threadId, labelRaw)` 只升不降（max 语义，null/非法值忽略、threadId 缺失返回 PUBLIC）；`current()` 未设置 = PUBLIC；写库仅在实际提升时（`ThreadLabelStore.update`） |
| 子 Agent 回流 | `SubAgentReaper.onChildTerminal`：子 thread 终态标签回流父 thread（经 parentTurn.threadId），同样 MONOTONIC |
| 标签读取 | `RuntimeTurnLabelLookup`（runtime-biz）实现 model-api `TurnLabelLookup.labelOf(threadId)`（读 runtime_thread.data_label）；`RuntimeThreadLabelStore`（infrastructure/label，@Repository 直持 RuntimeThreadMapper——application/kernel 禁持 Mapper 规则落位 infrastructure，见偏差 10） |
| 去向许可 | `DefaultSinkGuard`（governance-biz，@Autowired 消歧多构造）：rule(ws,kind,ref) ?? rule(ws,kind,'*') ?? 缺省；缺省规则 `tioka.governance.label-sink-defaults.<KIND>`（M3-02 原文）：MODEL_PLAN=INTERNAL、IM=CONFIDENTIAL（ref 级外部群收紧由空间配置行承载）、MAIL/SMS/MCP/BIZ/FILE_EXPORT/OTHER=INTERNAL；缺省 on_exceed=DENY；REDACT 路径内联调 `RegexPiiHook`（脱敏后无命中 → REDACTED，仍命中 → DENY/APPROVE，见偏差 5）；`SinkCheckResult` 加 redacted(payload,reason)/requireApproval 工厂 |
| GOV-10 正则 | `RegexPiiHook`（governance-biz，静态工具）：身份证 18 位（19/20 世纪 + 月日格式 + 校验位 X）、手机号 1[3-9] 11 位、银行卡 13〜19 位 + Luhn 校验、邮箱四类；`redact()` 全类替换 `[已脱敏]`、`matches()`/`hitCounts()` 供脱敏判定与审计计数（M3-02/ADR-040 未冻结具体正则表，四类为内置实现，见偏差 4） |
| IM 出口 | `ImSendConsumer` 加 ObjectProvider&lt;SinkGuard&gt;：payload.dataLabel 缺省 INTERNAL（IM 事件信封无标签位，登记偏差 6）；permitted()：PASS 放行 / REDACTED 发脱敏文本 / DENY 返回 null 丢弃；sendLocally 同样检查 |
| KB 出口 | `knowledge-biz`：KbBaseDO/KbDocumentDO 加 dataLabel；`KbLabels` 纯函数（parse 回退 / effective = max(kb 缺省 INTERNAL，doc null 继承) / readable = effective ≤ maxDataLabel）；`KnowledgeService.retrieve` 加 maxDataLabel 4 参重载（fused 流 limit 前过滤）+ `effectiveLabel(kbId,docId)`；`KbToolProvider` 重写 ctx 5 参 invoke：grant.maxDataLabel → retrieve 过滤，结果带命中最大有效标签 |
| MCP 出口 | `McpServerDO` 加 dataLabel；`McpStore.dataLabelOf(serverId)`；`McpToolProvider` 重写 ctx 5 参 invoke：`base.withDataLabel(store.dataLabelOf(serverId))`（MCP→SinkGuard 检查在 runtime 管线步骤统一执行，mcp-biz 无需依赖 governance-api） |
| 管线步骤 | `SinkGuardPipelineStep`（runtime-biz，order=EGRESS_PERMIT=12.5，既有 ToolPipelineOrders 预留位）：MCP→SinkKind.MCP/ref=serverId（经 `ToolRegistry.mcpServerIdOf`）否则 BIZ/toolName；DENY→BizException(DATA_LABEL_EXCEEDED)；REDACTED→`ATTR_REDACTED_ARGS = "egress.redactedArgs"`，`DefaultToolGateway.prepare` 管线后重建 inv/changeSet（M3-02 原文「ToolGateway commit 第 2 步」口径） |
| 授予补标 | `GrantAgentViews.parseLabel`：dataClearance 非法值保守回退 INTERNAL（M3-02 DAT-01 解冻口径「缺省 INTERNAL」） |

### DAT-03 按标签选模

| 项 | 实现 |
|------|------|
| plan 级闸 | `PlanLabelGate`（model-biz）：currentLabel（lookup 缺失/未知 thread = PUBLIC）+ allowed（SinkGuard 缺失 = 放行；check(MODEL_PLAN, planId, label) 非 PASS 一律不可选——M3 口径 REDACT_THEN_* 不放行，提示词假名化/审批处理在 M4 护栏管线，见偏差 7）+ assertPlanAllowed（不 PASS → BizException NO_ELIGIBLE_MODEL）；`RoutingPlanExecutor.select()` 在 legacy 检查后接闸（构造尾加参，null 兜底兼容既有装配） |
| binding 级兜底 | `LabelModelPlanFilter` 实现 ModelBindingFilter（MH-06 预留位落位）：sinkRef="*" 检查；剔除时经 RoutingTelemetry.recordEvent 记 KIND_FAIL/mode=LABEL/reason "40332: DATA_LABEL_EXCEEDED" |
| SPI | `model-api/label/TurnLabelLookup`：model-biz 不读 runtime 表，实现由 runtime-biz 装配（读 runtime_thread.data_label） |

### DAT-04 Flow 授予透传（V165）

| 项 | 实现 |
|------|------|
| 能力收集 | `CapabilityCollector`（flow-biz，静态工具）：Retrieve→kb(config.kbId)/MCP→mcp(config.serverId)/Agent→agent(config.agentId)；RequiredCapabilities(tools/kb/mcp/agents) + toJson/fromJson（ObjectMapper 参数化，ENG-05 禁 new ObjectMapper，见偏差 11） |
| 授予检查 | `FlowCapabilityChecker`：missing()（grant null / 非 ACTIVE → 全部缺失；tools 直查 + resources kb/mcp/agent 段；`*` 通配段兜底）+ assertWithinGrant（超授予 FORBIDDEN）+ resourceGranted（节点级 kb/mcp/agent） |
| 发布 | `FlowPublicationService.publishAsTool`：collect → `flow_publication.required_capabilities_json`（repo.upsertToolPub 3 参重载）；`invoke(ctx,…)`：grantId → GrantService.get → fromJson → assertWithinGrant（超授予返回失败）→ `flowService.run(..., grantId)` |
| 运行 | `FlowRunDO` + grantId（V165）；`FlowService.run`/`FlowEngine.run` 加 grantId 重载；`NodeExecContext` 尾加 grantId；`McpNodeExecutor`（ToolCallContexts.forTurn 9 参透传）与 `RetrieveNodeExecutor`（构造加 ObjectProvider&lt;GrantService&gt;；kbId ∉ grant.resources.kb → error 返回）实现节点级授予透传 |

## 三、偏差登记

1. **runtime_thread 表名更正**：任务简报与实现过程沿用 runtime_session 漂移名，ADR-040 数据模型（2026-11-07 修订）冻结事实为 `runtime_thread`；V164 ALTER 与传播链落位 runtime_thread。
2. **邮件去向缺载体**：M3 无 IM 类似的 mail 发送出口实现（connector 无 MAIL channel），SinkKind.MAIL 仅保留规则语义与缺省值；MAIL 出口的 SinkGuard 挂接随邮件通道批次补齐。
3. **SinkGuard 挂接点**：MCP / BIZ 工具出口统一挂在 runtime 管线 EGRESS_PERMIT 步骤（SinkGuardPipelineStep，order 12.5），而非各 Provider 内部；IM 出口因不经工具管线在 ImSendConsumer 内直调（M3-02 原文「ToolGateway commit 第 2 步」口径映射到既有管线序号）。
4. **GOV-10 正则表未冻结**：M3-02/ADR-040 只冻结「内置正则」机制，未冻结具体类别；按国内高频场景内置身份证/手机号/银行卡（Luhn）/邮箱四类，类别扩展点为 RegexPiiHook 静态表。
5. **REDACT 判定简化**：M3-02 伪码「label 仅因 PII 超限」不可从单条 payload 判定；实现口径统一为「脱敏后无 PII 命中 → REDACTED（无论原 payload 是否含 PII），仍命中 → 回落 DENY/APPROVE」——DefaultSinkGuard 行为与 SinkGuardIT 测试对齐。
6. **IM 标签位预留**：IM 事件信封 payload 无 dataLabel 字段（跨域契约不动），ImSendConsumer 按「缺省 INTERNAL」读取；信封加标签位后改为透传（生产链路标签位登记）。
7. **MODEL_PLAN REDACT 处理**：REDACT_THEN_* 对 MODEL_PLAN 一律视为不可选（PlanLabelGate 只认 PASS）——提示词假名化 / 审批处理在 M4 护栏管线；本批 plan 级过滤语义即「当前标签超出许可的模型不可选」。
8. **DRYRUN 列掩码仍执行**：M3-02 DRYRUN 只描述行过滤不拦截；实现取「行过滤记录 wouldFilter 不拦截，列掩码照常执行」（掩码是出参形态的一部分，DRYRUN 语义无法跳过）。
9. **JDBC 源改 JDK PreparedStatement**：PersistenceArchTest#noSpringJdbcInProductionCode 硬禁止 org.springframework.jdbc..，JdbcDatasetSource 从 JdbcTemplate 改为 DataSource + JDK PreparedStatement/ResultSet（参数化绑定语义不变，连接池由 DataSource 承载；connection 短持有即取即还）。
10. **RuntimeThreadLabelStore 落位 infrastructure**：application/kernel 禁直持 Mapper（PersistenceArchTest#applicationAndKernelMustNotDependOnMappers），存取实现落 `runtime-biz/infrastructure/label/`，application 层仅留 ThreadLabelStore 端口。
11. **CapabilityCollector JSON 参数化**：ENG-05（TiokaArchitectureTest#noNewObjectMapper）禁 new ObjectMapper；静态工具改为 toJson/fromJson 带 ObjectMapper 参数，调用方（FlowPublicationService）传入注入实例。
12. **agentNodeCountsDepth+ / runs API 后置**：简报缺口②与 agent 联动统计（agentNodeCountsDepth 加深）/ Flow runs REST API 属 DOC/PRS 面，按简报口径后置，本批未动。
13. **principal.attrs 增量同步（EIS）后置**：compile 期 principal 属性来自 GrantService 快照；attrs 与身份域的增量同步归后续批次。
14. **data.query 落 connector-biz**：简报原定 runtime-biz，与 ModuleDependencyArchTest 禁 runtime-biz → connector 冲突；参照 DocToolProvider（ontology-biz）先例落 connector-biz，runtime-api 仅被依赖（无环）。connector-biz pom 相应加 governance-api / runtime-api / dev.cel。
15. **CEL 自带 dev.cel**：CelRuleCondition 在 orchestration-biz，biz→biz 不建依赖；connector-biz 直接依赖 dev.cel 0.9.1（版本 tioka-dependencies 统一管理，与 orchestration-biz 对齐）。

## 四、测试清单

| 测试 | 结果 | 覆盖 |
|------|------|------|
| `RegexPiiHookTest`（governance-biz，纯函数） | 13/13 | 四类 PII 各 5 正例 5 反例（身份证 19/20 世纪格式 + 月日校验；手机号段/长度/连写；银行卡 Luhn 有效/失败/长度/连写/连字符；邮箱 TLD/双 @/下划线）；redact 全类替换计数；hitCounts 分类计数（Luhn 失败不计）；luhn 边界；null/空安全 |
| `SinkGuardIT`（governance-biz，离线） | 9/9 | 四 verdict（PASS/DENY/REDACTED/REQUIRE_APPROVAL）；REDACT 脱敏放行（多类替换 + 计数）；脱敏后仍敏感回落 DENY/APPROVE（identity redactor 注入）；缺省规则（IM=CONFIDENTIAL、其余 INTERNAL）；ref 精确→通配→缺省三级回落；无 Principal 走缺省；null label PASS |
| `DatasetPolicyCompilerTest`（connector-biz，纯函数） | 9/9 | 白名单合法子集 8 形态；白名单外拒绝（函数调用/字符串拼接/裸标识符/未知根/语法错误/空表达式）；JDBC 编译参数化 + principal 编译期代入 + grantScope IN 下推；when 全不命中零行 `1 = 0`；列交集 + DROP 剔除 + 未知列拒绝；REST 等值下推 + postFilter 命中/未命中；effectiveMasks 后规则覆盖 + 未知列拒绝；evalWhen 空白/求值；limit 归一 |
| `ColumnMaskerTest`（connector-biz，纯函数） | 5/5 | PARTIAL 前 3 后 4（≤7 位全 *）/数字量级/partialFor 类型分派/null spec；HASH 16 位 hex 稳定 + salt 敏感 + 缺省 salt；copyOf 独立副本 |
| `DatasetGatewayIT`（connector-biz，离线内存源） | 6/6 | 双区域行隔离（区域主体只见本区域行；另一主体见对应区域）；列掩码 PARTIAL/HASH + maskedColumns 记录 + 结果标签取 select 列最高密级；NULLIFY/DROP；filter 注入三形态拒绝 + 合法 filter 参数化执行；REST postFilter 事实标记 + ENFORCE/DRYRUN 双模式（DRYRUN 不拦截行、掩码仍执行）；数据集/源缺失 NOT_FOUND |
| `TurnLabelTrackerIT`（runtime-biz，离线） | 4/4 | MONOTONIC 提升（写库仅实际提升时）；非法/空/缺 threadId 忽略；大小写空白解析 + 存量脏值视为 PUBLIC；thread 隔离 |
| `PublishValidatorIT`（agent-biz，离线） | 5/5 | 无 datasets 通过；REF_NOT_FOUND（含 path /datasets/1 定位）；SERVICE 模式 ENFORCE 强制（SERVICE+DRYRUN 拒 / SERVICE+ENFORCE 过 / DELEGATED+DRYRUN 过 / 回滚同查）；api 缺省 REF_UNAVAILABLE；SERVICE+ENFORCE 正常形态 |
| `KbLabelIT`（knowledge-biz，纯函数） | 5/5 | parse 回退（null/空/非法/大小写）；effective kb 缺省 INTERNAL（显式 PUBLIC 按显式值）+ doc null 继承 + max 取值；readable 过滤（null 不过滤旧口径/相等/超出） |
| `LabelRoutingIT`（model-biz，Mockito） | 4/4 | plan 级闸：SECRET 超 CONFIDENTIAL 许可 → NO_ELIGIBLE_MODEL；currentLabel（未知 thread/非会话 = PUBLIC）；无 lookup/无 SinkGuard 放行（RoutingPlanExecutor 以 currentLabel 入参的装配语义）；binding 级剔除 + DATA_LABEL_EXCEEDED 事件（KIND_FAIL/mode=LABEL/reason 40332）；错误码注册值（42210/40332） |
| `FlowGrantIT`（flow-biz，纯函数） | 3/3 | rejectAtStart：授予内放行 / 工具超授予 FORBIDDEN + 缺失清单（tool/kb/mcp/agent 四段）/ grant null·非 ACTIVE 全缺失 / 空 caps 恒放行；nodesUseCallerGrant：resources 段 + `*` 通配 + 非 ACTIVE 拒绝 + 空白 ref 不校验；CapabilityCollector 图遍历（Retrieve/MCP/Agent/LLM/空 config）+ JSON 往返 + 空图/脏 JSON 回落 |
| 既有回归（适配构造变化） | 全绿 | ReaperIT（SubAgentReaper + TurnLabelTracker）/ KbToolProviderTest（+ObjectProvider mock）/ FlowEngineTest（RetrieveNodeExecutor provider）/ RoutingGatewayIntegrationTest / RoutingTestHarness（RoutingPlanExecutor 尾参）/ DatasetGatewayIT 网关构造（+JdbcDatasetSourceFactory） |
| governance-biz 全量 surefire | **63/63** | 含 RegexPiiHookTest 13 / SinkGuardIT 9 与既有套件（M3-C4 时 41 + 本批 22） |
| runtime-biz 全量 surefire | **102/102** | 含 TurnLabelTrackerIT 4（M3-C4 时 98 + 本批 4） |
| connector-biz 全量回归 | **50/50** | 含 DatasetPolicyCompilerTest 9 / ColumnMaskerTest 5 / DatasetGatewayIT 6（批次前 30 + 本批 20） |
| knowledge-biz 全量回归 | **14/14** | 含 KbLabelIT 5 |
| agent-biz / model-biz / flow-biz 全量回归 | **21/51/41 全绿** | 含 PublishValidatorIT 5 / LabelRoutingIT 4 / FlowGrantIT 3 |
| mcp-biz 全量回归 | **5/5** | 构造适配（McpToolProvider withDataLabel）无语义变化 |
| framework 全量回归 | **25/25** | ErrorCode 新增两码无既有断言影响 |
| server 全量（含架构八套） | **108/108** | PersistenceArchTest 6 / TiokaArchitectureTest 11 / M2ArchitectureTest 5 / M3ArchitectureTest 10 / ModuleDependencyArchTest 5 / RuntimeApiContractTest 9（容器）/ M2B4VaultContractTest 6 / M3C1aMigrationContractTest 7 + 其余 server 套件 |

本批新增测试合计 **10 类 / 63 例**（13+9+9+5+6+4+5+5+4+3），全部离线零容器（RuntimeApiContractTest 为既有回归，Docker 可用环境实测通过）。全仓 `mvn test` 一次通过（framework 25 + 各模块 + server 108，共 **480** 例，0 失败 0 错误）。

## 五、依赖方向实证结论

1. **CEL 复用**：CelRuleCondition 在 orchestration-biz，biz→biz 不建依赖；connector-biz 直接依赖 dev.cel 0.9.1（tioka-dependencies 统一版本），无环，经 ModuleDependencyArchTest。
2. **SinkGuard(IM)**：connector-biz 依赖 governance-api（SinkGuard/SinkCheckResult/SinkKind），governance-api 仅依赖 framework，无环。
3. **data.query Provider**：connector-biz 依赖 runtime-api（ToolProvider/ToolCallContext，仅依赖 framework），无环；runtime-biz 不依赖 connector（ArchUnit runtimeBizMustNotDependOnConnector 保持成立）。
4. **TurnLabelLookup**：model-api 定义 SPI，runtime-biz 实现（RuntimeTurnLabelLookup），model-biz 经 ObjectProvider 装配——runtime→model-api 单向，无环。

## 六、未尽事项（后续批次 / CI）

1. **JDBC 数据集双区域容器版**：DatasetGatewayIT 行隔离用内存源模拟 REST postFilter 语义；JdbcDatasetSource 真库（容器）行隔离归 CI（whereSql 参数化语义已由 DatasetPolicyCompilerTest 覆盖到 SQL 形态层）。
2. **MAIL 出口**：邮件通道落地后补 SinkGuard(MAIL) 挂接（偏差 2）。
3. **M4 护栏管线 SINK 检查点**：REDACT 落点 M3 内联在 DefaultSinkGuard / SinkGuardPipelineStep；管线 SINK 检查点本体（pii.detect 等）随 M4 护栏管线。
4. **IM 信封标签位**：payload.dataLabel 生产透传随 IM 信封契约修订（偏差 6）。
5. **MODEL_PLAN REDACT_THEN_\***：提示词假名化 / 审批处理随 M4 护栏管线（偏差 7）。
6. **principal.attrs 增量同步**：GrantService 快照与身份域增量同步（偏差 13）。
7. **agentNodeCountsDepth+ / Flow runs API**：统计口径与 REST 面随 DOC/PRS 批次（偏差 12）。

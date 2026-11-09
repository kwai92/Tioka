# M3 批次 C-2b：GOV-07 Cedar 策略引擎 + TRU-02 授权书编译（解锁 MAS-01/02 收口）实现覆盖说明

> 批次：M3 批次 C-2b（M3-05 GOV-07 / TRU-02，ADR-032「编译成 Cedar」+ 修订）
> 分支：`feature/m3-c2b-policy`（基线 main = `954ad11`，worktree `p3-c2b-policy`）
> 收口范围：迁移 V200（gov_policy 策略存储）；PolicyStore / PolicyEvaluator（cedar-java 4.11.0 两遍求值）/ CedarSchemaProvider / PolicyCache + policy_changed 跨实例失效 / 管理接口（PolicyAdminApi）与管线第 9 步挂接 / 错误码 40340/40341；TRU-02 类型化 Charter（agent-api）/ CharterCompiler 六类规则 / DelegationGrantCheck 动作类别回填 / NarrowingVerifier delegable 回接 / 当日累计（RedisDailyCounterStore + commit 记账）/ 四眼服务端二次校验
> 硬约束遵守：workspaceId 权威（gov_policy 系统级行 workspace_id NULL，无 tenant_id）；cedar-java 仅 governance-biz PolicyEvaluator 直依赖（guava/jne 为其运行必需）；不改 deploy/compose.yml；MQ 信封契约不动；ArchUnit 既有规则全过（PersistenceArchTest / TiokaArchitectureTest / M2 / M3 / ModuleDependency / RuntimeApiContract / M2B4VaultContract）；Redis 测试零直连（测试 mock）

## 一、迁移计划

| 号 | 内容 | 破坏性 | 备注 |
|------|------|--------|------|
| V200 | `V200__m3_policy_store.sql`：CREATE TABLE gov_policy（id/workspace_id 可空系统级/version/source/policy_id/cedar_text/mode/effect/status/created_by/时间戳）+ 唯一索引 **(workspace_id, source, policy_id, version)** + 两个查询索引；契约副本 `docs/contracts/db/` | 否 | 唯一索引含 version：TRU-02 重复编译按「旧行停用保留 + 新版本写入」幂等演进（PolicyModeIT 实测曾暴露 (ws,source,policy_id) 三列唯一与保留语义冲突，已在分支内修正，未合入过 main 无升级影响） |

workspace 隔离口径：gov_policy 有 workspace_id 列（系统级策略行 NULL），按 ScopeLine 正常过滤；charter 来源 `source = charter:{agentVersionId}` 只能由编译路径写入。

## 二、范围 → 实现映射

| 项 | 实现 |
|------|------|
| API 层 | governance-api `policy/`：PolicyApi（已存在）+ PolicyAdminApi（list/detail/importPolicy/setMode/disable）+ CharterPolicyCompilerApi（compile/deactivate，governance-api 不依赖 agent-api，ActionClassSpec 对齐类型化 Charter 字段） |
| 持久化 | `infrastructure/persistence/GovPolicyDO`（**TechnicalRecordDO**，ArchUnit 约定基类）+ `infrastructure/mapper/GovPolicyMapper`（自定义 SQL 全部在 `mapper/GovPolicyMapper.xml`，注解 SQL 禁令合规）+ `infrastructure/policy/GovPolicyRepository` |
| PolicyStore | permit 词法拒绝（`(?m)^\s*permit\b`，本类不依赖 cedar）；@id/@effect 词法解析（无注解按 DENY）；导入默认模式 `tioka.policy.import-default-mode`（DRYRUN）；`replaceCharterPolicies` 整组停用 + 新版本写入（幂等覆盖）；setMode/disable 管理操作 |
| PolicyEvaluator | **全仓库唯一 import com.cedarpolicy 的类**（ArchUnit cedarOnlyInGovernance）；两遍求值：DENY 组 forbid 命中即 DENY → ESCALATE 组 → 否则 ALLOW；命中回填 PolicyHit（policyId/mode/effect）；CompletableFuture 超时控制（默认 20ms）；解析/求值失败抛 PolicyEvalException 由服务层按 anyEnforce 兜底 |
| CedarSchemaProvider | EUID 构建（userEuid/actionEuid/toolEuid/actionGroupEuid）+ sanitize 防 Cedar 注入；实体组装：User / Action（挂类别组父实体）/ Tool（**平铺属性 + 嵌套 attrs record（CedarMap）并存**——金额/记录数直读 `resource.x`，范围读 `resource.attrs.x`）；context Map→Cedar Value（含 CedarList） |
| 缓存与刷新 | `PolicyCache`（按空间缓存 + TTL 60s 兜底）；`PolicyChangeNotifier`（发 DomainEvent 到 GovernanceMqTopics.POLICY_CHANGED）+ `PolicyChangedConsumer`（IdempotentConsumer，失效本实例空间缓存，跨实例广播走既有 RocketMQ） |
| 管理服务 | `DefaultPolicyService`：实现 PolicyApi + PolicyAdminApi；求值失败按集合内 anyEnforce 决定 DENY+审计（policy_error）/ ALLOW+告警；管理操作权限点 `gov:policy:admin`（Permissions.GOV_POLICY_ADMIN）+ 审计；DRYRUN 命中审计由管线第 9 步写 |
| 装配 | `config/PolicyAutoConfiguration`（@ConditionalOnProperty tioka.policy.enabled，matchIfMissing=true）注册进 AutoConfiguration.imports；PolicyProperties（enabled/evalTimeoutMs=20/cacheTtlSeconds=60/importDefaultMode=DRYRUN） |
| 类型化 Charter | agent-api `spec/Charter`（role/departmentId/mission/actingAs/serviceAccountId/actionClasses/forbiddenTools/escalation/kpis/reviewEvery；ActionClass{code,name,toolPatterns,Limits{perCallAmountMicros,dailyAmountMicros,perRunRecords,dailyRecords,scope},fourEyes,ceiling,delegable}）；`classify` glob 匹配（与 CharterPublishCheck 同口径）；`delegableClasses()`；`parse(JsonNode)` 类型化投影（AgentSpec.charter 保持 JsonNode 兼容） |
| CharterCompiler | `DefaultCharterPolicyCompiler` 六类规则（@id = charter:{agv}:{class}:{rule}，编译产物一律 ENFORCE）：单次金额/单 Run 记录数/当日金额/当日记录数 → ESCALATE；scope 范围外 → DENY；禁区工具 → DENY（**按委派链编写**：`context.agentVersion == "{agv}" || context.chain.contains("{agv}")`，祖先禁区生效）；动作组 `action in Tioka::Action::"{类别}"`；发布（AgentService.publish/rollback）经 ObjectProvider 调 compile，类型化解析失败抛 AGENT_PUBLISH_INVALID |
| 管线第 9 步 | `PolicyEvalPipelineStep`（order=9.0，首个注册管线步骤 bean）：context 按 M3-05 §1.2（workspaceId/agentVersion/agentId/origin/time/actionClass/autonomyLevel/chain/depth/onBehalfOf/dailyAmountMicros/dailyRecords）；DENY → BizException **POLICY_DENIED(40340)** 带策略 @id（DefaultToolGateway prepare 捕获后记 step=9 拒绝审计）；ESCALATE → verdict + ctx 四眼标记（DefaultToolGateway 预留分支转审批，reason 前缀 `fourEyes:`）；DRYRUN 命中照常继续 + `tool.policy_dryrun` 审计；开关关闭整步跳过 + `tool.policy_skipped` 审计 |
| 动作类别回填 | `DelegationGrantCheck` 3 参（+ ToolRegistry/ObjectProvider<AgentSnapshotApi>）：charter 有动作类别时 risk!=READ 未归类 DENY / 类别 ∉ grant.actionClasses DENY；无授权书保持放行兼容（C-2a 登记偏差第 3/5 条就此关闭） |
| NarrowingVerifier 回接 | DefaultGrantService 5 参（+ ObjectProvider<AgentSnapshotApi>）；narrow 的 parentCharterClasses 改为快照类型化 Charter 的 `delegableClasses()`（空则回退 grant.actionClasses，兼容存量） |
| 当日累计 | `DailyCounterStore` SPI（add/get/rebuild + DailyUsage，unknownUsage=按已用满宁可多转审批）+ `RedisDailyCounterStore`（`trust:usage:{agentId}:{actionClass}:{yyyyMMdd}` JSON TTL 48h，ADR-032 口径）+ `TrustActionDailySource`（TrustActionMapper.listAppliedInDay：APPLIED + 未撤销 + 当日窗口）；DefaultToolGateway commit 后 recordDailyUsage(row, approved)（成功才记账；actionClass 空/计数器缺失跳过；撤销扣回归 TRU-07 本批不做） |
| 四眼 | `ApprovalSuspendService`：fourEyes 标记写入 assigneeJson + cardJson；`ApprovalDecideService.guardAssignee` 前置 isFourEyes 校验（requested_by 或 runtime_turn run_as 命中审批人即拒绝）+ `FOUR_EYES_SELF_APPROVAL` 审计；`RuntimeApprovalQueryMapper` ASSIGNED_TO_ME scope 同步过滤（发起人不可见自己名下四眼单） |
| 错误码 | ErrorCode 新增 `POLICY_DENIED(40340, 403)`、`POLICY_EVAL_FAILED(40341, 403)`（M3-05 §1.3） |
| 依赖 | governance-biz：cedar-java 4.11.0（uber classifier）+ com.fizzed:jne 4.5.3（本地库定位）+ guava 33.5.0-jre（AuthorizationResponse.errors 公共字段暴露 guava 类型，compile scope）；runtime-biz / agent-biz：+ tioka-module-governance-api（runtime-biz 已传递依赖 agent-api） |

## 三、测试清单

| 测试 | 结果 | 覆盖 |
|------|------|------|
| `CedarEvaluatorTest`（governance-biz，离线表驱动） | 5 方法 / **24 行表驱动** | DENY 组优先 / ESCALATE 组次之 / 否则 ALLOW；金额/当日/记录数边界（严格大于）；版本与委派链条件（chain.contains）；未归类动作放行；scope contains 命中/未命中/属性缺失容错；禁区工具；DENY>ESCALATE 优先级；非 forbid 不参与收紧；hits 回填 policyId/mode/effect；多 DENY 全量回填；解析失败 PolicyEvalException |
| `PolicyStoreTest`（governance-biz，离线 mock 仓储） | 15/15 | permit 一律拒绝（含注解在前）；@id/@effect 词法解析与缺省；导入版本递增/默认模式/actor；charter 整组替换版本演进 + permit 拒绝先于停用；setMode/disable NOT_FOUND；currentSetVersion 跨来源取最大；空间列表不含系统级行 |
| `CharterCompilerTest`（governance-biz，离线） | 12/12 | 六类规则产物与 @id 口径；effects 映射；ESCALATE 文本形态（动作组 Action 层级 + 委派链条件）；scope 文本形态（contains）；禁区按类别生成；无限额空组；null classes=版本回退清空；compile→store/notifier 委派；deactivate；**编译产物 → 真实 Cedar 求值联动**（超限 ESCALATE / 边界 ALLOW） |
| `PolicyRefreshIT`（governance-biz，离线） | 5/5 | 同空间缓存命中；跨实例失效（policy_changed → 本实例重建、他实例 TTL 兜底）；他空间失效隔离；null 工作区 no-op；TTL 归一 |
| `PolicyEvalFailureTest`（governance-biz，离线 mock） | 7/7 | anyEnforce → DENY + recordIndependent（policy_error）；全 DRYRUN → ALLOW + record；成功无审计；setMode/disable 权限点校验（403）；setMode 委派 + 通知 + 审计 |
| `PolicyModeIT`（governance-biz，Testcontainers PG，gov_policy 直建 DDL） | 4/4 | 导入→切模式→停用全链路（ACTIVE 行进出求值集）；charter 双版本替换（旧行 DISABLED 保留、新行 ACTIVE version=2）；系统级 + 空间合并求值（DENY 组先于 ESCALATE 组）；纯空间 charter ESCALATE 命中 |
| `PolicyEvalStepTest`（runtime-biz，离线 mock） | 11/11 | DENY → POLICY_DENIED(40340) 带策略 @id + 拒绝前属性可还原；ESCALATE + charter fourEyes 标记透传；DRYRUN 命中照常继续 + policy_dryrun 审计（would_deny/would_escalate）；ALLOW 无审计；开关关闭 + policy_skipped 审计；API 缺失跳过；**context §1.2 全字段断言**（含快照 agentId/actionClass/ceiling=autonomyLevel/chain/depth/onBehalfOf/当日累计）；快照加载失败降级 L1；unknownUsage 哨兵值；order=9.0 |
| `CharterEnforceIT`（runtime-biz，离线 mock 快照） | 8/8 | WRITE 已归类且类别在授予内放行 / 类别不在授予内 DENY / 未归类 WRITE DENY / READ 绕过类别检查 / 授予类别空集拒写 / 无授权书放行 / 快照不可用放行 / 无快照 provider 放行 |
| `RedisDailyCounterStoreTest`（runtime-biz，离线 mock Redis） | 10/10 | ADR-032 键口径；缺失/坏 JSON 零值；add 累加 + JSON 键序稳定（LinkedHashMap）+ TTL 48h；Redis 故障 add/get → unknownUsage（Long.MAX/4 哨兵）；rebuild 汇总回填 + 回填失败返回重算值 + 空源零值 |
| `FourEyesGuardTest`（runtime-biz，离线 mock） | 4/4 | requested_by=审批人拒绝 + FOUR_EYES_SELF_APPROVAL 审计；Turn 发起人（run_as）=审批人拒绝；合规审批人通过守卫；非四眼单不受约束 |
| 既有回归 | 更新构造调用 | DefaultToolGatewayTest（11 参 + DailyCounterStore provider）/ GrantToolGatewayIT（DelegationGrantCheck 3 参 ×7、DefaultToolGateway 11 参）/ GrantRevokeIT、ReaperIT（DefaultGrantService 5 参） |
| governance-biz surefire 全量 | **39/39**（另 PolicyRefreshIT 5 + PolicyModeIT 4 经 -Dtest 执行） | 含框架/契约既有回归 |
| runtime-biz surefire 全量 | **98/98** | 含 NarrowingVerifierTest 30 / GrantToolGatewayIT 7 / GrantRevokeIT 6 / ReaperIT 6 / DelegateIT 7 / AgentLoopTest 等全部回归 |
| agent-biz surefire 全量 | 全绿 | AgentService publish/rollback 编译挂接回归（CharterPublishCheckTest / AgentSpecContractTest 等） |
| server 架构 + 契约 | **54/54** | PersistenceArchTest 6（GovPolicyDO→TechnicalRecordDO、mapper 注解 SQL 禁令合规）/ TiokaArchitectureTest 11 / **M3ArchitectureTest 10** / ModuleDependencyArchTest 5 / RuntimeApiContractTest 9 / M2B4VaultContractTest 6 / M3C1aMigrationContractTest 7 / LayeredDependencyArchTest 3 / SecretStoreArchTest 1 |

## 四、关键实测结论（cedar-java 4.11.0 无 schema 求值）

1. **Cedar `in` 不是列表成员运算**：仅用于实体层级成员判定（`action in Tioka::Action::"pay"`）；字符串列表成员须用 `[...].contains(x)`——编译器 scope 条件已按 `!(["C","D"].contains(resource.attrs.customerLevel))` 生成（DenyDebug 系列 standalone 程序实测，结论登记偏差）。
2. **嵌套属性访问需要 record 值**：`resource.attrs.x` 要求实体 attrs 中存在名为 `attrs` 的 record（cedar-java `CedarMap`）；CedarSchemaProvider 平铺属性与嵌套 `attrs` record 并存（金额/记录数直读、范围读 attrs 双口径都成立）。
3. **Cedar 动作组只能为 Action 类型父实体层级**：ADR-032 示例的 `Tioka::ActionClass::"..."` 写法在无 schema 求值下不可解析；统一落 `Tioka::Action::"{类别}"`，类别与工具同层级，登记偏差。
4. **uber jar 运行依赖**：cedar-java 4.11.0 uber 需补 `com.fizzed:jne:4.5.3`（本地库定位）与 `com.google.guava:33.5.0-jre`（AuthorizationResponse 公共字段 `type/success/errors` 暴露 ImmutableList，响应为公共字段非访问器）。
5. **编译器 when 块**：`when { ... };` 语法（曾出现双层 when 嵌套导致 `unexpected token '{'`，已修复并由 CharterCompilerTest 编译→求值联动覆盖）。

## 五、MAS-02 / MAS-01 解锁清单（C-2a 登记阻塞项处置）

| C-2a 阻塞项 | 本批处置 |
|------|------|
| 第 2.5 步动作类别检查 | **已落地**（DelegationGrantCheck 回填 + CharterEnforceIT 8 断言） |
| GrantAgentView classes 恒空集 | **已落地**（GrantAgentViews 从类型化 Charter 解析） |
| NarrowingVerifier parentCharterClasses 回退 | **已收紧**（delegableClasses()，空回退 actionClasses 兼容存量） |
| MAS-02（禁区 chain.contains / CedarContext chain/depth/onBehalfOf / **V161 重编译迁移**） | 禁区 chain 条件、CedarContext 三字段**已落地**；**V161 重编译迁移不做**（存量数据无 charter 策略，编译随发布触发即可，登记于 §六） |
| OnBehalfOfIT 端到端 | 服务账号 ∩ 经办人 + 动作类别已具备单测语义（CharterEnforceIT / PolicyEvalStepTest context 断言）；真实容器端到端仍归 CI（G3DelegationGateIT 保持 @Disabled） |
| G3DelegationGateIT / DelegateDualInstanceIT / DelegateIT#tokensCountToParent | 容器与 worker 全链路，归 CI 登记，本批不动 |

## 六、未尽事项（后续批次 / CI）

1. **V161 重编译迁移**：无存量授权书数据，M3 阶段不需要；若生产升级时存在 charter JSON，再补一次性重编译迁移。
2. **TRU-07 撤销扣回**：撤销审批/撤销动作时当日累计扣减未实现（当前只增不减；Redis 键 48h 自然过期兜底）。
3. **TRU-09 AutonomyGate**：L1〜L4 自治级别闸门（ceiling 已进 context.autonomyLevel，决策侧未接）。
4. **飞书卡片渲染过滤**：ASSIGNED_TO_ME scope 已按 card_json/assignee_json 过滤四眼单；飞书推送侧列表过滤未接（M4 工作项）。
5. **版本归档停用入口**：CharterPolicyCompilerApi.deactivate 已实现但无调用方（Agent 退役动作 M4 接入）。
6. **PolicyEvaluator 解析错误归类**：cedar 对坏文本返回 Failure 响应（错误信息 `failed to parse policy...`），与「写入端词法拒绝」双保险；管理界面导入预校验 UI 在 M4。
7. **跨实例缓存刷新端到端**（PolicyRefreshIT#crossInstance 的 Redis 广播版）：消费注册挂 RocketMqConsumerBootstrap 惯例，双实例容器版归 CI。
8. **mcp/flow/dataset 资源 ID 归属**：ToolDescriptor 仍无 resourceArgs 元数据，沿用 C-2a 偏差登记（仅 kb 交集剔除）。

## 七、验证快照

- governance-biz：全量 39/39 + PolicyRefreshIT 5/5 + PolicyModeIT 4/4（Testcontainers PG）
- runtime-biz：全量 98/98（含本批新增 33 断言方法）
- agent-biz：全量绿（publish/rollback 编译挂接）
- tioka-server：架构 + 契约 54/54（PersistenceArch / Tioka / M2 / M3 / ModuleDependency / RuntimeApiContract / M2B4VaultContract / M3C1aMigrationContract）
- 新增测试合计：**81 个测试方法**（governance 48 + runtime 33）

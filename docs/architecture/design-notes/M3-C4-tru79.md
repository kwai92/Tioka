# M3 批次 C-4：TRU-09 自治等级判定 + TRU-07 撤销与补偿实现覆盖说明

> 批次：M3 批次 C-4（M3-05 §7 TRU-09 / §5 TRU-07 / §11 V202/V203，ADR-033「撤销」节 + ApprovalPolicy 语义，ADR-034 判定表/观察期/TAINTED 修订，ADR-020）
> 分支：`feature/m3-c4-tru79`（基线 main = `7cb7374`，worktree `p3-c4-tru79`）
> 收口范围：迁移 V202（撤销）/V203（自治）；AutonomyLevel/AutonomyContext/AutonomyApi + DefaultAutonomyGate 判定表（ADR-034 逐行）/AutonomyLevelStore + TrustAutonomyRepository/DefaultAutonomyService（事件+审计+通知+抽检）/SamplingPolicy/降级开关；AutonomyGatePipelineStep（管线 10.0）+ DefaultToolGateway 接线 + recordAutoCommit 事后抽检；撤销窗口三层解析（charter→空间默认→24h）/撤销状态机（PROPOSED→APPROVED→APPLIED→COMPENSATING→COMPENSATED/COMPENSATION_FAILED）/UndoAuthorizer 五类可撤销人/UndoService 同范围幂等整组撤销/TrustCompensator（outbox+幂等消费+3 次退避+停组+Attention）/撤销熔断计数/undo REST 接口/当日累计扣回
> 硬约束遵守：workspaceId 权威（trust_undo_window/trust_autonomy 均以 workspace_id 为主键或过滤列，无 tenant_id）；不引新运行时依赖；不改 deploy/compose.yml；MQ 信封契约不动（复用既有 tioka.trust.compensate.v1 / action_undone.v1 / compensation_failed.v1 / autonomy_changed.v1）；业务 ID 用 `Ids.newId`（undo_/aev_ 前缀）；ArchUnit 既有规则套件全过

## 一、迁移计划

| 号 | 内容 | 破坏性 | 备注 |
|------|------|--------|------|
| V202 | `V202__m3_trust_undo.sql`：CREATE TABLE trust_undo_window（workspace_id PK, window_hours）；trust_undo_request（id/workspace_id/scope_type/scope_id/status OPEN·COMPLETED·FAILED/requester/时间戳，**UNIQUE(scope_type, scope_id)**）；trust_undo_circuit（workspace_id PK, undo_count/comp_fail_count/window_start）；`ALTER TABLE trust_action ADD COLUMN undo_scope_type/undo_scope_id/compensate_seq/undo_attempts` + idx_trust_action_scope(scope_type, scope_id)；契约副本 `docs/contracts/db/` | 否 | 同范围幂等靠 UNIQUE 兜底；undo_scope_* 记撤销归属（TRU-07 §5.4） |
| V203 | `V203__m3_trust_autonomy.sql`：CREATE TABLE trust_autonomy（workspace_id+agent_id 主键语义，level 默认 L1）；trust_autonomy_event（id aev_ 前缀/kind SET·INCIDENT·SAMPLING/payload JSON）；trust_autonomy_stats（agent_id+day 维度 auto_total/auto_sampled）；`ALTER TABLE trust_action ADD COLUMN autonomy_level VARCHAR(4)`；契约副本 | 否 | L0〜L4 代码 4 字符足够 |

## 二、范围 → 实现映射

### TRU-09 自治等级判定（ADR-034）

| 项 | 实现 |
|------|------|
| API 层 | governance-api `autonomy/`：`AutonomyLevel`（L0〜L4 + fromCode/ordinalLevel）；`AutonomyContext`（13 参 record：cedar 判定/origin/level/reversibility/estimated/risk/inObservationPeriod/toolPolicy/agentId/agentVersionId/actionClass/tainted/injectionSensitive）；`AutonomyApi`（list/levelOf/setLevel/markIncident/shadowReport/recordAutoCommit + AutonomyView/ShadowReport） |
| 判定表 | `DefaultAutonomyGate`（governance-biz，**纯函数无依赖**）：ADR-034 判定表逐行实现、自上而下命中即止——① Cedar DENY→DENIED；② shadow 或 L0→SHADOW_ONLY；③ toolPolicy 收紧（approve/deny 照办、auto 不越级，只收紧不放宽）；④ ESCALATE→REQUIRE_APPROVAL；⑤ TAINTED 且 injectionSensitive 上限 L2；⑥ 观察期 ≥L3 按 L2；⑦ L1→审批；⑧ L2 可逆（REVERSIBLE/COMPENSABLE）+ 非估算 + WRITE→自动；⑨ L3 非 IRREVERSIBLE 且非估算→自动；⑩ L4 非估算→自动；兜底 REQUIRE_APPROVAL |
| 持久化 | `AutonomyLevelStore` 接口 + `infrastructure/autonomy/TrustAutonomyRepository` 实现（MP LambdaQueryWrapper）；TrustAutonomyDO/EventDO/StatsDO + 三 Mapper（BaseMapper，自定义 SQL 在 XML 合规） |
| 服务 | `DefaultAutonomyService`：setLevel 写事件（aev_ 前缀）+ 审计 + 通知；markIncident→降 L1（**调用方带归属**，见偏差 2）；recordAutoCommit→bump stats + L3/L4 稳定哈希抽检写 SAMPLING 事件 |
| 抽样 | `SamplingPolicy`：Percentage（对 actionId 稳定哈希）/Fixed 两种 |
| 通知 | `AutonomyChangeNotifier`：DomainEvent → tioka.trust.autonomy_changed.v1，subjectKey=workspaceId（同空间分区） |
| 装配 | `config/AutonomyAutoConfiguration`（@ConditionalOnProperty `tioka.trust.autonomy.enabled`，matchIfMissing=true）注册进 AutoConfiguration.imports |
| 管线接线 | runtime-biz `AutonomyGatePipelineStep`（order=10.0，AUTONOMY_GATE）：level = min(store levelOf 缺省 L1, charter ceiling 缺省 L2)；观察期 = AgentSnapshot.publishedAt + 7 天（ADR-034）；决策写 ctx（`autonomy.decision` / `autonomy.level`）；DENIED→BizException FORBIDDEN；判定异常降级 REQUIRE_APPROVAL |
| 网关接线 | `DefaultToolGateway.prepare` 重排：changeSet 先建，pipeline 风险属性（risk/reversibility/estimated）入 pipeCtx；`autonomy.decision` 存在时按其映射（SHADOW_ONLY→GateDecision.SHADOW_ONLY + PolicyOutcome.ALLOW），否则 evaluatePolicy 兜底（ApprovalPolicy 切换口径见偏差 4）；insertProposed 传 autonomy_level；commit 成功后 `recordAutoCommitForSampling`（L3/L4 + AUTO_COMMIT 时调 recordAutoCommit） |
| 快照扩展 | agent-api `AgentSnapshot` 尾加 `Instant publishedAt`（观察期起点，AgentSnapshotService toSnapshot 从 agent 表 published_at 取） |
| Charter 扩展 | `Charter.ActionClass` 尾加 `Long undoWindowHours`（parse 从 `ac` 节点读，供撤销窗口第一优先级） |

### TRU-07 撤销与补偿（ADR-033）

| 项 | 实现 |
|------|------|
| 撤销窗口 | `UndoWindowResolver` 接口 + `DefaultUndoWindowResolver`（@Component）：charter.undoWindowHours → 空间默认（V202 trust_undo_window，`trust_undo_window` 配置键）→ 24h 三层解析；`PreparedCallStore.markApplied` 撤销期经 resolver（null 兜底 24h） |
| 状态机 | `PreparedCallStore`/`TrustActionStore`（runtime-biz）：PROPOSED→APPROVED→APPLIED→COMPENSATING→COMPENSATED / COMPENSATION_FAILED；新增 STATUS_COMPENSATING/COMPENSATED/COMPENSATION_FAILED；markCompensating/markCompensated/markCompensationFailed/bumpUndoAttempts |
| 可撤销人 | `UndoAuthorizer`（@Component）五路判定：① Turn 发起人（runtime_turn run_as）；② 工单请求人（`RunRequesterLookup` SPI，M3 无实现置空，见偏差 3）；③ Agent 负责人；④ 批准过该动作的审批人；⑤ 空间管理员（简化为 `workspace:manage` 权限，见偏差 8） |
| 撤销编排 | `UndoService`（@Transactional undo）：同范围幂等（findByScope）→ listAppliedInScope → authorizer.check → 撤销期校验（过期 → `UNDO_WINDOW_CLOSED(40910, 409)`）→ insert request（dup 冲突兜底）→ **倒序** markCompensating + publishCompensate（subjectKey=scopeId 分区，payload 含 actionId/undoScopeId/seq/toolName/workspaceId）→ 落已撤销/已补偿清单 → circuit.recordUndo → 审计 trust.undo |
| 补偿消费者 | `TrustCompensator` extends IdempotentConsumer<Map>（consumerName "trust-compensator"，tag TRUST_COMPENSATE）：幂等键 actionId+":undo"；非 COMPENSATING / 组 FAILED 跳过；compensateOnce = ToolRegistry.resolve → TransactionalTool.compensate(receipt, id+":undo")；成功 → markCompensated + 当日累计扣回（DailyCounterStore.deduct，下限 0）+ publishActionUndone；失败 3 次指数退避 → markCompensationFailed + publishCompensationFailed + **停组** + openOwnerAttention（runtime_approval kind=CARD）+ circuit.recordCompensationFailure |
| 撤销熔断 | `TrustUndoCircuitRepository`：1h 滑动窗口 undo_count/comp_fail_count |
| REST | kernel-api `TrustUndoController`：POST /api/trust/undo（鉴权在 UndoAuthorizer 五路判定，不套单一 @RequirePermission，见偏差 8） |
| 持久化 | TrustUndoWindowDO/RequestDO/CircuitDO + 三 Mapper + 三 Repository（@Repository）；TrustActionMapper.xml 4 处 SELECT 加新列、insert 加新列、追加 listAppliedInScope（seq DESC）/listCompensatingInScope（seq ASC）/markCompensatingMeta/bumpUndoAttempts/countCompensatedInWindow |
| 卡片链路 | ChangeSetDetail.changeStatus / card changeStatus 字符串透传（COMPENSATING/COMPENSATED/COMPENSATION_FAILED 三新态自动生效）；轨迹 PartType 渲染归前端 FE-22（后端数据已就绪） |

## 三、偏差登记

1. **AutonomyContext 字段对齐**：M3-05 冻结结论中 L2 行要求「严格 REVERSIBLE」、L3 行仅「排除 IRREVERSIBLE」，同一布尔 reversible 无法表达两行语义；改为 `String reversibility`（枚举值 REVERSIBLE/COMPENSABLE/IRREVERSIBLE），判定表 L2 用 compensable={REVERSIBLE, COMPENSABLE}、L3 仅排 IRREVERSIBLE。
2. **markIncident 扩参**：governance 模块不能读 runtime 的 trust_action 表，事件归属（actionClass/actionId/note）由调用方随参数带入，而非服务层自查。
3. **RunRequesterLookup SPI 置空**：runtime-biz 不读 orch_mandate（工单域），工单请求人判定留 SPI 接口 `RunRequesterLookup`，M3 无实现（check 五路中该路恒否），后续批次接入。
4. **ApprovalPolicy / GateDecision 切换口径**：autonomy.decision 存在时按映射表切换（SHADOW_ONLY→GateDecision.SHADOW_ONLY + PolicyOutcome.ALLOW，即影子模式放行但只建草稿不提交），REQUIRE_APPROVAL→approve 照办转审批，AUTO→auto 放行；无 autonomy 决策（降级开关关闭/步骤缺失）走既有 evaluatePolicy 兜底。
5. **Attention 简化**：补偿失败的人工 Attention 用 runtime_approval kind=CARD 承载（openOwnerAttention 既有通道），不新增 Attention 种类。
6. **抽检审批落事件**：L3/L4 抽检（SAMPLING 事件）落在 trust_autonomy_event，审批台渲染登记；不写 runtime_approval（governance 不能跨模块写）。
7. **FakeErp**：简报缺口③已过时——FakeErp（tioka-testkit，含 setCompensateFail/mutateBefore 注入）已由 B-5 批次建好，本批直接复用。
8. **空间管理员简化**：M3 用既有 `workspace:manage` 权限点判定空间管理员，避免新增权限点目录 + 角色迁移；REST 接口因此用 `@RequirePermission({})` 空权限点（PermissionAspect 语义 = 仅需已登录），五路细判在 UndoAuthorizer 内部完成。
9. **UndoDualInstanceIT 归 CI**：双实例消费者竞争全链路（RocketMQ 真容器）归 CI，本批以离线幂等/停组/熔断单测覆盖语义。
10. **trust_autonomy 系列登记 ScopeLine exempt**：V203 三表（trust_autonomy/trust_autonomy_event/trust_autonomy_stats）按 ADR-034 数据模型无 workspace_id（等级 per Agent×类别，Agent 全局唯一），已登记 `tioka.persistence.workspace-scope-exempt-tables`（PersistenceProperties 默认清单）；六个新 DO 继承 TechnicalRecordDO 过 PersistenceArchTest。

## 四、测试清单

| 测试 | 结果 | 覆盖 |
|------|------|------|
| `AutonomyGateTableTest`（governance-biz，离线表驱动） | 2 方法 / **25 行判定表** | ADR-034 判定表逐行：Cedar DENY 优先 / shadow 与 L0 / toolPolicy 三态收紧（approve/deny 照办、auto 不越级、只紧不宽）/ ESCALATE / TAINTED×injectionSensitive 封顶 L2 / 观察期 ≥L3 按 L2 / L1 全审批 / L2 三条件与 / L3 排除项 / L4 非估算 / 兜底审批 |
| `AutonomyPolicyIT`（governance-biz，离线） | 4/4 | toolPolicyOnlyTightens（只紧不宽）/ observationPeriod（publishedAt+7d 内降 L2）/ setLevelWritesEventAndNotifies（aev 事件 + autonomy_changed 通知 + 审计）/ recordAutoCommitBumpsStatsAndSamplesAtL3（stats 累计 + L3 抽检 SAMPLING 事件） |
| `PostHocSamplingIT`（governance-biz，离线） | 3/3 | 10% 抽样 50〜150 次提交断言样本量落在置信区间 / Fixed 采样全量 / L2 不抽检 / 同 actionId 稳定哈希确定性 |
| `UndoIT`（runtime-biz，离线 FakeErp） | 6/6 | ① 五步动作整组撤销复原快照一致 + 补偿倒序（act_5→act_1，补偿 seq 1→N）；② 补偿失败 3 次退避→COMPENSATION_FAILED + 停组 + Attention（kind=CARD）+ 熔断计数；③ 不可逆动作清单拒绝；④ 撤销期过期 UNDO_WINDOW_CLOSED(40910)；⑤ 同范围双击幂等（UNIQUE 兜底）；⑥ 越权拒绝（非五类可撤销人） |
| `UndoCardIT`（runtime-biz，离线） | 5/5 | 五类可撤销人矩阵：Turn 发起人 / Agent 负责人 / 批准过该动作的审批人 / 空间管理员（workspace:manage）/ 无关人拒绝；工单请求人 SPI 置空登记 |
| 既有回归 | 更新构造调用 | PolicyEvalStepTest / DelegateIT / CharterEnforceIT / DelegableIT（AgentSnapshot +publishedAt 尾参 null）；GrantToolGatewayIT / DefaultToolGatewayTest（网关构造 + AutonomyApi provider）；runtime-biz pom + tioka-testkit test 依赖 |
| governance-biz 全量 surefire | **41/41** | 含框架/契约既有回归（*IT 类按根 pom 约定不在默认 surefire，经 -Dtest 执行） |
| runtime-biz 全量 surefire | **98/98** | 含 DefaultToolGatewayTest 3 / GrantToolGatewayIT / NarrowingVerifierTest 30 / AgentLoopTest 等全部回归 |
| connector-biz 全量回归 | **30/30** | 未受影响 |
| server 架构 + 契约套件 | **59/59** | PersistenceArchTest 6（新 DO 继承 TechnicalRecordDO；trust_autonomy 系列登记 workspace-scope-exempt）/ TiokaArchitectureTest 11（TrustUndoController @RequirePermission({})；DefaultAutonomyService 注入 ObjectMapper 合规）/ M2ArchitectureTest 5 / M3ArchitectureTest 10 / ModuleDependencyArchTest 5 / RuntimeApiContractTest 9 / M2B4VaultContractTest 6 / M3C1aMigrationContractTest 7 |

本批新增测试合计 **20 例**（AutonomyGateTableTest 2 + AutonomyPolicyIT 4 + PostHocSamplingIT 3 + UndoIT 6 + UndoCardIT 5），全部离线零容器。

## 五、未尽事项（后续批次 / CI）

1. **TRU-10 降级联动**：治理降级（governance 不可用）时自治判定的整体降级联动未做——当前 AutonomyGatePipelineStep 判定异常已降级 REQUIRE_APPROVAL，但 store 不可达时的缺省策略未单独联动降级开关。
2. **抽检驳回消费**：SAMPLING 事件只写不消费，审批台驳回抽检后回写 markIncident 的消费链路后续批次接。
3. **eval 负向反馈**：策略求值失败/负向结果对自治等级的自动下调（ADR-034 负反馈环）未实现。
4. **RunRequesterLookup**：工单请求人 SPI 无实现（偏差 3），orch 域接入后补。
5. **UndoDualInstanceIT**：双实例竞争全链路归 CI（偏差 9）。
6. **补偿幂等跨实例**：IdempotentConsumer 幂等表在离线测试用内存实现，RocketMQ 真容器语义归 CI。

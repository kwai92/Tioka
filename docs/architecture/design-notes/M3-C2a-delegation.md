# M3 批次 C-2a：委派授予链非阻塞部分（MAS-01 主体 + MAS-03 + RT-12 关闭态合入）实现覆盖说明

> 批次：M3 批次 C-2a（M3-01 MAS-01 / MAS-03 / RT-12 非阻塞部分）
> 分支：`feature/m3-c2a-delegate`（基线 main = `6b2db79`）
> 收口范围：迁移 V160（rt_grant + runtime_thread.grant_id）；GrantService 接口 + DefaultGrantService（签发/收窄/回收/缓存抽象）；NarrowingVerifier 收窄校验；ToolCallContext.grantId 第 9 字段 + ToolGateway 第 2.5 步真实现 + listTools 授予过滤；startTurn 根授予 + StartTurnCmd.onBehalfOf/mandateScope；MAS-03 SubAgentReaper（级联 REAPED + 预算回拨 + 审批 EXPIRED + 唤醒父）；RT-12 agent.delegate（`tioka.runtime.delegate.enabled=false` 关闭合入）；framework-security onBehalfOf 交集；orch 传参（scope_json / requester_user_id）
> 硬约束遵守：全程无 tenantId（workspaceId 权威）；业务 ID 一律 `Ids.newId()`（grt_/turn_/thr_）；不引新运行时依赖（未引 cedar-java）；不改 deploy/compose.yml；MQ 信封契约不动；ArchUnit 既有规则不动；不动 M3-00/M3-01 文档本体；Redis 测试零直连（GrantCacheStore 抽象 + 测试 mock）

## 一、迁移计划

| 号 | 内容 | 破坏性 | 备注 |
|------|------|--------|------|
| V160 | `V160__m3_delegation_grant.sql`：CREATE TABLE rt_grant（ADR-039 数据模型全列 + idx_rt_grant_parent）+ `ALTER TABLE runtime_thread ADD COLUMN grant_id VARCHAR(64)`；契约副本 `docs/contracts/db/` | 否 | **不重复建 audit_event 五列**（V221/OBS-05 已提前建）；列宽以 V221 为准（grant_id VARCHAR(64)），M3-01 草稿 VARCHAR(32) 过时 |
| V161 | **本批不做**（MAS-02 授权书重编译，被 TRU-02 阻塞），号段 V160〜V169 空闲 | — | 登记于 §六 |

workspace 隔离口径：ADR-039 数据模型无 workspace 列 → rt_grant 按「会话级内部对象、经 runtime_thread.grant_id 关联隔离」处理，登记进 `PersistenceProperties.workspace-scope-exempt-tables`（与 runtime_turn/runtime_item 同类），实体继承 TechnicalRecordDO（PersistenceArchTest 三条规则全过）。

## 二、范围 → 实现映射

| 项 | 实现 |
|------|------|
| GrantService 接口 | runtime-api `grant/` 8 文件已在库（DelegationGrant/ChainHop/TaskNarrow/Budget/GrantStatus/MandateScope/GrantAgentView/GrantService，字段对齐 ADR-039）；实现在 runtime-biz |
| DefaultGrantService | `application/grant/DefaultGrantService`：root（charter null → classes 空集，rootTtl 默认 24h=props.rootGrantTtl）/ narrow（含父 ACTIVE 校验）/ reap（mapper `WITH RECURSIVE` RETURNING 受影响 ID → afterCommit evict + publishRevoked）/ get（缓存 60s 回源回填）；预算回拨 `accrueAndReturnToParent`（GrantBody CAS ×3 重试） |
| 持久化 | `infrastructure/RuntimeGrantDO`(TechnicalRecordDO) + `RuntimeGrantMapper` + XML（insert/findById/updateBodyCas **body_json 整体 CAS 乐观锁**/reapRecursive/sumThreadTokens/countActiveChildTurns/findChildThreadIds）+ `GrantStore`（infrastructure 层，三层封装） |
| 收窄校验 | `application/grant/NarrowingVerifier` 纯函数无 Spring：工具/类别/资源三类交集、narrow 出父没有的工具或资源**抛错不静默丢弃**、scope 同键交集空抛错/新键追加、标签 min、预算 b.le(父剩余=amountLimit−used)、期限 min(父, now+childTtl)、深度 `chain.size >= maxDepth+1` 抛「委派深度超限」、skill/出网交集、chain+hop |
| 缓存抽象 | `application/grant/GrantCacheStore`（get/put/evict/publishRevoked）+ `RedisGrantCacheStore`（`rt:grant:{id}` TTL 60s、pub/sub `rt:grant:revoked`，广播失败 TTL 兜底） |
| ToolCallContext.grantId | 第 9 字段 + 旧 8 参构造兼容（仅 spi 内部工厂用）；`ToolCallContexts.forTurn` 8/9 参双载；`LoopContext.grantId`（TurnRunner 从 thread 读入）→ GatewayToolPort 透传；M3ArchitectureTest.toolCallsCarryGrant 冻结规则生效且**零违例**（全仓库仅工厂构造 ToolCallContext） |
| 第 2.5 步真实现 | `application/tool/DelegationGrantCheck` 替换删除 `AlwaysAllowGrantCheck`：grantId null 放行（旧链路/测试）；非 ACTIVE/过期 → `GRANT_INACTIVE`；工具不在授予 → DENIED；kb（kbId/kbIds）按授予交集**就地剔除**（M1-04 口径），剔空 DENIED；scope 参数越界 DENIED；拒绝经管线记 **step=2**；`DefaultToolGateway.listTools` 按授予过滤（模型看不到授予外工具） |
| startTurn 根授予 | `KernelRuntimeService.ensureRootGrant`：新 thread 首 Turn 签发、写 `runtime_thread.grant_id`（MyBatis-Plus null-不更新补丁式 upsert）；同 thread 复用（REAPED/过期重签）；签发失败不阻断 Turn（grant_id 保持空，2.5 步放行）；GrantAgentView 由 `GrantAgentViews.fromSnapshot` 统一构造（工具名经 ToolRegistry 解析，与模型可见一致） |
| StartTurnCmd | 新增 `onBehalfOf`/`mandateScope`（Map<String,List<String>>），旧 5 参构造兼容；`run_as_json` 写入 `onBehalfOf`（KernelRuntimeService.runAsJson） |
| MAS-03 Reaper | `kernel/child/SubAgentReaper.onChildTerminal`（TurnRunner 三处终态点 + CancelledSignal + FAILED 钩入，异常仅告警）：reap 级联 → 子 thread token 合计回拨父授予 → 子 thread 各 Turn PENDING 审批 `expirePendingForSubject(TURN,…,"reaped")`；afterCommit：SubAgentSandboxReleased 结构化事件 + 按 `ChildPendingRegistry.take(childTurnId)` 唤醒父（最终回答 = 子 thread 最后一条 assistant message，截断 4000 字符 ≈ RT-10 口径） |
| 级联取消 | TurnSignalService.interrupt：WAITING_CHILD 直接 `cancelWaitingChild`（新 mapper 方法）→ `cascadeToChildren` 递归 interrupt（reason=parent_cancelled） |
| RT-12 DelegateTool | `application/tool/DelegateToolProvider`（name=builtin）：注册挂 `tioka.runtime.delegate.enabled`（默认 **false**，关闭时 listTools 不含它）；六步照 M3-01 L164-171：subAgents.allowed 校验（快照 subAgents 预留 null → 拒绝）/ `countActiveChildTurns < maxParallel`（超限 ok=false「并行数已达上限」）/ currentPublished + charter 状态检查 stub（见 §四）/ TransactionTemplate 同一事务 narrow+建 thread(parent_turn_id=父Turn, depth+1, grant_id)+turn=QUEUED(origin=delegate, **run_as=父 run_as 不变**)/afterCommit `dispatchQueue.offer`（XADD rt:turns）/ PendingJob(jobId=childTurnId) → ChildWaitService 挂起 WAITING_CHILD → 子终态 Reaper 唤醒；ToolProvider SPI 新增 ctx 感知 default invoke（DefaultToolGateway 透传完整 ctx，既有 provider 默认回退不受影响）；fork() NotImplemented 不动 |
| 配置 | KernelProperties 新增 `delegate{enabled=false,maxDepth=2,maxParallel=3}`、`rootGrantTtl=24h`、`childGrantTtl=1h`（`tioka.runtime.delegate.*`） |
| framework-security | `Principal` 增第 7 字段 `onBehalfOf`（旧 6 参构造兼容，Jackson 兼容旧缓存 JSON）；`PrincipalLoaderApi.loadEffective(uid, ws, onBehalfOf)` default 方法（测试桩只附着身份）；system-biz `PrincipalLoader.loadEffective` 真实现：服务账号权限 ∩ 经办人权限（任一侧 "*" 取另一侧具体集） |
| runAs 重载 | TurnRunner.resolveRunAs：run_as_json 带 onBehalfOf → loadEffective（全链名义不变 + 权限交集） |
| orch 传参 | AgentActionDispatcher：`repo.findLatestMandate(workItemId)` → StartTurnCmd.onBehalfOf=requester_user_id、mandateScope=scope_json 解析（失败空 Map 不阻断派工） |
| 错误码 | framework/error ErrorCode 新增 `GRANT_NOT_NARROWER(40330,403)`、`GRANT_INACTIVE(40331,403)`（M3-01 L111） |

## 三、测试清单（全部离线，零 Redis 直连）

| 测试 | 结果 | 覆盖 |
|------|------|------|
| `NarrowingVerifierTest`（runtime-biz） | **30/30** | M3-01 L117 表驱动：工具（T1〜T4 收窄通过/放大拒绝）、类别（T5〜T7）、资源（R1〜R4）、scope（S1〜S4）、标签（L1〜L2）、预算（B1〜B4 含父已用口径）、期限（E1〜E3）、深度（D1〜D2）、skill/出网（K1/N1）+ 缺省预算=父剩余一半 + onBehalfOf/rootPrincipal 保留 |
| `GrantToolGatewayIT`（runtime-biz，离线） | 7/7 | grantId null 放行 / 子调父有己无工具 DENIED / REAPED → GRANT_INACTIVE / scope 越界 DENIED（HT-2）/ kb 交集就地剔除 + 剔空 DENIED / 管线拒绝步号=2 / listTools 按授予过滤 |
| `GrantRevokeIT`（runtime-biz，离线 mock 缓存+Store） | 6/6 | reap 级联广播（evict+publishRevoked 含孙 ID → 「1 秒内 DENIED」经缓存失效抽象断言）/ get 60s 缓存回源回填 / narrow 父 REAPED → GRANT_INACTIVE / 预算回拨 CAS 重试（10k-3k 父 used 7000→0）/ root 签发落库（refOf/onBehalfOf/charter null→空集） |
| `ReaperIT`（runtime-biz，离线 mock） | 6/6 | 终态后 grant REAPED + 用其 grantId 构造调用 DENIED(GRANT_INACTIVE) / 预算回拨（10k 用 3k 父增 7k，子用量累计 3k）/ 审批 EXPIRED(reason=reaped) / 唤醒父带子最终回答 + sandbox 结构化事件 / 非子 thread 空操作 / FAILED/CANCELLED 唤醒带错误码 |
| `DelegateIT`（runtime-biz，离线 mock） | 7/7 | 开关默认关闭 listTools 不含 agent.delegate / parentWaitsChild（accepted+PendingJob(jobId=childTurnId)、同事务建 thread(parent_turn_id/depth+1/grant_id)+QUEUED turn、run_as 不变、afterCommit offer）/ parallelLimit（3 个在跑第 4 个拒绝）/ subAgents.allowed 校验 / 无父授予拒绝 / 深度上限断言 / unknown tool |
| runtime-biz surefire 全量 | **73/73** | 含既有 AgentLoopTest / DefaultToolGatewayTest（补 GrantService mock 参数）/ ToolPipelineStepRegistryTest 等回归 |
| orchestration-biz surefire 全量 | **23/23** | AgentActionDispatcher 改造回归 |
| server 架构四件套 + 契约 | **52/52** | PersistenceArchTest 6（rt_grant 豁免 + DO 基类 + application 不持 Mapper，GrantStore 落 infrastructure 包）/ TiokaArchitectureTest 11 / M2ArchitectureTest 5 / **M3ArchitectureTest 10（toolCallsCarryGrant 生效零违例）** / ModuleDependencyArchTest 5 / M2B4VaultContractTest 6 / **RuntimeApiContractTest 9/9**（Spring 上下文装配含新增 grant bean 全通过） |
| PlaceholderLedgerIT | 1/1 | fork() 保持 NOT_IMPLEMENTED（KernelRuntimeService 构造参数变更回归） |

## 四、与 M3-01 文档的偏差登记

1. **审计域归属**：M3-01 写「governance-biz AuditWriter 写五列」与实际不符——审计域实际在 system 模块（system-api AuditEvent + system-biz AuditService）；且五列已由 V221 提前建好（ADR-039 修订节）。本批**不改 AuditWriter 填值**（chain_json/grant_id 等五列由审计统一模型写入端自动补齐归 ADR-072/GOV-09 链路），登记为 M3-01 偏差。
2. **表名**：M3-01/ADR-039 写 `runtime_session` 为 V128 前旧名词，一律落 **runtime_thread**；grant_id 列宽 VARCHAR(64)（随 V221 口径，非草稿 32）。
3. **Charter stub**：AgentSpec.charter 为 JsonNode 预留必须 null（TRU-02 未落地）→ 根授予动作类别恒空集（M3-01 L37 语义）；DelegateTool 第 3 步 charterApi.status(child)==ACTIVE 检查无 API 可调 → **stub 放行**；父授权书 delegable 无类型化投影 → narrow 的 parentCharterClasses 先取父授予 actionClasses（交集口径不变，MAS-02 落地后收紧）。
4. **第 2.5 步资源子检查简化**：ToolDescriptor 无 `resourceArgs` 元数据（M3-01 L79「ToolDescriptor 新增」未在冻结范围）→ 仅 kb（kbId/kbIds 参数键，M1-04 现有口径）做交集剔除；mcp/flow/dataset 资源 ID 归属无法识别，**stub 放行登记**，待 ToolDescriptor 扩展后补。
5. **工作盘简化**：M3-01 L221 第 5 步「工作盘文件复制/删除标记」——工作盘机制尚不存在，简化为 sandbox 释放结构化事件（`SubAgentSandboxReleased`）+ 前三项（reap/回拨/审批）完整落地。
6. **子授予期限近似**：子快照无 turnTimeout 投影 → 用 `tioka.runtime.child-grant-ttl`（默认 1h）近似 min(父, now+子TTL)。
7. **预算计量口径**：Budget DTO 无 used/remaining 字段（ADR-039 Budget.of(a.limits()) 需 M3-02 dataset 等）→ 用 rt_grant.body_json 内 GrantBody{usedTokens,version} 记账，子用量 = 子 thread 全部 Turn prompt+completion tokens 合计（mapper SUM）；「划拨即冻结」目前以父 used 上账近似（父 used += childBudget 于 narrow 时不写、回拨时 used -= returned），精确冻结随 GOV-07 批次校准。
8. **DelegationGrantCheck 兼容口径**：grantId=null 视为根授予尚未签发放行（M3-01 L23 语义），保证既有无授予链路（flow / chat 旧 turn）零行为变化。

## 五、被 GOV-07 / TRU-02 / MAS-02 阻塞清单（本批 stub 或不做）

- **2.5 步动作类别检查**（`risk != READ → actionClass ∈ grant.actionClasses`）：依赖 TRU-02 Cedar 求值器 + Charter 类型化，本批 stub 放行（GrantToolGatewayIT 不含该断言，OnBehalfOfIT 动作类别断言同阻塞）。
- **MAS-02**（Charter.delegable / CharterCompiler 禁区改 chain.contains / CedarContext 加 chain/depth/onBehalfOf / **V161 重编译迁移**）：整项不做。
- **OnBehalfOfIT 端到端**（服务账号∩经办人检索收窄）：loadEffective 单元语义已由 PrincipalLoader 实现覆盖，端到端 IT 依赖真实 Charter/Cedar 管线，登记骨架（测试口径见 M3-01 L121）。
- **G3DelegationGateIT**：保持 @Disabled 骨架不动；解除条件 = MAS-01〜03 全量落地 + GOV-07/TRU-02 转绿 + 双实例容器环境。
- **DelegateDualInstanceIT / DelegateIT#tokensCountToParent**：双实例与预算超限取消需容器与 worker 全链路，容器版归 CI 登记。
- **flow_run.grant_id 列**（ADR-039 数据模型含 `ALTER TABLE flow_run ADD grant_id`）：DAT-04（Flow 透传授予）范围，本批未动。

## 六、未尽事项

- GrantCheck 的 kb 交集剔除在**校验期**就地改写 args；管线第 3〜4 步的 ResourceGuard 过滤仍在其后执行（双保险，口径一致）。
- DelegateTool 挂起登记存在微小竞态窗口（子 Turn 极速完成先于父 ChildWaitService.suspend 注册）：DelegateTool 已先行 register（同映射幂等），且 ChildResumeService 幂等可重入；极端场景由 ChildTimeoutSweeper 兜底。
- SubAgentReaper 对「子 Turn 无 grant_id（历史数据 / 签发失败）」只做审批过期 + 唤醒，不做预算回拨。
- runtime_thread.grant_id 更新走 MyBatis-Plus updateById（null 字段不更新），未来改手写 SQL 时需保留该语义。

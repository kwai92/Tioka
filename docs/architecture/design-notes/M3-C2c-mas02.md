# M3 批次 C-2c：MAS-02 授权书下放收口实现覆盖说明

> 批次：M3 批次 C-2c（M3-01 L128-146 / L155 MAS-02，2 人天）
> 分支：`feature/m3-c2c-mas02`（基线 main = `5ddd674`，worktree `p3-c2c-mas02`）
> 收口范围：① delegable 发布校验（CharterPublishCheck）；② 委派链祖先禁区策略参与求值（语义已由 C-2b 满足，本批以 ChainForbiddenIT 证明 + 修复一处 C-2b 遗留缺陷）；③ 类型化 Charter 顶层 `delegable` 列表 + parentCharterClasses 去空集回退
> 硬约束遵守：不改 deploy/compose.yml；无新运行时依赖；workspaceId 权威；MQ 信封契约不动；ArchUnit 既有规则全过；Redis 测试零直连；M3-00/M3-01 文档本体未改

## 一、范围 → 实现映射

| 项 | 实现 |
|------|------|
| Charter 顶层 delegable | agent-api `spec/Charter`：record 加 `List<String> delegable`（置于 forbiddenTools 之后，M3-01 L128 口径），parse 读顶层数组缺省 `List.of()`（compact 构造归一）；`delegableClasses()` **顶层列表非空优先、否则按 `ActionClass.delegable` 标志派生**（C-2b per-class boolean 保留为兼容派生源） |
| 发布校验 | agent-biz `CharterPublishCheck` 新增 `CODE_DELEGABLE_INVALID`（CHARTER_DELEGABLE_INVALID）：delegable ⊆ 本授权书 actionClasses codes ∪ subAgents 成员授权书 actionClasses codes（尽力解析 member.charter / member.spec.charter，成员形态未定解析失败不阻断）；顶层 delegable 数组优先、缺省按 delegable=true 标志派生（派生码必属本授权书类别，天然合规）；违反报 `PublishError("/charter/delegable", ...)` 消息含越界类别名；`tioka.trust.charter.enabled=false` 行为不变（早退在 check() 入口） |
| parentCharterClasses 收紧 | runtime-biz `DefaultGrantService.parentCharterClasses`：**授权书解析成功即返回 `delegableClasses()`（可为空集，不再回退 grant.actionClasses）**——delegable=[] 意味着父授权书不可下放，子授予类别收窄为空集（子 Agent 写类操作全 DENY）；仅快照缺失 / 无授权书 / 解析失败回退（登记偏差保持） |
| 祖先禁区参与求值 | **无需新代码**：C-2b PolicyCache.Loader 经 `store.loadActiveForEvaluation(workspaceId)` 已全量加载全部 charter:{agv} 来源策略，编译条件 `(context.agentVersion == "{agv}" || context.chain.contains("{agv}"))` 使祖先策略对后代自动生效；本批 ChainForbiddenIT 以真实编译器 + 真实 Cedar 求值证明（含跨两 hop 孙子、直接子、无祖先对照组、非禁区工具对照组） |
| N+1 防范 | chain 取祖先 agentVersionId 后按 source 批量查：由 `GovPolicyRepository.listActiveForWorkspace(workspaceId)` 单查询返回全空间 ACTIVE 行（C-2b 装配），无逐 hop 查询 |
| **C-2b 遗留缺陷修复** | `DefaultCharterPolicyCompiler.forbiddenToolSpec` 实体列表字面量 `Action::"..."` 短写**解析通过但静默不匹配**（禁区规则自编译起从未在求值中命中过），修正为完整类型 `Tioka::Action::"..."`；`CharterCompilerTest.forbiddenToolTextShape` 断言同步更新。新增实测登记：Cedar 实体列表字面量与单实体引用均须完整类型（短写是坑 #1 的又一变体） |

## 二、测试清单

| 测试 | 结果 | 覆盖 |
|------|------|------|
| `ChainForbiddenIT`（governance-biz，**新增**，纯离线：真编译器 + 真 PolicyCache + 真 DefaultPolicyService + 真 Cedar JNI） | 5/5 | 祖先授权书禁区工具 X 编译产物 @id/effect/chain 条件文本；孙子（跨两 hop chain=[祖先,子,孙]）调用 X → DENY + hits 带策略 @id（ENFORCE）；直接子 → DENY + @id；chain 不含祖先 → ALLOW（对照组）；非禁区工具（在类别 toolPatterns 内）→ ALLOW；compile→store/notifier 委派与 charter source 广播 |
| `DelegableIT`（runtime-biz，**新增**，离线：真 DefaultGrantService.narrow + 真 DelegationGrantCheck） | 5/5 | delegable=[] → 子授予类别空集 + 子 Agent 写类工具 DENIED（"动作类别不在授予内"）；delegable=["pay"] → 类别 {pay} + 放行；顶层缺省按 delegable=true 标志派生放行（C-2b 兼容口径）；快照缺失回退父授予 actionClasses（登记偏差口径）；子快照无类别 → 交集空集（三重收窄） |
| `CharterPublishCheckTest`（agent-biz，**新增 4 用例**） | 9/9 | delegable 越界（["echo","pay"] vs 类别 {echo}）→ CHARTER_DELEGABLE_INVALID 且消息含越界类别名 pay、不含本类别 echo；delegable ⊆ 本类别 → 放行；顶层缺省按标志派生 → 放行；subAgents 成员授权书类别（member.charter）并入 allowed → 放行 |
| 既有回归 | 全绿 | `CharterCompilerTest` 12/12（forbiddenToolTextShape 断言更新为完整类型）；`CedarEvaluatorTest` 5（24 行表驱动）/ `PolicyEvalStepTest` 11 / `DelegateIT` 7 / `NarrowingVerifierTest` 30 / `GrantToolGatewayIT` 7 / `GrantRevokeIT` 6 等不受影响 |
| 模块全量（surefire 默认，`-am` 联动） | **BUILD SUCCESS** | governance-biz **39/39**；runtime-biz **98/98**；agent-biz **16/16**（上游 framework/system/api 模块 78 全绿） |
| server 架构契约八套（`-Dtest` 显式执行） | **45/45** | PersistenceArchTest 6 / TiokaArchitectureTest 11 / M3ArchitectureTest 10 / ModuleDependencyArchTest 5 / M2ArchitectureTest 5 / P1L8ArchSuiteIntegrityTest 4 / LayeredDependencyArchTest 3 / SecretStoreArchTest 1 |
| 新增 IT 经 `-Dtest` 显式执行（默认 surefire 排除 `**/*IT.java`） | 10/10 | ChainForbiddenIT 5 + DelegableIT 5 |

## 三、实测登记（延续 C-2b Cedar 三坑）

1. **实体列表字面量也须完整类型**：`action in [Action::"mcp.drop.table"]` 解析通过、求值静默不命中；`action in [Tioka::Action::"mcp.drop.table"]` 正常（DenyDebug standalone 实测；C-2b 坑 #1「`in` 仅实体层级」的变体——坑 #1 只登记了单实体引用 `Tioka::Action::"pay"` vs `Action::"pay"`，列表字面量同规则）。C-2b 遗留：forbidden-tool 规则一直用短写，禁区从未实际生效过；本批修复并由 ChainForbiddenIT 锁定。
2. **DefaultPolicyService 默认 evalTimeoutMs=20ms 偏紧**：Cedar JNI 首次求值含初始化开销，离线测试直连 DefaultPolicyService 会超时误触「eval-error → anyEnforce DENY 兜底」（表现为 DENY 但 hits 空）。ChainForbiddenIT 显式 `setEvalTimeoutMs(5000)`。生产 20ms 是否够用待 CI/压测观察（冷启动超时 → ENFORCE 侧 fail-closed 为 DENY，安全方向正确但可能误伤）。

## 四、未尽项与偏差登记

1. **subAgents 成员类别并集完整校验待解冻后启用**：subAgents 为预留 JsonNode（M3 须 null/空）；发布校验对非空 subAgents 尽力解析成员授权书（member.charter / member.spec.charter），成员形态正式定稿（含跨版本快照引用解析）后需补齐：成员 agentVersionId → 快照 → 授权书类别并集的权威解析 + 变更联动重校验。
2. delegable=[] 语义收紧后，存量「charter 无任何 delegable 标志」的已发布 Agent 的后续 narrow 将收窄为空集（MAS-02 预期行为：未声明可下放 = 不可下放）；如需对存量放行，须在授权书补 delegable 标志后重新发布（M3-01 L128 缺省空口径的直接后果，非偏差）。
3. parentCharterClasses 的兼容回退（快照缺失/无授权书/解析失败 → 父授予 actionClasses）维持 C-2a 登记，未在本批关闭。
4. ChainForbiddenIT/DelegableIT 为离线构造（mock GrantStore/快照 API），容器版端到端（真实发布 → 编译 → Redis 缓存 → 子 Turn 调用被祖先禁区拒绝）依赖 G3 套件基建，登记 CI 侧补充。

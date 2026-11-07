# M3-01 — 子 Agent 与委派授予

- 负责人：BE-B（RT-12、MAS-03）、BE-A（MAS-01、MAS-02）
- 工作项：RT-12（P0，提前到 M3）、MAS-01（P0）、MAS-02（P0）、MAS-03（P0）
- 约束：ADR-027、ADR-038、ADR-039；ADR-029 第 43〜46 条；ADR-030
- 前置：RT-08（M1-02）、CON-01（M1-04）、TRU-05（M2-05 `prepare` / `commit`）、GOV-07 与 TRU-02（M3，Cedar 求值器与授权书编译）
- 迁移：V160〜V162（M3 多智能体与运行时权限号段 V160〜V169）
- 估算：RT-12 6 人天（BE-B），MAS-01 5、MAS-02 2（BE-A），MAS-03 3（BE-B），合计 16 人天
- 排期：周次、人天与负责人以 [M3-00](M3-00-M3实施计划.md) 为准（2026-11-06，ADR-029 第 66 条）。负责人不变

本阶段交付「能委派，且委派后权限只会收窄」。团队（任务图、黑板、仲裁，MAS-04〜07）在 M4，届时直接复用本节的 `GrantService` 与子 Turn 机制。**RT-12 与 MAS-01 必须同一个发布版本上线**（ADR-029 第 43 条）：MAS-01 合并前，`agent.delegate` 的工具注册用特性开关 `tioka.runtime.delegate.enabled=false` 关闭。

---

## MAS-01 委派授予（5 人天）

### 改动清单

| 模块 | 文件 | 改动 |
| --- | --- | --- |
| runtime-api | `grant/DelegationGrant.java`、`grant/ChainHop.java`、`grant/TaskNarrow.java`、`grant/GrantStatus.java` | 新增，字段照抄 ADR-039 |
| runtime-api | `grant/GrantService.java` | 新增接口：`root`、`narrow`、`reap`、`get` |
| runtime-api | `spi/ToolGateway.java` | `ToolCallContext` 增加 `String grantId`；保留旧构造器（`grantId=null` 视为根授予尚未签发，仅测试用） |
| runtime-biz | `application/grant/DefaultGrantService.java` | 签发、收窄校验、回收、Redis 缓存 |
| runtime-biz | `application/grant/NarrowingVerifier.java` | 纯函数：证明子 ⊆ 父，失败抛 `GRANT_NOT_NARROWER` |
| runtime-biz | `application/RuntimeService#startTurn` | 新 thread 的第一个 Turn 签发根授予，写 `runtime_session.grant_id`；同一 thread 后续 Turn 复用（过期则重签） |
| runtime-biz | `application/DefaultToolGateway` | `prepare` / `call` 第 2.5 步；`listTools` 按授予过滤 |
| framework-security | `Principal`、`RunAs` | `run_as_json` 增加 `onBehalfOf`；`PrincipalLoader.loadEffective(runAs)` 返回两者权限交集 |
| governance-biz | `audit/AuditWriter` | 写 `root_principal`、`on_behalf_of`、`chain_json`、`grant_id`、`data_label` |
| orch-biz | `application/dispatch/RunDispatcher` | 派工时把 `orch_mandate.scope_json` 与经办人（`requester_user_id`）传给 `startTurn`（新增 `StartTurnRequest.onBehalfOf`、`mandateScope`） |

### 根授予

```java
public DelegationGrant root(Principal runAs, String onBehalfOf, AgentSnapshot a, MandateScope m) {
    Set<String> tools = a.tools().allToolNames();                  // 内置 + MCP 白名单 + flow.* + 业务插件
    Set<String> classes = a.charter() == null ? Set.of()
                        : a.charter().actionClasses().stream().map(ActionClass::code).collect(toSet());
    Map<String, Set<String>> res = Map.of(
        "kb", a.knowledge().kbIds(), "mcp", a.tools().mcpServerIds(),
        "flow", a.tools().flowPublicationIds(), "dataset", a.datasets());   // dataset 见 M3-02
    DataLabel max = DataLabel.min(a.dataClearance(), workspaceProps.maxLabel());
    return save(new DelegationGrant(Ids.newId("grt"), null, runAs.ref(), onBehalfOf,
        List.of(new ChainHop(a.versionId(), null)), tools, classes, a.skillVersionIds(), res,
        m == null ? Map.of() : m.scope(), max, a.sandbox().egressHosts(),
        Budget.of(a.limits()), Instant.now().plus(props.rootTtl()), ACTIVE, null));   // rootTtl 默认 24h
}
```

### 收窄校验

```java
final class NarrowingVerifier {                 // domain 层，无 Spring 依赖，表驱动单元测试
    static DelegationGrant narrow(DelegationGrant p, AgentSnapshot c, Charter parentCharter, TaskNarrow n) {
        Set<String> tools   = intersect(p.tools(), c.tools().allToolNames(), n.toolsOr(ALL));
        Set<String> classes = intersect(p.actionClasses(),
                                        parentCharter == null ? Set.of() : Set.copyOf(parentCharter.delegable()),
                                        c.charterClassCodes());
        Map<String, Set<String>> res = intersectEach(p.resources(), c.bindings(), n.resourcesOr(ALL));
        Map<String, List<String>> scope = andScope(p.scope(), n.scope());   // 同键取交集，新键追加；交集为空 → 抛错
        DataLabel max = DataLabel.min(p.maxDataLabel(), c.dataClearance());
        Budget b = n.budget().orElse(p.budget().remaining().fraction(0.5));
        if (!b.le(p.budget().remaining())) throw new BizException(GRANT_NOT_NARROWER, "预算超出父授予剩余额度");
        Instant exp = min(p.expiresAt(), Instant.now().plus(c.limits().turnTimeout()));
        if (p.chain().size() >= props.maxDepth() + 1) throw new BizException(GRANT_NOT_NARROWER, "委派深度超限");
        return new DelegationGrant(/* parentGrantId = p.grantId(), chain = p.chain() + hop, … */);
    }
}
```

`narrow` 只接受集合、区间、枚举三种形式；`TaskNarrow` 里出现父授予没有的工具或资源时不是静默丢弃，而是抛错（模型给错了收窄应当被告知，而不是悄悄变成更小的集合后才发现做不了事）。

### ToolGateway 第 2.5 步

```text
2.5 grant = grantService.get(ctx.grantId)
    if grant == null || grant.status != ACTIVE || now > grant.expiresAt → DENIED(GRANT_INACTIVE)
    if name ∉ grant.tools                                              → DENIED("不在委派授予内")
    for (kind, argKey) in descriptor.resourceArgs:                     // ToolDescriptor 新增：参数里哪些键是资源 ID
        ids = args[argKey]
        if kind == "kb": args[argKey] = ids ∩ grant.resources.kb，空则 DENIED      // 沿用 M1-04 剔除规则
        else if ids ⊄ grant.resources[kind]                             → DENIED
    for (key, allowed) in grant.scope:
        if args 含 key 且 args[key] ∉ allowed                           → DENIED("超出任务范围 {key}")
    if descriptor.risk != READ:
        cls = charterApi.check(...) 的 Classified.actionClass
        if cls.code ∉ grant.actionClasses                               → DENIED("动作类别未下放")
9   Cedar context 增加 chain = grant.chain[*].agentVersionId，depth = chain.size - 1，onBehalfOf
```

`listTools(ctx)` 返回前过滤 `name ∈ grant.tools`，模型的工具列表与授予一致。

### 缓存与失效

- `get` 读 Redis `rt:grant:{id}`（JSON，TTL 60 秒），未命中读库。
- `reap` / 撤销：库内把该授予及全部后代（`WITH RECURSIVE` 按 `parent_id`）置为 `REAPED` / `REVOKED`，事务提交后 `DEL` 对应 Redis 键并发布 `rt:grant:revoked`，各实例清本地缓存。

### 迁移 V160

```sql
-- V160__m3_delegation_grant.sql
CREATE TABLE rt_grant ( ... );                         -- 全文见 ADR-039「数据模型」
ALTER TABLE runtime_session ADD COLUMN grant_id VARCHAR(32);
ALTER TABLE audit_event ADD COLUMN root_principal VARCHAR(64);
ALTER TABLE audit_event ADD COLUMN on_behalf_of VARCHAR(64);
ALTER TABLE audit_event ADD COLUMN chain_json TEXT;
ALTER TABLE audit_event ADD COLUMN grant_id VARCHAR(32);
ALTER TABLE audit_event ADD COLUMN data_label VARCHAR(16);
```

错误码（契约 `framework/error`）：`GRANT_NOT_NARROWER` 40330 → 403，`GRANT_INACTIVE` 40331 → 403。

### 测试

| 用例 | 断言 |
| --- | --- |
| `NarrowingVerifierTest`（表驱动，≥ 20 行） | 工具、类别、资源、scope、标签、预算、期限、深度各有「收窄通过」「放大拒绝」两类用例 |
| `GrantToolGatewayIT#childCannotUseParentOnlyTool` | 父有 `biz.erp.writeOff`，子快照没有 → 子调用 DENIED，审计 `chain_json` 含两节 |
| `GrantToolGatewayIT#scopeEnforced` | 授予 scope `contractId=[HT-1]`，参数 `HT-2` → DENIED |
| `GrantToolGatewayIT#listToolsFiltered` | 子 Turn 的工具列表不含授予外工具 |
| `OnBehalfOfIT` | 服务账号能读 A、B 两个知识库，经办人只能读 A → 编排 Run 只检索到 A |
| `GrantRevokeIT#cascade` | 撤销根授予后，孙授予的调用在 1 秒内 DENIED（Redis 失效广播） |

---

## MAS-02 授权书下放与链上禁区（2 人天）

### 改动清单

| 模块 | 文件 | 改动 |
| --- | --- | --- |
| agent-api | `spec/Charter.java` | 增加 `List<String> delegable`（缺省空列表） |
| agent-biz | `application/publish/PublishValidator` | 校验 `delegable` 中每个编码属于本授权书或某个 `subAgents` 成员授权书的动作类别 |
| governance-biz | `policy/CharterCompiler` | 禁区策略条件改为 `context.chain.contains("{agv}")`；额度与范围策略不变 |
| governance-biz | `policy/CedarEvaluator` | context 的 schema 增加 `chain: Set<String>`、`depth: Long`、`onBehalfOf: String` |

编译后的禁区示例：

```cedar
@id("charter:agv_01J..:forbidden:biz.lease.finalize")
@effect("DENY")
forbid (principal, action == Action::"biz.lease.finalize", resource)
when { context.chain.contains("agv_01J..") };
```

已发布授权书的策略在 V161 中重新编译（Java 迁移 `V161__m3_recompile_charters`：遍历 `gov_policy where source like 'charter:%'`，按新规则重建）。

### 测试

| 用例 | 断言 |
| --- | --- |
| `CharterCompilerTest#forbiddenUsesChain` | 生成的禁区策略文本含 `context.chain.contains` |
| `ChainForbiddenIT` | 主管授权书禁区含 X；孙子 Agent 快照有 X → 调用 DENIED，审计记策略 `@id` |
| `DelegableIT` | 主管 `delegable=[]` 时，子 Agent 的写工具全部 DENIED（动作类别未下放）；加入后放行到判定表 |

---

## RT-12 子 Agent 执行（6 人天）

按 ADR-027 第「子 Agent（RT-12）」节实现，以下为与本节相关的增量。

### `agent.delegate` 内置工具

```json
{ "name": "agent.delegate",
  "input": { "agentId": "string", "task": "string", "context": "string?",
             "narrow": { "tools": ["string"], "resources": {"kb": ["string"]}, "scope": {"contractId": ["string"]},
                         "budgetTokens": 0 } } }
```

```text
DelegateTool.invoke(ctx, args):
  1. agentId ∈ 父快照.subAgents.allowed，否则 ok=false
  2. 并行数：本步同父 Turn 已在跑的子 Turn 数 < limits.maxParallel（缺省 3，平台上限 8）
  3. child = agentRepo.publishedSnapshot(agentId)；charterApi.status(child) == ACTIVE
  4. 同一事务：grant = grantService.narrow(父授予, child, 父授权书, args.narrow)
             thread = runtime_session(parent_turn_id = 父 Turn, depth = 父 depth + 1, grant_id = grant.id)
             turn   = QUEUED，run_as = 父 run_as（不变），origin = delegate
  5. afterCommit：XADD rt:turns；父 Turn → WAITING_CHILD（ADR-027）
  6. 子 Turn 终态事件 → 唤醒父 Turn，最终回答作为 tool_result（截断规则同 ADR-027 RT-10）
```

一步内多个 `agent.delegate` 调用并行发出；父 Turn 等全部返回后再进入下一步。

### 配置

```yaml
tioka:
  runtime:
    delegate:
      enabled: false          # MAS-01 合并后改为 true
      max-depth: 2            # 平台上限 3
      max-parallel: 3         # 平台上限 8
```

### 测试

| 用例 | 断言 |
| --- | --- |
| `DelegateIT#parentWaitsChild` | 父 Turn 进入 WAITING_CHILD，不占 worker 槽位；子完成后父继续 |
| `DelegateIT#parallelLimit` | 同一步 4 个委派，第 4 个返回「并行数已达上限」 |
| `DelegateIT#cascadeCancel` | 取消父 Turn，子、孙 Turn 均 CANCELLED |
| `DelegateIT#tokensCountToParent` | 子 Turn 用量计入父 Turn 预算；超出后子 Turn 被取消 |
| `DelegateDualInstanceIT` | 父在 A、子在 B 执行，结果正确回到父 |

---

## MAS-03 子任务回收（3 人天）

### 回收时机与动作

子 Turn 进入 COMPLETED / FAILED / CANCELLED 时，在写终态的同一事务里调用 `SubAgentReaper.reap(childThread)`：

```text
1. grantService.reap(thread.grant_id)                     // 本授予与后代 → REAPED
2. 父授予预算：remaining += child.budget - child.used      // 乐观锁更新 rt_grant.body_json
3. 未决审批：runtime_approval where thread_id = child → EXPIRED(reason=reaped)
afterCommit：
4. sandboxProvider.release(child sandbox)                 // 不等 15 分钟空闲
5. 工作盘：被子 Turn 最终回答或产物引用的文件复制到父 thread 的 artifacts/；其余加 7 天删除标记
```

### 测试

| 用例 | 断言 |
| --- | --- |
| `ReaperIT#grantInvalidAfterDone` | 子 Turn 完成后，用其 `grantId` 构造的工具调用 DENIED(GRANT_INACTIVE) |
| `ReaperIT#budgetReturned` | 子授予 10k token 用了 3k，父剩余额度增加 7k |
| `ReaperIT#sandboxReleased` | 子 thread 沙箱在终态后 5 秒内释放 |

---

## 闸门 G3 追加用例

`gate/G3DelegationGateIT`：双实例，Keycloak 用户 A（区域华东）在对话里调主管 Agent；主管委派规则判定 Agent。断言：子 Agent 调用主管有、自己没有的写工具被拒并记录两节委派链；主管禁区内的工具在子 Agent 处也被拒；子任务结束后其授予失效；把 A 移出空间后 60 秒内整条链的工具调用被拒。

## 完成标准

- 任何子 Turn 的工具调用都能在审计里看到完整委派链和授予 ID。
- 没有一条路径能让子 Agent 的工具、动作类别、资源、范围、数据标签、预算、期限超过父授予。
- `agent.delegate` 默认开启的版本中，MAS-01〜03 的测试全部通过。

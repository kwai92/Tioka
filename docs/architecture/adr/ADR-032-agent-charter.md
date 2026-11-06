# ADR-032 — 数字员工档案与岗位授权书

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：TRU-01〜04（新增）
- 对应设计：设计 P（可信放权 D0）
- 方向文档：[Tioka 差异化能力方向：数字员工全生命周期](https://claude.ai/artifact/1HP9f4Rr9XmaiZqMMLRJjx)
- 相关：[ADR-033](ADR-033-reversible-actions.md) 可撤销执行、[ADR-034](ADR-034-autonomy-ladder.md) 自治阶梯、[ADR-035](ADR-035-value-ledger.md) 价值账本
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

ADR-032〜035 合称「可信放权」，把 Tioka 从逐层对标的 Agent 搭建平台改为**企业数字员工的上岗、授权、执行、问责、经营平台**：企业怎么管一个员工，Tioka 就怎么管一个 Agent。本篇是另外三篇共用的骨架。

核心决定有三条：

1. 每个已发布的 Agent 有一份**岗位授权书（Charter）**，写明岗位、负责人、按动作类别划分的额度、禁区、升级对象、KPI 和复核周期。授权书是 AgentSpec 的一段，随 Agent 版本一起版本化、diff、发布审批。
2. 授权书**编译成 Cedar 策略**，在 ADR-025 的同一个求值点执行，不引入第二套策略引擎。
3. Agent 的**有效权限 = 发起人权限 ∩ 授权书 ∩ 空间策略**，负责人失效时 Agent 自动暂停并发起交接。

## 与已有概念的区分

| 概念 | 粒度 | 回答的问题 | 出处 |
| --- | --- | --- | --- |
| 岗位授权书 Charter | Agent 版本 | 这个 Agent 平时被允许做什么、做到多大 | 本 ADR |
| `orch_mandate` | 编排工单 | 这一单是谁、因为什么交给这个 Agent 的 | ADR-020 修订第 3 条 |
| 服务账号 | 身份 | Agent 以谁的名义访问外部系统 | FND-06 |
| 审批策略 `ApprovalPolicy` | 单次工具调用 | 这一次调用要不要人批 | M1-02 RT-07，由 ADR-034 改为按自治等级判定 |

`orch_mandate` 不变，只增加 `agent_version_id`，派工复验（M2-02）时额外检查该 Agent 的授权书状态为 `ACTIVE`。

## 授权书结构（TRU-01）

`AgentSpec` 增加 `charter` 段。草稿可随时修改，发布时随版本冻结。

```java
public record Charter(
    String role,                         // 岗位名称，如「应收对账专员」
    String departmentId,                 // 服务部门；FND-09 组织同步前为手填的部门编码
    String mission,                      // 一句话职责，展示在档案页和卡片上
    ActingAs actingAs,                   // DELEGATED：以发起人名义；SERVICE：以服务账号名义
    String serviceAccountId,             // SERVICE 时必填
    List<ActionClass> actionClasses,     // 动作类别与额度，未归类的写工具一律拒绝
    List<String> forbiddenTools,         // 禁区，命中即拒绝，不进审批
    Escalation escalation,               // 超额度、异常时找谁：负责人 / 角色 / 指定用户
    List<KpiTarget> kpis,                // 与 ADR-035 的任务类型关联
    Duration reviewEvery) {}             // 复核周期，默认 90 天

public record ActionClass(
    String code,                         // 如 ar.writeoff、crm.update_contact
    String name,                         // 「核销应收」
    List<String> toolPatterns,           // 工具名通配，如 biz.erp.writeOff*、crm__update_*
    Limits limits,
    boolean fourEyes,                    // 审批人必须不是发起人
    AutonomyLevel ceiling) {}            // 自治等级上限（ADR-034），默认 L2

public record Limits(
    Long perCallAmountMicros,            // 单次金额上限，金额单位沿用 cost_micros 约定
    Long dailyAmountMicros,              // 当日累计金额
    Integer perRunRecords,               // 单个 Turn 或编排 Run 内累计影响记录数
    Integer dailyRecords,                // 当日累计影响记录数
    Map<String, List<String>> scope) {}  // 参数取值范围，如 {"customerLevel": ["C","D"]}

public record KpiTarget(String taskTypeCode, String metric, double target) {}
// metric：automation_rate / human_touch_rate / undo_rate / cost_per_task / quality_score
```

规则：

- READ 风险的工具不必归类；**风险不是 READ 的工具必须落在某个动作类别里**，否则发布校验失败（AST-02 增加此项）。
- 金额与记录数优先取预演生成的变更单汇总（ADR-033）；工具不支持预演时，取 `ToolDescriptor.policyFields` 声明的参数字段（ADR-025 中「参数中声明为可策略化的字段」），都没有时该类别的金额与记录数额度视为无法校验，一律转审批。
- 发布校验另加两条：发布者对 `serviceAccountId` 有 USE 权限；`ceiling` 高于 L2 的类别需要空间管理员审批（GOV-06 的发布审批里单独标出）。

## 有效权限（TRU-02）

```text
有效权限(调用) = 发起人权限            -- DELEGATED：Turn 的 run_as；SERVICE：服务账号的角色
              ∩ 授权书（动作类别、额度、范围、禁区）
              ∩ 空间策略（ADR-025 的 Cedar 策略，只收紧）
```

- DELEGATED 模式下 Agent 永远不超过发起人；SERVICE 模式下永远不超过授权书。两种模式都要过空间策略。
- 发起人权限沿用定案 6：每个 Turn 开始和每次工具调用前重新加载，缓存 60 秒。
- 四眼原则：`fourEyes=true` 的类别，审批人不能是 Turn 发起人，也不能是编排工单的请求人；审批台和飞书卡片按此过滤可审批人。

### 编译成 Cedar

授权书发布时由 `CharterCompiler` 生成一组 Cedar 策略，写入 `gov_policy`（`source = charter:{agentVersionId}`，`mode = ENFORCE`）。策略只能产生拒绝或转审批，不能放行 RBAC 没放行的调用，与 ADR-025「策略只能收紧」一致。示例（核销应收，单次 5 万元以上转审批，D 类客户之外拒绝）：

```cedar
@id("charter:agv_01J..:ar.writeoff:escalate-amount")
@effect("ESCALATE")
forbid (principal, action in Tioka::ActionClass::"ar.writeoff", resource)
when { context.agentVersion == "agv_01J.." && resource.amountMicros > 50000000000 };

@id("charter:agv_01J..:ar.writeoff:scope")
@effect("DENY")
forbid (principal, action in Tioka::ActionClass::"ar.writeoff", resource)
unless { ["C","D"].contains(resource.attrs.customerLevel) };
```

- `@effect` 注解决定命中后是拒绝还是转审批，求值器先查 DENY 组再查 ESCALATE 组，沿用 ADR-025 的三种结果。
- 当日累计不在 Cedar 里算。`context.dailyAmountMicros`、`context.dailyRecords` 由 ToolGateway 从 Redis 计数器 `trust:usage:{agentId}:{actionClass}:{yyyyMMdd}` 读出后放入 context；计数在 apply 成功后累加，撤销时扣回（ADR-033）。
- 审计记录命中的策略 `@id`，档案页可直接看到「这次为什么被拦」。

**GOV-07 因此从 M4 提前到 M3**（ADR-029 第 39 条）。M3 只需落地求值器、`ESCALATE` 语义、授权书编译和 DRYRUN/ENFORCE 切换；空间级策略编辑界面仍在 M4。

## 负责人与生命周期（TRU-03）

`agent_agent.owner_id`（ADR-023）就是直属负责人。授权书状态与版本无关，记在 Agent 上：

```text
ACTIVE ──负责人禁用 / 移出空间 / 复核逾期 14 天──▶ SUSPENDED ──指定新负责人──▶ HANDOVER ──新负责人确认授权书──▶ ACTIVE
   ▲                                                    │
   └──────────────── 原负责人恢复且确认 ─────────────────┘
```

- **SUSPENDED 的效果**：`RuntimeApi.createThread` 和 `startTurn` 对该 Agent 返回 `AGENT_SUSPENDED`（新增错误码 42301 → HTTP 423）；运行中的 Turn 在下一步开始前取消（与定案 6 同一检查点）；编排规则命中时决定记为 `UNAVAILABLE`；IM 中 @它会收到「该助手暂停服务，联系 {部门}」的回复。
- **触发来源**：M1 的用户禁用事件（`tioka.identity.user_disabled.v1`）、空间成员移除事件；M4 接入 SCIM 后，部门调动也会触发（负责人不再属于 `departmentId`）。
- **交接**：空间管理员指定新负责人；新负责人在档案页逐条确认授权书后变为 ACTIVE，确认动作写审计。交接期间自治等级保留但不生效，恢复后按 ADR-034 的「观察期」执行。
- 每次状态变化写 `trust_charter_event` 并通知负责人、空间管理员（IM 卡片 + 站内）。

## 复核（TRU-04）

- 到 `reviewed_at + reviewEvery` 前 14 天，给负责人发复核卡片，内容是本周期的账本摘要（ADR-035）、KPI 达成、自治等级变化、撤销和事故记录。
- 负责人点「续期」即更新 `reviewed_at`；点「修改授权书」进入 Studio 草稿。逾期 14 天自动 SUSPENDED。

## 档案页（HCI）

Studio 的 Agent 页新增「岗位档案」标签页，一屏回答「这个数字员工是谁、能做什么、做得怎么样」：

| 区块 | 内容 | 数据来源 |
| --- | --- | --- |
| 身份 | 岗位、部门、负责人、名义（以谁的身份做事）、状态、下次复核日 | 本 ADR |
| 授权 | 动作类别表：工具、额度、范围、四眼、等级上限、当前等级 | 本 ADR、ADR-034 |
| 表现 | 本月任务数、自动化率、撤销率、净节省工时与金额、KPI 达成 | ADR-035 |
| 记录 | 状态变化、等级升降、撤销、被拦截的调用（按策略 `@id` 聚合） | 审计、`trust_*` 表 |

## 接口

```java
public interface CharterApi {
    /** ToolGateway 在授权检查后调用：解析动作类别，检查禁区与授权书状态。 */
    CharterVerdict check(CharterCheckContext ctx);
    CharterView get(String agentId);
    void suspend(String agentId, String reason);
    void assignOwner(String agentId, String newOwnerId);       // 进入 HANDOVER
    void acceptCharter(String agentId);                         // 新负责人确认
    void renew(String agentId);                                 // 复核续期

    record CharterCheckContext(Principal principal, String agentId, String agentVersionId,
                               String toolName, ToolRisk risk) {}
    sealed interface CharterVerdict permits Classified, Forbidden, Unclassified, Suspended {}
    record Classified(ActionClass actionClass) implements CharterVerdict {}
    record Forbidden(String rule) implements CharterVerdict {}
    record Unclassified() implements CharterVerdict {}           // 非 READ 工具未归类 → 拒绝
    record Suspended(String reason) implements CharterVerdict {}
}
```

## 数据模型

```sql
ALTER TABLE agent_agent ADD COLUMN charter_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';  -- ACTIVE / SUSPENDED / HANDOVER
ALTER TABLE agent_agent ADD COLUMN reviewed_at TIMESTAMPTZ;
ALTER TABLE gov_policy  ADD COLUMN source VARCHAR(64);             -- NULL = 空间手写策略；charter:{agentVersionId}
ALTER TABLE orch_mandate ADD COLUMN agent_version_id VARCHAR(64);
CREATE TABLE trust_charter_event (id VARCHAR(32) PRIMARY KEY, agent_id VARCHAR(64) NOT NULL,
  kind VARCHAR(16) NOT NULL,                                       -- SUSPEND / HANDOVER / ACCEPT / RENEW / RESUME
  reason TEXT, from_owner VARCHAR(64), to_owner VARCHAR(64), actor VARCHAR(64),
  create_at TIMESTAMPTZ NOT NULL DEFAULT now());                   -- 只增
-- 授权书本体在 agent_version.spec_json 的 charter 段，不单独建表
```

ID 前缀：`chev` 授权书事件。

## 工作项

| 编号 | 工作项 | 阶段 | 优先级 | 估算（人日） | 依赖 | 验收 |
| --- | --- | --- | --- | --- | --- | --- |
| TRU-01 | AgentSpec 授权书段、发布校验、档案页 | M3 | P1 | 4（后端 2、前端 2） | AST-01、AST-02 | 写工具未归类时发布失败 |
| TRU-02 | 有效权限交集、授权书编译为 Cedar、当日累计计数、四眼 | M3 | P0 | 6 | GOV-07（提前到 M3） | G3：超额度调用转审批，越范围调用被拒并记录策略 ID |
| TRU-03 | 负责人失效自动暂停与交接 | M3 | P1 | 3 | FND-04 | G3：负责人被禁用后，新 Turn 返回 423，编排决定为 UNAVAILABLE |
| TRU-04 | 复核提醒与逾期暂停 | M4 | P2 | 2 | TRU-03、TRU-13 | — |

## 修订（2026-11-05）

授权书增加 `delegable`；禁区按委派链编译；SERVICE 模式增加 `on_behalf_of`。见 [ADR-029](ADR-029-design-rulings.md) 第 44、45 条与 [ADR-039](ADR-039-delegation-grant.md)。

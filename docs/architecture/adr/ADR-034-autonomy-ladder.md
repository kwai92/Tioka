# ADR-034 — 自治阶梯与影子上岗

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：TRU-09〜12（新增）
- 对应设计：设计 R（可信放权 D2）
- 方向文档：[Tioka 差异化能力方向：数字员工全生命周期](https://claude.ai/artifact/1HP9f4Rr9XmaiZqMMLRJjx)
- 相关：[ADR-032](ADR-032-agent-charter.md) 授权书、[ADR-033](ADR-033-reversible-actions.md) 可撤销执行、[ADR-031](ADR-031-agent-eval-experience.md) 评测、ADR-020 编排修订第 2 条（接单模式）
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

M1-02 的默认审批策略 `agent-config` 是静态的：写工具一律审批，要放开只能由负责人手工改成 `auto`。结果是两头不讨好，要么人审成为永久瓶颈，要么凭感觉一刀切放开。

本设计把放权变成**有证据、可回退的过程**，类比新员工从跟岗、试用到转正：

1. 自治等级按 **Agent × 动作类别**设置，分 L0〜L4 五级。同一个 Agent 可以查询类 L4、改价类 L1。
2. **晋级要证据、要人确认**：样本数和统计下界都达标后，系统生成转正申请，负责人确认才生效。
3. **降级自动、立即生效**：一次撤销、一次复核不通过、一次事故，就降级并通知，不等人确认。
4. 新 Agent 先在 **L0 影子模式**里跟岗：和人处理同一批工单，只产出变更单不执行，用比对结果生成上岗报告。

## 等级

| 等级 | 名称 | 写操作的处理 | 事后 |
| --- | --- | --- | --- |
| L0 | 影子 | 永不提交，变更单作为建议记录（`SHADOW_ONLY`） | 与人工实际处理比对 |
| L1 | 逐步审 | 每个写操作都审批 | — |
| L2 | 按风险审 | 可撤销、可冲销且非推断的变更单自动提交；其余审批 | — |
| L3 | 事后抽检 | 除不可撤销外自动提交 | 按比例抽检（默认 10%），撤销期内可撤销 |
| L4 | 自主 | 授权书额度内全部自动提交 | 按比例抽检（默认 2%） |

授权书给每个动作类别设了上限 `ceiling`（ADR-032，默认 L2），等级不会超过上限。新 Agent 的初始等级：没有影子期的为 L1，有影子期的由上岗报告建议、负责人确认。

## 判定表（`AutonomyGate.decide`，ADR-033 管线第 10 步）

先看 Cedar 结果，再看来源，最后看等级：

| 条件（自上而下，命中即止） | 结果 |
| --- | --- |
| Cedar = DENY | DENIED |
| Turn 来源 `origin = shadow`，或等级 = L0 | SHADOW_ONLY |
| Cedar = ESCALATE（超额度、超范围） | REQUIRE_APPROVAL |
| Agent 处于观察期（见下）且等级 ≥ L3 | 按 L2 判定 |
| L1 | REQUIRE_APPROVAL |
| L2：可逆性 ∈ {REVERSIBLE, COMPENSABLE} 且 `estimated = false` 且风险 = WRITE | AUTO_COMMIT，否则 REQUIRE_APPROVAL |
| L3：可逆性 ≠ IRREVERSIBLE | AUTO_COMMIT，否则 REQUIRE_APPROVAL |
| L4 | AUTO_COMMIT |

- Agent 版本配置的 `toolPolicies`（M1-02）仍然有效，但**只能收紧**：配置为 `approve` 或 `deny` 的工具按配置处理；配置为 `auto` 不再能越过等级。
- `ApprovalPolicy` 新增实现 `autonomy`，自 M3 起替代 `agent-config` 成为默认策略（ADR-029 第 38 条）。`ApprovalRequestContext` 增加 `actionClass`、`changeSetTotals`、`policyOutcome`、`reversibility`、`estimated`。
- **观察期**：Agent 发布新版本后 7 天内，L3、L4 的类别按 L2 执行。新版本通过 EVO-06 回归门禁且分数不低于上一版本时，观察期取消。交接（ADR-032）完成后同样进入观察期。

## 证据与晋级（TRU-12）

每个（Agent，动作类别）按 30 天滚动窗口维护以下指标，每日任务汇总到 `trust_autonomy_stats`：

| 指标 | 定义 | 来源 |
| --- | --- | --- |
| n | 窗口内的写操作判定次数（影子期为影子用例数） | `trust_action` |
| 批准率 | 审批通过 / 审批总数（带参数修改批准的算不通过） | `runtime_approval` |
| 撤销率 | 被人撤销的动作 / 已提交动作 | `trust_action` |
| 复核不通过率 | 抽检判定为「应撤销」或「有错但保留」 / 抽检总数 | 复核审批 |
| 事故数 | 负责人或管理员标记为事故的次数 | `trust_autonomy_event` |
| 线上评测分 | 该类别相关 Turn 的线上评测均分 | ADR-031 `eval_result` |
| 影子一致率 | 影子变更单与人工实际处理一致的比例 | 本 ADR 影子比对 |

比例都用 **Wilson 95% 区间**判断，样本少时下界自然很低，不会因为 10 次全对就晋级。默认门槛（空间可调高，不能调低）：

| 晋级 | 最少样本 | 条件 |
| --- | --- | --- |
| L0 → L1 或 L2 | 50 个有对照的影子用例 | 一致率下界 ≥ 0.85 时建议 L1，≥ 0.95 时建议 L2 |
| L1 → L2 | 100 次审批 | 批准率下界 ≥ 0.95，窗口内无事故 |
| L2 → L3 | 200 次提交 | （撤销 + 驳回）率上界 ≤ 0.02，无事故，线上评测分不低于空间基线 |
| L3 → L4 | 500 次提交且在 L3 满 60 天 | 复核不通过率上界 ≤ 0.01，撤销率上界 ≤ 0.01，无事故 |

达标后系统生成**转正申请**：一条通用审批（`subject_type = autonomy`，assignee = 负责人；目标等级 ≥ L3 时另加空间管理员），卡片内容是指标、置信区间、最近 5 条被驳回或撤销的例子。批准后等级生效、写审计；拒绝后 14 天内不再生成同一申请。

## 降级（TRU-10）

| 触发 | 动作 |
| --- | --- |
| 人撤销了该类别的动作（ADR-033 `action_undone`） | 降一级，最低 L1 |
| 抽检判定为「应撤销」 | 降一级并执行撤销 |
| 连续 3 次审批被驳回 | 降一级 |
| 标记事故 | 直接降到 L1 |
| 撤销熔断（ADR-033 TRU-08） | 该 Agent 全部类别降到 L1 |
| 授权书新版本降低了 `ceiling` | 取 min(当前, 新上限) |

降级立即生效，写 `trust_autonomy_event` 和审计，给负责人发 IM 卡片说明原因与证据链接。

## 事后抽检（L3、L4）

- 动作提交后按比例抽样，生成一条**不阻塞**的复核审批（`kind = review`），assignee 为负责人或其指定的复核人，有效期等于该动作的撤销期。
- 复核选项：「没问题」「应撤销」（触发 ADR-033 撤销）「有错但保留」（不撤销，但计入不通过率）。
- 撤销期过了还没复核，记为「未复核」，不计入通过率，连续 2 周未复核率超过 50% 时提醒负责人并冻结晋级。

## 影子上岗（TRU-11）

### 影子运行从哪里来

| 来源 | 做法 |
| --- | --- |
| 编排规则（主要） | 规则增加 `shadow = true`。在 `accept_mode = observe` 下，推断事件原本只记 `WOULD_ACCEPT`（ADR-020 修订第 2 条）；开启 `shadow` 后，同时创建一个影子 Run，派给 Runtime 一个 `origin = shadow` 的 Turn。显式事件照常由人处理 |
| 历史回放 | 选一批已结案的工单作为评测数据集（ADR-031 `eval_dataset`，来源 ONLINE / IMPORT），批次评测时被测 Agent 以影子模式运行，工具走预演 |

### 硬约束

- `origin = shadow` 的 Turn 在 ToolGateway 里**只能 `prepare` 不能 `commit`**，READ 工具照常执行。这条用 ArchUnit 规则和 `ShadowNeverCommitsIT` 守护，不依赖配置。
- 影子 Turn 不向 IM 外发消息（M2-03 的回答卡片和 `tell` 通知都跳过），只在负责人的「影子审阅」列表里可见。
- 影子 Turn 用量计入空间的评测预算（`purpose = EVALUATION`，与 ADR-031 相同），不占用户配额。

### 和人工结果比对

- **结构化比对**：工单结案后，编排的跟进订阅（ADR-020 修订第 8 条）会收到后续事件。规则可配置 `actual_mapping`（JSONPath），把结案事件映射成「人实际做了什么」：对象、字段、取值。与影子变更单逐字段比较，全部关键字段一致记为一致。
- **非结构化比对**：没有可映射字段的，用 ADR-031 的 LLM-as-a-Judge 评估器 `shadow-agreement` 判定影子建议与人的处理是否等价。
- 结案后 7 天仍没有对照数据的用例记为「无对照」，不进入一致率。

### 上岗报告

影子期满（默认 14 天或 50 个有对照用例，以后到为准）生成报告，发给负责人：按动作类别列出一致率及区间、不一致的典型例子（影子建议与人工处理并排）、人工处理耗时分布（作为 ADR-035 的实测基线），以及建议的初始等级。负责人确认后，规则切换为 `accept_mode = auto`，Agent 按确认的等级上岗。

## 档案页与审批台

- 档案页（ADR-032）的授权表每行显示当前等级、上限、距下一级还差什么（例如「L1 → L2：还需 37 次审批，当前批准率下界 0.93」）。
- 审批台增加两类条目：转正申请、抽检复核。抽检复核在飞书里以低打扰的汇总卡片每日推送一次。

## 接口

```java
public interface AutonomyApi {
    AutonomyGate.Decision decide(AutonomyContext ctx);                 // ToolGateway 调用
    List<AutonomyView> list(String agentId);                           // 每个动作类别的等级、上限、指标
    void markIncident(String actionId, String note);                   // 负责人或管理员标记事故
    void setLevel(String agentId, String actionClass, AutonomyLevel level, String reason);  // 仅降级，或批准转正申请时内部调用
    ShadowReport shadowReport(String agentId);

    record AutonomyContext(String agentId, String agentVersionId, String actionClass, String origin,
                           PolicyOutcome policy, ToolRisk risk, Reversibility reversibility, boolean estimated) {}
}
public enum AutonomyLevel { L0, L1, L2, L3, L4 }
```

## 数据模型

```sql
CREATE TABLE trust_autonomy (agent_id VARCHAR(64) NOT NULL, action_class VARCHAR(64) NOT NULL,
  level VARCHAR(4) NOT NULL, observe_until TIMESTAMPTZ, promotion_blocked_until TIMESTAMPTZ,
  update_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY (agent_id, action_class));
CREATE TABLE trust_autonomy_event (id VARCHAR(32) PRIMARY KEY, agent_id VARCHAR(64) NOT NULL,
  action_class VARCHAR(64) NOT NULL, kind VARCHAR(16) NOT NULL,     -- PROMOTE / DEMOTE / INCIDENT / RESET
  from_level VARCHAR(4), to_level VARCHAR(4), reason TEXT, evidence_json TEXT, actor VARCHAR(64),
  create_at TIMESTAMPTZ NOT NULL DEFAULT now());                    -- 只增
CREATE TABLE trust_autonomy_stats (agent_id VARCHAR(64) NOT NULL, action_class VARCHAR(64) NOT NULL,
  day DATE NOT NULL, stats_json TEXT NOT NULL, PRIMARY KEY (agent_id, action_class, day));
CREATE TABLE trust_shadow_case (id VARCHAR(32) PRIMARY KEY, agent_id VARCHAR(64) NOT NULL,
  orch_run_id VARCHAR(32), eval_run_id VARCHAR(32), turn_id VARCHAR(32) NOT NULL,
  proposed_json TEXT NOT NULL, actual_json TEXT, verdict VARCHAR(16),   -- AGREE / DISAGREE / NO_TRUTH
  judge VARCHAR(16), human_seconds INT, create_at TIMESTAMPTZ NOT NULL DEFAULT now(), judged_at TIMESTAMPTZ);
ALTER TABLE orch_rule_version ADD COLUMN shadow BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE orch_rule_version ADD COLUMN actual_mapping_json TEXT;
-- runtime_turn.origin 增加 shadow；runtime_approval.subject_type 增加 autonomy，kind 增加 review
```

ID 前缀：`aev` 等级事件、`shd` 影子用例。

## 工作项

| 编号 | 工作项 | 阶段 | 优先级 | 估算（人日） | 依赖 | 验收 |
| --- | --- | --- | --- | --- | --- | --- |
| TRU-09 | 等级 L1〜L4、判定表、`autonomy` 审批策略、观察期、事后抽检 | M3 | P0 | 6 | TRU-02、TRU-05 | G3：同一 Agent 两个类别分别为 L1、L3，判定结果符合判定表 |
| TRU-10 | 自动降级与通知 | M3 | P1 | 4 | TRU-07、TRU-09 | G3：一次撤销触发降级，负责人收到卡片 |
| TRU-11 | 影子运行：规则 `shadow`、`origin=shadow` 只预演、结构化与评估器比对、上岗报告 | M4 | P0 | 9（后端 7、前端 2） | ORC-03、EVO-02、TRU-09 | G4：影子 Run 不产生任何 commit；50 个用例后生成上岗报告 |
| TRU-12 | 指标汇总、Wilson 区间、转正申请 | M4 | P1 | 6（后端 4、前端 2） | EVO-03、TRU-09 | 达标后生成转正申请，批准后等级生效并留审计 |

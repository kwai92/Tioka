# ADR-035 — 价值账本与数字员工经营报表

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：TRU-13〜15（新增）
- 对应设计：设计 S（可信放权 D3）
- 方向文档：[Tioka 差异化能力方向：数字员工全生命周期](https://claude.ai/artifact/1HP9f4Rr9XmaiZqMMLRJjx)
- 相关：GOV-05 成本归集（M1-03）、[ADR-031](ADR-031-agent-eval-experience.md) 评测、[ADR-033](ADR-033-reversible-actions.md) 撤销、[ADR-034](ADR-034-autonomy-ladder.md) 影子基线
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

现有设计能回答「花了多少钱」（GOV-05 按会话、Agent、空间汇总模型成本），回答不了「换来了什么」。企业续费和扩大使用范围，靠的是后一个问题的答案。

本设计给每个数字员工记一本**价值账本**：每完成一件任务记一条账，写明这件事人工做要多久、这次人实际花了多久、花了多少钱、质量如何。CIO 和部门负责人每月收到一份经营报表，每个数字都能追溯到账本明细。

核心决定：

1. **以任务为记账单位**，不以 Turn 为单位。闲聊和单纯问答默认不记账，避免用调用次数冒充产出。
2. **人工基线必须标注来源**：实测、调研、估算三种，报表上原样显示。优先用影子期的实测（ADR-034）。
3. **账本只增不改**。撤销、基线修正都用冲销记录表达；报表只从账本汇总，不另算。

## 任务类型（TRU-13）

```java
public record TaskType(
    String code,                  // refund.review
    String name,                  // 「退款工单审核」
    String agentId,               // 归属的数字员工
    Binding binding,              // 哪些执行算一件任务，见下表
    Baseline baseline,
    String departmentId) {}       // 默认取授权书的部门，可覆盖

public record Baseline(
    int humanMinutes,             // 人工处理一件的平均分钟数
    String laborRateCode,         // 引用空间的人力费率表，不在任务上直接存单价
    Double humanErrorRate,        // 可空
    BaselineSource source,        // MEASURED / SURVEY / ESTIMATE
    String note,                  // 例如「2026-10 影子期 63 件中位数」
    Instant effectiveFrom) {}
```

| 绑定方式 | 一件任务 = | 适用 |
| --- | --- | --- |
| 编排规则 | 该规则的一个 Run 进入终态 | 工单类、事件驱动类（主要场景） |
| Flow 发布 | 一次 Flow 运行结束 | 流程类 |
| Agent 对话 + 标签 | 一个 Turn，且模型在回执或工具里声明了 `task_type` | 对话中完成的具体任务（如「生成一份周报」） |

授权书的 KPI（ADR-032 `KpiTarget`）通过 `taskTypeCode` 指向任务类型。

**人力费率**由空间管理员维护（`trust_labor_rate`：按岗位或部门的每小时成本），只有 `trust:ledger:admin` 能看具体数值，报表上展示折算后的金额。

## 记账

消费以下事件，每件任务结束时写一条账：

| 事件 | 来源 |
| --- | --- |
| `tioka.orch.run_finished.v1` | 编排 Run 进入终态（本设计新增；M2-02 回执生效时与 `orch_fact` 同事务写 outbox） |
| `tioka.flow.run_finished.v1` | M2-04 |
| `tioka.runtime.turn_finished.v1` | M2-03（带 `task_type` 的 Turn） |
| `tioka.trust.action_undone.v1` | ADR-033，生成冲销 |

一条账的字段与算法：

```text
outcome        = AUTO（全程无人参与）/ ASSISTED（有审批或复核）/ HANDED_OFF（转人工接手）/ FAILED / NOTHING
human_minutes  = 审批次数 × 每次审批分钟数（空间配置，默认 2）
               + 抽检复核次数 × 每次复核分钟数（默认 3）
               + HANDED_OFF 时按基线全额计
saved_minutes  = AUTO 或 ASSISTED：baseline.humanMinutes − human_minutes（可以为负，如实反映「审批比自己做还慢」）
                 HANDED_OFF、FAILED、NOTHING：0 − human_minutes（失败的成本也要入账）
cost_micros    = 该任务关联的全部 model_usage.cost_micros 之和（按 turn_id / orch_run_id 关联）
               + 工具单次成本（ToolDescriptor 可选声明，如付费 API）
               + 平台分摊（空间配置的每月固定费用 ÷ 当月任务数，月末一次性写分摊记录，默认 0）
quality        = 线上评测分（ADR-031，有则填）、是否被撤销、是否被投诉（点踩）
```

- 审批停留时长**不计入**人的工时：等审批的时间是人在做别的事，不是在做这件事。审批停留时长单独记录，作为「端到端时效」指标。
- 被撤销时写一条冲销：`saved_minutes` 取原值的相反数，再额外扣除撤销本身的人工（默认 5 分钟），`outcome = UNDONE`。
- 基线修改不回溯：新基线从 `effectiveFrom` 起生效，旧账不动；修改写审计，报表上标出基线变化点。

## 经营报表（TRU-14）

报表全部从 `trust_ledger` 汇总，夜间任务写月度汇总表 `trust_ledger_monthly`，页面按需下钻到明细。

**单个数字员工（档案页「表现」区块和月报）**

| 指标 | 算法 |
| --- | --- |
| 任务数 | 账本条数（不含冲销） |
| 自动化率 | AUTO ÷ 任务数 |
| 人工介入率 | （ASSISTED + HANDED_OFF）÷ 任务数 |
| 净节省工时 | Σ saved_minutes ÷ 60（含冲销与负值） |
| 净价值 | 净节省工时 × 费率 − Σ cost_micros |
| 单件成本 | Σ cost_micros ÷ 任务数 |
| 撤销率 | UNDONE ÷ 任务数 |
| 质量分 | 线上评测均分 |
| 端到端时效 | 事件到达到任务结束的中位数与 P90 |
| KPI 达成 | 授权书 KPI 目标与实际对比 |

**部门与全公司（经营看板）**：按部门、按数字员工排行；成本与净价值的月度趋势；基线来源构成（实测 / 调研 / 估算各占多少节省工时），让看报表的人知道数字有多硬。

**推送与导出**：每月 1 日给负责人推送单个数字员工月报卡片，给部门负责人推送部门汇总；看板支持导出 CSV 和 Excel，给财务和年度预算使用。

## 防虚报

| 风险 | 处理 |
| --- | --- |
| 拿调用次数冒充产出 | 只有绑定了任务类型的执行才记账；闲聊不记 |
| 基线拍脑袋 | 来源必须标注，报表展示来源构成；ESTIMATE 来源的节省在看板上以浅色单独显示 |
| 做错了还算节省 | 撤销即冲销，并扣撤销的人工 |
| 失败不算成本 | FAILED、HANDED_OFF 同样计成本和人工，净值可为负 |
| 事后改数 | 账本只增；基线、费率修改写审计；报表的每个数字可下钻到明细并复算（G4 用例） |

## 接口

```java
public interface LedgerApi {
    TaskTypeView upsertTaskType(UpsertTaskTypeCmd cmd);
    AgentScorecard scorecard(String agentId, YearMonth month);
    List<DepartmentRollup> rollup(YearMonth from, YearMonth to, String departmentId);
    Page<LedgerEntryView> entries(LedgerQuery query);             // 下钻明细
    ExportRef export(LedgerQuery query, ExportFormat format);      // CSV / XLSX
}
```

接口权限：Agent 负责人看自己负责的数字员工；部门负责人看本部门；`trust:ledger:admin` 看全部和费率。

## 数据模型

```sql
CREATE TABLE trust_task_type (id VARCHAR(32) PRIMARY KEY, code VARCHAR(64) NOT NULL UNIQUE,
  name VARCHAR(255) NOT NULL, agent_id VARCHAR(64) NOT NULL, department_id VARCHAR(64),
  binding_kind VARCHAR(16) NOT NULL, binding_ref VARCHAR(64),          -- ORCH_RULE / FLOW_PUBLICATION / AGENT_TAG
  create_at TIMESTAMPTZ NOT NULL DEFAULT now(), update_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  create_by VARCHAR(64) NOT NULL, update_by VARCHAR(64) NOT NULL, deleted BOOLEAN NOT NULL DEFAULT FALSE);
CREATE TABLE trust_baseline (id VARCHAR(32) PRIMARY KEY, task_type_id VARCHAR(32) NOT NULL,
  human_minutes INT NOT NULL, labor_rate_code VARCHAR(64) NOT NULL, human_error_rate DOUBLE PRECISION,
  source VARCHAR(16) NOT NULL, note TEXT, effective_from TIMESTAMPTZ NOT NULL,
  create_by VARCHAR(64) NOT NULL, create_at TIMESTAMPTZ NOT NULL DEFAULT now());        -- 只增
CREATE TABLE trust_labor_rate (code VARCHAR(64) PRIMARY KEY, name VARCHAR(255) NOT NULL,
  hourly_micros BIGINT NOT NULL, update_by VARCHAR(64) NOT NULL, update_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE trust_ledger (id VARCHAR(32) PRIMARY KEY, task_type_id VARCHAR(32) NOT NULL,
  agent_id VARCHAR(64) NOT NULL, department_id VARCHAR(64), baseline_id VARCHAR(32) NOT NULL,
  subject_kind VARCHAR(16) NOT NULL, subject_id VARCHAR(64) NOT NULL,  -- ORCH_RUN / FLOW_RUN / TURN / ALLOCATION
  outcome VARCHAR(16) NOT NULL,                                        -- AUTO / ASSISTED / HANDED_OFF / FAILED / NOTHING / UNDONE
  reverses_id VARCHAR(32),                                             -- 冲销时指向原记录
  human_minutes NUMERIC(10,2) NOT NULL, saved_minutes NUMERIC(10,2) NOT NULL,
  cost_micros BIGINT NOT NULL, quality_score DOUBLE PRECISION, approvals INT NOT NULL DEFAULT 0,
  wait_seconds INT, e2e_seconds INT, occurred_at TIMESTAMPTZ NOT NULL,
  create_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (subject_kind, subject_id, outcome));                         -- 同一任务同一结局只记一次，消费幂等
CREATE INDEX idx_ledger_agent_time ON trust_ledger (agent_id, occurred_at);
CREATE INDEX idx_ledger_dept_time ON trust_ledger (department_id, occurred_at);
CREATE TABLE trust_ledger_monthly (agent_id VARCHAR(64) NOT NULL, month DATE NOT NULL,
  department_id VARCHAR(64), summary_json TEXT NOT NULL, computed_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (agent_id, month));
```

ID 前缀：`tt` 任务类型、`bl` 基线、`led` 账本。

## 工作项

| 编号 | 工作项 | 阶段 | 优先级 | 估算（人日） | 依赖 | 验收 |
| --- | --- | --- | --- | --- | --- | --- |
| TRU-13 | 任务类型、基线、费率；记账消费者；撤销冲销；`orch.run_finished` 事件 | M4 | P0 | 9 | GOV-05、ORC-03、TRU-07 | G4：一个编排 Run 结束后账本有且只有一条记录，撤销后出现冲销 |
| TRU-14 | 月度汇总、档案页表现区块、经营看板、月报推送、导出 | M4 | P1 | 8（后端 3、前端 5） | TRU-13 | G4：看板上任一数字可从账本明细逐条复算 |
| TRU-15 | 影子期实测基线：从影子用例的人工耗时生成 MEASURED 基线建议 | M4 | P2 | 3 | TRU-11、TRU-13 | — |

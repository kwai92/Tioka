# ADR-038 — 多智能体团队：规划、分派、协商、汇总

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：MAS-03〜07（新增）
- 对应设计：设计 Q（多智能体与运行时权限）
- 阅读版：[Tioka 多智能体自主协作与权限管控设计](https://claude.ai/artifact/Qy9taJVV8cz2B7VBGewkJA)
- 相关：[ADR-027](ADR-027-runtime-sandbox-subagent.md) 子 Agent、[ADR-020](ADR-020-orchestration.md) 编排、[ADR-034](ADR-034-autonomy-ladder.md) 自治阶梯、[ADR-039](ADR-039-delegation-grant.md) 委派授予、[ADR-040](ADR-040-dataset-policy-data-label.md) 数据标签；产物格式引用 ADR-037（结构化产物，随商业租赁合同场景另行入库）
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

ADR-027 已定子 Agent 委派（`agent.delegate`），并把「Agent Team（任务板与角色协作）」留给 M4 之后单独设计。本篇就是这份设计。核心决定有四条：

1. **不新造执行引擎**。团队的每个子任务就是一个子 Turn，等待沿用 `WAITING_CHILD`，派工沿用 `rt:turns`，人审沿用通用审批表。
2. **团队是一种 Agent**。`AgentSpec.profile.type = TEAM`，与普通 Agent 一样有版本、ACL、授权书和自治等级。用户和编排看到的是一个 Agent。
3. **模型决定路径，平台决定边界**。规划、重规划、仲裁由模型做；角色范围、深度、并行数、预算、产物格式、必审检查点由平台校验。
4. **协商是黑板上的结构化冲突，不是自由群聊**。子 Agent 只交结构化产物；同一主键上的矛盾结论由仲裁者处理，有轮数上限，超限转人。

## 协作拓扑

| 拓扑 | 适用 | 实现 |
| --- | --- | --- |
| 主管-专家（默认） | 任务可拆、子问题不确定 | 本 ADR：TeamSpec + 任务图 + 黑板 |
| 制作-复核 | 输出需要质量门槛 | 任务图中的 `verify` 节点，复核者为另一 Agent 或 ADR-031 评测规则 |
| 固定流水线 | 步骤确定、要可视化编辑 | Flow（ADR-019）；Flow 里的 Agent 节点计一层委派 |
| 事件接力 | 跨部门、跨名义、跨天 | 编排回执 `emit` 派生新事件与新工单（ADR-020） |

去中心的自由对话式协作不做：轮次不可控、责任不可归属、成本不可预算。

## TeamSpec（MAS-04）

```java
public record TeamSpec(
    String leadAgentVersionId,            // 主管：规划、重规划、仲裁、汇总
    List<Role> roles,
    PlanPolicy plan,
    List<String> artifactTypes,           // 黑板上允许的产物类型，引用 ADR-037 的 Schema
    NegotiationPolicy negotiation,
    TeamBudget budget,
    TeamLimits limits) {}

public record Role(String code,
                   String agentVersionId,          // 已发布专家；与 templateVersionId 二选一
                   String templateVersionId,       // 临时子 Agent 的模板（MAS-06）
                   List<String> consumes, List<String> produces,
                   int maxInstances) {}

public record PlanPolicy(PlanMode mode,             // TEMPLATE / FREE
                         String templateJson,       // TEMPLATE 时的任务图骨架
                         Set<String> mandatoryNodes,// 主管不能删除的节点
                         boolean requireApproval) {}// 计划审批，另见下文「人审」
public record NegotiationPolicy(String arbiterRole, // 缺省为主管
                                int maxRounds) {}   // 默认 2
public record TeamBudget(long maxTokens, long maxCostMicros, Duration maxWallTime, int maxTasks) {}
public record TeamLimits(int maxDepth,              // 默认 2，平台上限 3
                         int maxParallel) {}        // 默认 3，平台上限 8
```

发布校验（AST-02 扩展）：每个角色引用的 Agent / 模板已发布且发布者有 USE；`produces` 中的类型在 `artifactTypes` 内；`maxDepth`、`maxParallel` 不超过平台上限；团队授权书的 `delegable`（ADR-039）覆盖各角色授权书中所有写动作类别，否则那些类别在团队内不可用并给出警告。

## 四个动作

1. **规划**：主管的第一个 Turn 调内置工具 `team.plan(graph)` 交出任务图（DAG）。节点字段：`role`、`inputs`（产物 ID 或上游节点）、`expectedType`、`narrow`（授予收窄，ADR-039）、`budgetTokens`。平台校验角色在 TeamSpec 内、无环、预算之和不超团队预算、产物类型已声明、`mandatoryNodes` 都在。失败则把校验错误作为新输入要求重写，最多 2 次（与 ADR-020 回执重写同一机制）。`TEMPLATE` 模式下主管只能填参数和追加节点，不能改骨架。
2. **分派**：`TeamDispatcher` 把入度为 0 的节点作为子 Turn 写入 `rt:turns`，主管 Turn 进入 `WAITING_CHILD`。任务行在 PostgreSQL，状态更新用乐观锁，任何实例都能接着派，满足双实例闸门。
3. **协商**：子 Agent 用 `team.publish(type, subjectKey, payload, evidence)` 写产物，平台按 Schema 校验并打数据标签（ADR-040）。同一 `subjectKey` 出现矛盾结论（由产物类型声明的比较字段判定，如 Finding 的 `verdict`）时生成冲突记录，派给仲裁者。仲裁者可要求双方补证一轮；超过 `maxRounds` 转人工仲裁。
4. **汇总**：全部叶子节点终态后唤醒主管。最终结果中每条结论必须引用产物 ID，引用不存在的产物时校验失败。配了 `verify` 节点的，汇总先经复核再交付。

**重规划**：节点失败、产物不合格、或新产物揭示新问题时唤醒主管，可增删尚未开始的节点（`plan_rev + 1`）。单个团队 Run 最多重规划 3 次。每次规划、重规划、仲裁、升级各写一条 `team_decision`，带理由和前后 diff，进 why 时间线。

## 子 Agent 生命周期（MAS-03、MAS-06）

| 来源 | 定义者 | 主管能改什么 | 能力上限 |
| --- | --- | --- | --- |
| 已发布专家 | 构建者，走发布审批 | 只能传任务与输入 | 该专家版本的 AgentSpec 与授权书 |
| 临时子 Agent | 从已发布「子 Agent 模板」派生 | 指令可生成；工具、Skill、知识库、数据集只能取模板的子集 | 模板 ∩ 父授予 |

**主管可以生成提示词，但不能生成能力。** 临时子 Agent 不进资产库、不可被他人使用；派生规格写在 `team_task.spec_json` 并算哈希。动作类别与自治等级沿用模板的，ADR-034 的晋级证据按模板积累。

```text
PLANNED ──就绪──▶ SPAWNED（建子 thread、签发子授予、划拨预算）──▶ RUNNING ⇄ WAITING_APPROVAL
   │                                                                  │
   └──重规划删除──▶ SKIPPED                     DONE / FAILED / CANCELLED ──▶ REAPED
```

**回收（REAPED）** 与终态同事务：子授予置为失效，之后用它的任何调用被拒；未用预算退回团队；子 thread 的沙箱立即释放（不等 15 分钟空闲）；工作盘中被产物引用的文件提升为团队产物，其余 7 天后清理；未决审批关闭。父 Turn 取消时级联取消（ADR-027 已定）。

## 失败处理

| 失败 | 处理者 | 动作 |
| --- | --- | --- |
| 工具报错、超时 | 子 Agent | 按工具重试策略 |
| 子任务 FAILED 或产物不合 Schema | 派工器 | 同角色重试 1 次（可换路由计划）；仍失败唤醒主管重规划 |
| 预算耗尽 | 平台 | 取消未完成子任务；主管用已有产物交部分结果，并发 `ask` 询问是否追加 |
| 已对外产生副作用后中断 | 人 | `UNCERTAIN`（ADR-020）；可撤销的按 ADR-033 撤销 |
| 冲突超轮数 | 人 | 仲裁卡片，展示双方产物与证据 |
| 重规划超 3 次 | 人 | 团队 Run 结局 FAILED，卡片说明卡在哪个节点 |

## 人审

全部复用通用审批表与 ADR-034 的判定，新增两种 `subject_type`：

- `team_plan`：团队的「规划」动作类别（`team.plan`）处于 L1 时，任务图先审后跑；L2 及以上只在计划偏离模板或预算超过团队预算 50% 时审。
- `team_conflict`：冲突超轮数时的人工仲裁。

子 Agent 自己的写操作按各自动作类别的等级审批；审批卡片显示委派链（如「合同审查团队 → 规则判定专家」）；四眼原则对链根的发起人和 `on_behalf_of` 都生效。

## 编排与回执

团队可以是编排派工的目标。编排看到的仍是一个 Agent；ADR-020 的回执校验器（含业务场景的必审检查点覆盖校验）在主管交回执时执行，不通过则把缺口作为新输入还给主管，而不是结束 Run。

## 可观测

团队 Run 一个根 span，每个子任务一个子 span（GOV-08）。轨迹视图按任务图展开，可点进任意子 thread（MAS-07）。`team_decision` 与 `orch_fact` 在 why 时间线合并展示。

## 数据模型

```sql
CREATE TABLE team_run (id VARCHAR(32) PRIMARY KEY, team_agent_version_id VARCHAR(64) NOT NULL,
  lead_thread_id VARCHAR(64) NOT NULL, root_grant_id VARCHAR(32) NOT NULL, plan_rev INT NOT NULL DEFAULT 0,
  replans INT NOT NULL DEFAULT 0, status VARCHAR(16) NOT NULL,     -- PLANNING / RUNNING / SUMMARIZING / DONE / FAILED / CANCELLED
  budget_json TEXT NOT NULL, used_json TEXT, create_at TIMESTAMPTZ NOT NULL DEFAULT now(), ended_at TIMESTAMPTZ);
CREATE TABLE team_task (id VARCHAR(32) PRIMARY KEY, team_run_id VARCHAR(32) NOT NULL, role_code VARCHAR(64) NOT NULL,
  depends_on_json TEXT, inputs_json TEXT, expected_type VARCHAR(64), narrow_json TEXT, spec_json TEXT, spec_hash VARCHAR(64),
  budget_tokens INT, status VARCHAR(16) NOT NULL,    -- PLANNED / SPAWNED / RUNNING / WAITING_APPROVAL / DONE / FAILED / SKIPPED / CANCELLED / REAPED
  thread_id VARCHAR(64), grant_id VARCHAR(32), attempts INT NOT NULL DEFAULT 0, plan_rev INT NOT NULL, row_version INT NOT NULL DEFAULT 0);
CREATE INDEX idx_team_task_run ON team_task (team_run_id, status);
CREATE TABLE team_artifact (id VARCHAR(32) PRIMARY KEY, team_run_id VARCHAR(32) NOT NULL, task_id VARCHAR(32),
  type VARCHAR(64) NOT NULL, subject_key VARCHAR(255), payload_json TEXT NOT NULL, evidence_json TEXT,
  data_label VARCHAR(16) NOT NULL, author_agent_version_id VARCHAR(64) NOT NULL,
  create_at TIMESTAMPTZ NOT NULL DEFAULT now());                    -- 只增，修正写新行
CREATE TABLE team_conflict (id VARCHAR(32) PRIMARY KEY, team_run_id VARCHAR(32) NOT NULL, subject_key VARCHAR(255) NOT NULL,
  artifact_ids_json TEXT NOT NULL, round INT NOT NULL, status VARCHAR(16) NOT NULL,  -- OPEN / RESOLVED / ESCALATED
  resolution_json TEXT, approval_id VARCHAR(32));
CREATE TABLE team_decision (team_run_id VARCHAR(32), seq INT, kind VARCHAR(16) NOT NULL,  -- PLAN / REPLAN / ARBITRATE / ESCALATE
  reason TEXT, diff_json TEXT, at TIMESTAMPTZ NOT NULL, PRIMARY KEY (team_run_id, seq));
-- 通用审批表的 subject_type 增加 team_plan、team_conflict
```

ID 前缀：`trun` 团队 Run、`ttsk` 任务、`tart` 产物、`tcfl` 冲突。

## 工作项

| 编号 | 工作项 | 阶段 | 优先级 | 人日 | 依赖 | 验收 |
| --- | --- | --- | --- | --- | --- | --- |
| MAS-03 | RT-12 提前到 M3；子任务回收（授予失效、预算退回、沙箱释放）；`on_behalf_of` | M3 | P0 | 3 | RT-08、MAS-01 | G3：子任务结束后用其授予的调用被拒 |
| MAS-04 | TeamSpec、`team.plan`、TeamDispatcher、重规划 | M4 | P1 | 8 | MAS-01 | G4：双实例下任务图跨实例派完 |
| MAS-05 | 黑板、冲突仲裁、计划审批与仲裁卡片 | M4 | P1 | 6 | MAS-04、ADR-037 | 超轮数冲突生成仲裁卡片 |
| MAS-06 | 临时子 Agent 模板 | M4 | P2 | 4 | MAS-04 | 模板外的工具无法被生成 |
| MAS-07 | 轨迹视图按任务图展开、why 时间线（前端） | M4 | P1 | 4 | MAS-04 | — |

M3 期间，固定步骤的多 Agent 场景（如商业租赁合同审查）先用 Flow 骨架过渡，每个节点即同一个专家 Agent；MAS-04 就绪后改由主管自主规划，专家 Agent 与产物格式不变。

## 修订：Agent 架构调研落地（2026-11-06）

来由：[调研 01](../research/01-Agent与多Agent架构调研.md)、[调研 02](../research/02-Agent执行策略与执行器插件调研.md)；负责人同意按调研建议设计，见 ADR-029 第 67 条。

依据调研 01 第 3 节（Anthropic 多 Agent 研究系统、Cognition、MAST 失败分类），在不改变主体的前提下做四处修订，合并为工作项 MAS-08（M4 P1，后端 3 人日，随 MAS-04、MAS-05、EVO-06）：

1. **任务简报必填结构**：`team.plan` 的节点增加 `objective`（目标）、`doneWhen`（完成标准）、`outOfScope`（不做什么）、`sources`（建议使用的工具或资料，可空）四个字段，前三个必填，平台校验非空；子 Agent 的首条输入按固定模板由这些字段与 `inputs` 组成。
2. **共享决定日志**：黑板增加平台内置产物类型 `Decision`（决定或假设：内容、理由、作者、影响范围 `subjectKey` 前缀）。子 Agent 与主管都可发布；派发节点时，平台把与该节点输入的 `subjectKey` 前缀匹配的有效 `Decision` 附在输入中；新产物与已有 `Decision` 矛盾时按冲突处理（比较规则由 `Decision` 类型声明）。
3. **投入分级**：TeamSpec 增加可选的「复杂度档位」表（如 S / M / L → 最多任务数 1 / 4 / 10），主管在 `team.plan` 中声明档位，平台校验任务数不超过该档位上限且不超过 `TeamBudget.maxTasks`；平台预置的主管提示词包含档位说明。
4. **上线基线**：见 ADR-031 修订（团队发布门禁同时跑单 Agent 基线臂）。

另：

- **Role 来源类型**：Role 增加「来源类型」字段，取值先只有已发布专家与临时模板；远端 A2A Agent 作为第三种来源预留（[ADR-045](ADR-045-a2a-interop.md)，M4 之后）。
- **租赁合同审查迁移**：MAS-04 就绪后，M3-04 的审查团队迁移到 TeamSpec 时使用 `TEMPLATE` 模式，主管、复核员、判断员、质检员四个角色的节点列入 `mandatoryNodes`；质检员的「与判断员不同的模型族」改为 `verify` 节点上的模型多样性约束（ADR-024 修订），不再只是配置约定。`FREE` 模式留给开放式任务。
- **与执行策略的分工**：同一个 Agent 能做完、不需要不同权限或并行的多步任务，用 ADR-046 的 `PLAN_EXECUTE`，不建团队。

## 修订：记忆与上下文工程调研落地（2026-11-06）

来由：[调研 03](../research/03-Agent记忆与上下文工程调研.md)；按负责人「有选项时按推荐方案直接写设计」的要求落地，见 ADR-029 第 68 条。

- **子任务的上下文**：节点输入即任务简报，子 Agent 默认 `ISOLATED`，不继承主管的历史；回传超过上限时先发布为黑板产物再回传「摘要 + 产物引用」（[ADR-048](ADR-048-context-engineering.md) 第 3.1 节）。
- **黑板是团队级的共享工作记忆**，决定日志是其中的共享决定；两者都不写入长期记忆。团队成员读团队所在空间的空间记忆，不能写记忆（[ADR-050](ADR-050-memory-system.md) 第 7 节）。

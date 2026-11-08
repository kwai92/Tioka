# ADR-046 — Agent 执行策略

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-06
- 覆盖工作项：EXE-01〜04、FE-29（新增）
- 来由：[调研 02](../research/02-Agent执行策略与执行器插件调研.md) 第 1、4 节，选项 I0〜I3；ADR-029 第 67 条
- 相关：[ADR-016](ADR-016-runtime-kernel.md)、[ADR-020](ADR-020-orchestration.md)、[ADR-023](ADR-023-agent-skill-assets.md)、[ADR-024](ADR-024-model-hub-routing.md)、[ADR-027](ADR-027-runtime-sandbox-subagent.md)、[ADR-031](ADR-031-agent-eval-experience.md)、[ADR-037](ADR-037-structured-outputs.md)、[ADR-038](ADR-038-multi-agent-team.md)、[ADR-043](ADR-043-flow-orchestration-detail.md)
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。
- 写法说明：只用文字和表格，不附代码。

## 背景

ADR-016 只有一种执行循环（模型 → 工具 → 模型，本质是原生工具调用实现的 ReAct）。业界已分化出先规划后执行、反思、代码即动作、长时目标循环等策略，论文报告在省 token、降延迟、提准确率上各有收益（调研 02 第 1.1 节）。Tioka 需要让「单个 Agent 内部怎么推进」可选，同时不能为每种策略另造运行时。

## 决定

1. **执行策略是 Runtime 内的可插拔 SPI**，由 AgentSpec 的 `execution` 段选择，随 Agent 版本冻结。默认 `REACT`，即现有循环，行为不变。
2. **策略只决定「下一步」，不碰边界**。策略替换 ADR-016 循环中「组装本步提示 → 调模型 → 解释结果」这一段；选模（ADR-024）、工具管线（ADR-033、ADR-039）、审批挂起、取消与插话、预算、item 落库、流式推送一律不变。策略只能经 ModelGateway 调模型、经 ToolGateway 调工具。
3. **新增 item 类型** `plan` 与 `reflection`，轨迹可区分规划、执行、反思。
4. **统一计量**：规划、执行、反思中的每次模型调用都计入 `maxSteps` 与 Turn 预算。
5. **首批在 M4 做 `PLAN_EXECUTE` 与 `REFLECT`**；`CODE_ACT`、`GOAL_LOOP` 在 M4 之后；树搜索不做。M3 只预留契约。

## 1. AgentSpec 的 `execution` 段

| 字段 | 说明 | 默认 |
| --- | --- | --- |
| 策略 | `REACT` / `PLAN_EXECUTE` / `REFLECT` / `CODE_ACT` / `GOAL_LOOP` | `REACT` |
| 策略参数 | 各策略的参数（第 2 节），按策略 Schema 校验 | 空 |
| 执行器 | `NATIVE` 或外部执行器引用（[ADR-047](ADR-047-external-executor.md)，M4 之后） | `NATIVE` |
| 允许运行时覆盖 | 是否允许调用方在单次 Turn 指定其他策略 | 否 |

M3（EXE-01）只开放 `REACT` + `NATIVE`，其他取值发布校验拒绝。这样 M4 增加策略时不需要迁移快照格式。

## 2. 策略

### 2.1 REACT（现有）

ADR-016 的循环，无参数。

### 2.2 PLAN_EXECUTE

| 项 | 规则 |
| --- | --- |
| 规划 | 第 1 步要求模型按「计划 Schema」（登记在 ADR-037 登记表）输出：目标、步骤列表（每步：说明、预期产出、可能用到的工具）、完成标准。写一条 `plan` item（版本 1） |
| 执行 | 之后每步的提示包含计划与各步状态，模型执行当前步，可调用工具；步骤完成时模型调用内置工具 `plan.update` 标记状态（完成 / 失败 / 跳过）与简短结果 |
| 重规划 | 当前步失败、或模型调用 `plan.update` 声明「计划需调整」时，回到规划，写 `plan` item 新版本 |
| 结束 | 全部步骤终态后，模型输出最终答复；计划中「完成标准」写入最终 item 的元数据供评测使用 |
| 参数 | 最大步骤数（默认 8，上限 12）；最大重规划次数（默认 2）；规划用路由组、执行用路由组（ADR-024 组名，可不同，用于「强模型规划、弱模型执行」）；计划是否需要人审（默认否；为是时规划后以 kind=card 审批挂起，复用 ADR-034 的 `team.plan` 判定规则） |
| 校验 | 计划步骤里提到的工具必须在本 Turn 授予内，否则该步标记为不可执行并触发一次重规划 |

与 ADR-038 的分工：同一个 Agent 能做完、不需要不同权限或并行的多步任务用 `PLAN_EXECUTE`；需要不同角色、权限收窄或并行时用团队。

### 2.3 REFLECT

| 项 | 规则 |
| --- | --- |
| 流程 | 按 REACT 执行到模型给出最终答复；在答复交付前，用引用的评估器打分；通过则交付；不通过则写一条 `reflection` item（评分、未通过项、改进要求），作为新输入继续执行 |
| 评估器 | 引用 ADR-031 评估器 SPI 中已登记的评估器（规则、LLM-as-a-Judge）。评估调用 purpose=EVALUATION，计入本 Turn 预算 |
| 参数 | 评估器引用（必填，1〜3 个）；通过阈值；最大反思轮数（默认 1，上限 3）；用尽后的处理：`DELIVER_WITH_WARNING`（交付并标注未通过项，默认）或 `FAIL` |
| 结构化输出 | Agent 声明了输出契约（ADR-037）时，Schema 校验先于评估器执行，Schema 修复次数不计入反思轮数 |
| 与经验库 | 反思内容可作为 ADR-031 经验提取的输入（失败组），不直接写经验 |

### 2.4 CODE_ACT（M4 之后）

模型输出代码，在 RT-11 沙箱执行。代码中调用平台工具时经「工具桥」回到 ToolGateway，逐次走授予、Cedar、审批；需要审批时 Turn 挂起，代码执行在审批期间暂停（沙箱进程保持，超过工具调用时限则失败）。没有沙箱的部署不能选用。

### 2.5 GOAL_LOOP（M4 之后）

跨多次 Run 推进一个目标，组合已有能力，不新造调度器：

- 目标与完成标准由编排规则或用户给出，写入工单；完成标准须可检查（清单项、测试、评估器分数）。
- 首次 Run 在工作盘（ADR-027）创建「目标清单」（JSON，每项只允许修改状态字段）与进度日志；之后每次 Run 先读清单与日志，只推进一项，收工更新。
- 平台在每次 Run 结束时校验清单结构未被改坏（只允许状态字段变化），否则该 Run 记为失败。
- 未完成时回执 `later`（ADR-020）安排下一次；全部通过时 `resolve`。
- 最大 Run 数、总预算、总时长由规则配置，超限转人。

## 3. 轨迹与可观测

| item 类型 | 内容 | 界面 |
| --- | --- | --- |
| `plan` | 计划版本号、步骤与状态、完成标准 | 轨迹视图顶部显示当前计划，步骤状态随 `plan.update` 实时变化；版本间可对比 |
| `reflection` | 轮次、各评估器分数、未通过项、改进要求 | 折叠展示在对应答复之前 |

链路追踪中，规划、每个步骤、每轮反思各一个 span。ADR-031 轨迹导出带上这两类 item。

## 4. 评测

ADR-031 实验的变量增加「执行策略与参数」。建议每个打算切换策略的 Agent 先用回归数据集跑 REACT 与新策略两组实验，报告质量、步数、token、延迟后再发布。

## 5. 数据模型增量

| 对象 | 内容 |
| --- | --- |
| AgentSpec | 新增 `execution` 段（第 1 节） |
| `runtime_item.type` | 增加 `plan`、`reflection` |
| 内置工具 | `plan.update`（仅 PLAN_EXECUTE 下出现在工具列表） |
| Schema 登记表 | 预置「计划」「反思」两个 Schema（ADR-037） |

## 6. 工作项

| 编号 | 工作项 | 阶段 | 优先级 | 人日 | 依赖 | 验收 |
| --- | --- | --- | --- | --- | --- | --- |
| EXE-01 | 契约预留：AgentSpec `execution` 段（只允许 REACT + NATIVE），`plan`、`reflection` item 类型登记 | M3 | P1 | 后端 0.5 | AST-01 | 其他取值发布校验拒绝 |
| EXE-02 | 执行策略 SPI；`PLAN_EXECUTE`（含 `plan.update`、重规划、规划与执行分组选模）；`REFLECT`（接评估器 SPI） | M4 | P1 | 后端 8 | EVO-02、MH-06、SCH-01 | 同一回归集上两种策略都能跑通并出实验报告；策略内的模型调用全部计入 Turn 预算 |
| FE-29 | 轨迹视图展示计划与反思；Studio 选择策略与参数 | M4 | P1 | 前端 3 | EXE-02 | — |
| EXE-03 | `CODE_ACT` 与工具桥 | M4 之后 | P2 | 后端 5 | RT-11、EXE-02 | 代码里调用授予外工具被拒 |
| EXE-04 | `GOAL_LOOP`（目标清单、结构校验、`later` 续跑、上限） | M4 之后 | P2 | 后端 5，前端 2 | EXE-02、ORC-03 | 清单被改坏的 Run 记为失败 |

## 修订：记忆与上下文工程调研落地（2026-11-06）

来由：[调研 03](../research/03-Agent记忆与上下文工程调研.md)；按负责人「有选项时按推荐方案直接写设计」的要求落地，见 ADR-029 第 68 条。

- **计划复述**：`PLAN_EXECUTE` 下当前计划在每个 Turn 的本轮尾部复述；`plan.update` 的结果不参与工具结果清理；压缩时最新版本的 `plan` item 原样保留（[ADR-048](ADR-048-context-engineering.md) 第 2.3、2.4 节）。
- `REFLECT` 的反思内容不写记忆；`GOAL_LOOP` 跨 Run 的进度靠工作盘文件，不靠记忆（[ADR-050](ADR-050-memory-system.md) 第 7 节）。

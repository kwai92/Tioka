# Agent 执行策略与执行器插件：调研与设计建议

- 日期：2026-11-06
- 状态：调研报告 + 设计建议（背景材料，不是执行依据）。第 6 节的选项经负责人决定后，按 [ADR-030](../adr/ADR-030-anti-drift-process.md) 写成 ADR 并修订 34 号计划才生效。
- 缘起：负责人在 [01 号调研](01-Agent与多Agent架构调研.md) 之后追问：ReAct、Loop 等多种执行方式，以及类似 DSH 的插件模式，有没有深入调研与设计。结论是**没有**：现有设计只有 ADR-016 的一种执行循环，也没有「外部 Agent 作为执行器」的插件机制。本文补上。
- 写法：只有文字和表格，不含代码。引用编号见文末，资料于 2026-11-06 联网读取，读不到原文之处标「未核实」。

---

## 0. 结论摘要

1. **Tioka 现在只有一种执行方式**：ADR-016 的「模型调用 → 工具调用 → 再调模型」循环（最多 20 步、并行 4 个工具），本质就是用原生工具调用实现的 ReAct [1]。多 Agent（ADR-038）、Flow（ADR-043）、编排（ADR-020）都建立在它之上，但「单个 Agent 内部怎么推进」不可选。
2. **业界的执行策略已分化成 6 类**：ReAct、先规划后执行（Plan-and-Execute / ReWOO / LLMCompiler）、反思（Reflexion / 制作-复核）、代码即动作（CodeAct）、树搜索（LATS）、长时目标循环（跨多次会话推进）[1]〜[7]。论文报告的收益集中在**省 token、降延迟**（ReWOO 5 倍 token 效率，LLMCompiler 延迟最多快 3.7 倍、成本最多省 6.7 倍）和**提准确率**（Reflexion HumanEval 91%、CodeAct 成功率最多 +20%）。这些都是论文作者在基准上的自报数字，Tioka 场景的实际收益要用 ADR-031 实验验证。
3. **建议把「执行策略」做成 Runtime 内的可插拔 SPI**（选项 I），先做三种：ReAct（现有）、先规划后执行、反思；CodeAct 等 M4 沙箱就绪；长时目标循环复用编排的 `later` 与工作盘进度文件；树搜索不做。核心约束：**所有策略共用同一个 Turn 状态机、item 落库、ToolGateway 管线、审批、授予与预算**，策略只决定「下一步调什么」，不能绕开平台边界。
4. **「插件模式」有两层意思，Tioka 只有一层。** 平台扩展插件（工具、Flow 节点、领域包、决策器、IM 渠道等）在 ADR-026 插件宿主与各 SPI 里已经有了；DeepSeek Harness 那种「一切皆插件」[8] 里的**执行器插件**，也就是 dsh-superagent / autoloop 把 Claude Code、Codex、ACP 兼容 Agent、任意命令行 Agent 当作「干活的人」接进来 [9][10]，Tioka 没有。
5. **建议设计「外部执行器」SPI**（选项 J）：Tioka 继续负责接单、身份、授予、审批、预算、审计、回执；外部 Agent 只在沙箱里干活。接入顺序为 ACP（一个协议覆盖 Claude、Codex、Gemini CLI、Qwen Code 等）→ 通用命令行 → Codex app-server。代价是这类执行的轨迹粒度更粗、内置工具只能靠沙箱约束，因此自治等级有上限。它适合编码类、文档处理类数字员工，**不建议用于租赁合同审查**这类要求逐步留痕的场景。
6. **M3 不需要实现任何一项**，只建议在 M3 的 AgentSpec 契约里预留 `execution` 字段（约 0.5 人日），避免 M4 再迁移快照格式。

---

## 1. 执行策略调研

### 1.1 六类执行策略

| 策略 | 做法 | 公开数据 | 代价与局限 | 代表实现 |
| --- | --- | --- | --- | --- |
| **ReAct** | 推理与行动交替：想一步、调一次工具、看结果再想 [1] | 原论文：ALFWorld、WebShop 上比模仿学习与强化学习方法成功率分别高 34%、10%，只用 1〜2 个示例 [1] | 每步都要调大模型；长任务容易跑偏或兜圈 | OpenAI Agents SDK、Claude Agent SDK、Spring AI Alibaba ReactAgent [12]、AgentScope ReActAgent、**Tioka ADR-016** |
| **先规划后执行** | 规划器先产出多步计划，执行器逐步执行，结束后可重规划 [2] | LangChain 归纳的收益：更快（不必每次工具调用都过大模型）、更省（子任务用小模型）、更好（规划时通盘考虑）；**该文未给数字** [2] | 计划可能过时；仍是串行 | LangGraph plan-and-execute 示例 [2] |
| ReWOO | 规划时用变量占位引用后续观察结果，执行阶段不再回到规划器 [3] | HotpotQA 上 5 倍 token 效率、准确率 +4%；可把推理从 175B 模型转到 7B 模型 [3] | 串行执行；中途发现问题无法纠偏 | — |
| LLMCompiler | 规划器流式产出任务 DAG，依赖满足即并行执行，Joiner 决定结束或重规划 [4] | 相对 ReAct：延迟最多快 3.7 倍、成本最多省 6.7 倍、准确率最多 +9% [4]（LangChain 博客写 3.6 倍 [2]，以论文为准） | 规划质量决定上限 | — |
| **反思 / 制作-复核** | 失败或评审不通过时，把反思文字写入记忆再试；或生成者与评审者循环 [5][13] | Reflexion：HumanEval pass@1 91%（当时 GPT-4 为 80%），不更新权重 [5] | 多轮调用成本；需要可靠的评判信号 | Anthropic evaluator-optimizer [13]、ADK LoopAgent（写手 + 评审，`max_iterations` 或子 Agent「escalate」退出）[6] |
| **代码即动作（CodeAct）** | 模型写可执行代码来组合多次工具调用，而不是逐个 JSON 调用 [7] | 17 个模型上成功率最多高 20% [7]；smolagents 同时提供 CodeAgent 与 ToolCallingAgent，「按需选择」[7b] | **必须有沙箱**；代码里的工具调用要能被平台逐个拦截审批 | smolagents CodeAgent [7b]；Anthropic Programmatic Tool Calling（复杂研究任务 token 降 37%）[14] |
| 树搜索 | 蒙特卡洛树搜索 + 模型打分 + 反思 [15] | LATS：HumanEval pass@1 92.7%（GPT-4）[15] | 调用量成倍增加；有副作用的工具无法「回退」 | 研究为主 |
| **长时目标循环** | 跨多次会话推进一个大目标：首次会话建立环境、进度文件、功能清单；之后每次只推进一项，开工先读进度与日志 [16] | Anthropic 示例：功能清单为 JSON（200 多项，每项只改 `passes` 字段），选 JSON 是因为模型「不太会误改 JSON」；每次会话只做一个功能 [16] | 需要可验证的完成标准；跨会话状态要可靠 | Anthropic 长时任务 harness [16]；DeepSeek Harness 的「Agent loop」插件 [8]（细节未核实） |

### 1.2 「Loop」在三个层次上

讨论「Loop」时容易混淆层次。Tioka 现有设计在三个层次上都有循环，只是没有统一说明：

| 层次 | 含义 | 业界 | Tioka 现状 |
| --- | --- | --- | --- |
| 内循环 | 一个 Turn 内「模型 ↔ 工具」的迭代 | OpenAI `max_turns` 超限抛异常，可用错误处理器给出兜底输出 [17]；Spring AI Alibaba `ModelCallLimitHook` [12] | ADR-016 `maxSteps` 默认 20，超限按 `max_steps` 正常结束 |
| 外循环 | 多个 Agent 或节点重复执行直到满足条件 | ADK LoopAgent：自身不判断何时停止，必须设上限或由子 Agent 发出退出信号 [6] | ADR-043 Flow 的 Loop 节点（最多 1000 轮，可设退出条件）；ADR-038 重规划最多 3 次 |
| 长循环 | 跨小时、跨天反复推进同一目标 | Anthropic 长时 harness [16]；autoloop / dsh 的「later + 跟进」[9][10] | ADR-020 回执 `later`、`orch_timer`、跟进订阅；**但没有「目标、进度、完成标准」这一层抽象** |

### 1.3 框架里的执行策略形态

| 框架 | 执行策略是否可选 | 钩子 / 中间件 |
| --- | --- | --- |
| Spring AI Alibaba（Java 17）[12] | ReactAgent 为主；多 Agent 用顺序、并行、路由、Supervisor、自定义 FlowAgent | AgentHook（Agent 前后）、ModelHook（每次推理前后）、ModelInterceptor 与 ToolInterceptor 包裹调用；内置调用次数上限钩子；可写自定义停止条件 |
| AgentScope Java 2.0 | ReActAgent 为主，子 Agent 派生 | 五个中间件阶段（Agent、推理、执行、模型调用、系统提示）与权限门（见 01 号调研） |
| smolagents [7b] | CodeAgent 与 ToolCallingAgent 两种 | — |
| Google ADK [6] | LlmAgent + 顺序、并行、循环模板；2.0 起图式工作流取代模板 | 回调 |
| DeepSeek Harness [8] | 「一切皆插件」（基于 Cordis 插件框架）；插件管理器里列有 Agent teams、Subagents、Agent loop、Auto approval reviewer、Scheduled tasks 等官方或实验插件 | 插件即扩展点（bundle 清单格式、钩子 API 本次未核实） |

共同点：**执行策略和钩子都是「同一个 Agent 内核上的可替换部分」**，没有哪个框架为每种策略另造一个运行时。

---

## 2. 执行器插件（DSH 模式）调研

### 2.1 DeepSeek Harness 与 dsh-superagent

| 项 | 内容 | 出处 |
| --- | --- | --- |
| DeepSeek Harness | 开源（MIT）、公开预览；可处理文档、表格、代码、定时任务，「用可组合的插件扩展能力」；基于 Cordis「一切皆插件」架构；插件可从插件管理器安装，也可在对话中由「创建者模式」生成；有 Web UI 与轨迹视图 | [8] |
| dsh-superagent 的打包方式 | 作为 DeepSeek Harness 的 bundle：用 `cordis.patch.yml` 把三个插件（主插件、GitHub 监听、飞书监听）插入 bundle，飞书默认关闭；测试校验 patch 与执行器会话使用的 Agent 预设 | [9] |
| 执行器 | `DshExecutor`：每个工单一个 Session 和一个目录；启动时先跑准备命令，再起执行会话，再发派工消息；供应商或网络失败按 5 秒倍增到 300 秒重试 3 次；后续输入按 2 秒倍增到 60 秒投递给运行中的派工；`WorkerSlots` 在启动前预占并发名额，人工消息可超出 `maxWorkers` 一个名额 | [9] |
| 写入纪律 | 引擎是 Trigger、Thread、Decision、Ticket、Assignment 的唯一写入者；Outbox 是回执的唯一写入者 | [9] |

### 2.2 autoloop 的 Agent 插件

| 项 | 内容 | 出处 |
| --- | --- | --- |
| 四类插件 | Agent（Claude Code、Codex、ACP、通用命令）；事件（GitHub / GitLab Webhook、HTTP API、cron、Issue 轮询）；渠道（桌面通知、飞书、钉钉、企微、Slack、Discord）；投递 | [10] |
| 通用命令 Agent | 规则里声明命令数组，`{prompt}` 作为单个参数传入（不经 shell）。README 列出 `dsh --profile headless`、`pi --print`、Cursor `agent -p`、`codebuddy -p` 等写法，并说明不是全部实测过 | [10] |
| 回执协议 | Agent 在最终消息里附一个 `agent-autoloop` 围栏块（JSON：ask、notice、resolve、later、events）；格式错时在同一会话只要求重写最后一条；仍失败保留原结论并通知人 | [10] |
| 等待 | 等人答复时进程退出、状态在 SQLite，答复后用会话续接恢复原对话；README 称 Claude Code、Codex、ACP 都实测过多轮问答与跨进程续接 | [10] |

### 2.3 让外部 Agent「可被宿主驱动」的协议

| 协议 / 接口 | 形态 | 要点 | 出处 |
| --- | --- | --- | --- |
| **ACP**（Agent Client Protocol，Zed 发起） | 编辑器与编码 Agent 之间的协议，类比 LSP；本地 Agent 作为子进程经 stdio 走 JSON-RPC；远程 Agent（HTTP / WebSocket）仍在完善 | v1 稳定、v2 草案；有会话、Prompt Turn、工具调用、elicitation、会话模式、终端、文件系统、取消等章节；支持的 Agent 包括 Claude（Zed 适配器）、Codex CLI（ACP 适配器）、Gemini CLI、Cursor、GitHub Copilot、OpenCode、Qwen Code、Kimi CLI 等；有 Java 与 Kotlin 库（权限请求章节本次未读，未核实） | [11] |
| Codex app-server | 宿主经 stdio JSONL 驱动 Codex：初始化握手后，Thread（start / resume / fork）、Turn（start / steer / interrupt）、Item（消息、命令、文件改动、工具调用）；服务端向宿主发审批请求（权限、MCP elicitation、用户输入）；审批策略与沙箱在开线程时设定 | Tioka ADR-016 的 thread / turn / item 模型就是对标它 | [18] |
| Claude Agent SDK | 「在你自己运营的进程里嵌入 Claude Code 的 Agent」，Python / TS；其他语言可用 CLI 子进程的无头模式；第三方产品须用 API Key 认证，不得提供 claude.ai 登录 | 自带工具、钩子、子 Agent、MCP、权限、会话续接 | [19] |
| A2A | 跨系统的远端 Agent | 见 01 号调研第 6 节 | — |

### 2.4 小结：执行器插件解决什么问题

外部编码 Agent（Claude Code、Codex 等）在「读写代码库、跑命令、改文件」上的能力，比在 Tioka 里用自研循环 + 工具复刻要成熟得多。dsh-superagent 与 autoloop 的共同做法是：**平台只管「接什么活、以谁的名义、何时找人、结果怎么回」，干活交给可替换的执行器**。这和 ADR-020「编排只做调度和记账，执行一律交给 Runtime」是同一思路，只是把「Runtime」这一格从唯一实现变成可插拔。

---

## 3. 与 Tioka 现有设计的对照

| 方面 | 现状 | 缺口 |
| --- | --- | --- |
| 单 Agent 执行方式 | ADR-016 只有 ReAct 式循环；AgentSpec（ADR-023）的 `limits` 只有 maxSteps 与 token 上限 | 无法为某个 Agent 选择先规划后执行、反思等策略；规划与反思没有对应的 item 类型，轨迹视图无法区分 |
| 多 Agent 规划 | ADR-038 `team.plan` 是任务 DAG，依赖满足即派发，失败重规划 | 这其实是**多 Agent 层面的 LLMCompiler**，设计已经到位；缺的是单 Agent 内的轻量版本（同一个 Agent 自己先列计划再逐步做，不开子 thread） |
| 制作-复核 | ADR-038 `verify` 节点（多 Agent）；Flow 的 Loop + StructuredExtract | 单 Agent 内没有「自评不过就重试」的标准做法；ADR-031 的评估器 SPI 只用于离线和线上评测，没接进执行 |
| 代码即动作 | ADR-027 有 `code.run`，但代码里不能回调平台工具 | 没有「代码里的工具调用经 ToolGateway 逐个校验」的桥 |
| 长时目标 | 编排 `later`、定时器、跟进订阅；工作盘 | 没有「目标、完成标准、进度」的结构，长任务完全依赖 Agent 自觉 |
| 钩子 / 中间件 | 固定管线（ADR-033 的 12 步）；上下文组装顺序固定（ADR-027） | 01 号调研选项 D（护栏挂点）已提出；执行策略需要同一套挂点 |
| 平台扩展插件 | ADR-026 插件宿主（九类 SPI）、Flow 节点插件与远程节点（ADR-043）、决策器 SPI（ADR-024）、评估器 SPI（ADR-031）、沙箱 Provider（ADR-027） | 基本齐全；缺「执行策略」与「执行器」两类 |
| 外部执行器 | 无。ADR-022 对外的 OpenAI 兼容接口是 `model = agent:{id}`，即 Tioka 作为被调用方 | 没有把外部 Agent 当执行器的 SPI；也没有让外部 Agent 的模型调用回到 Tioka ModelGateway 的「模型直通」接口 |

---

## 4. 设计建议一：执行策略 SPI（选项 I）

### 4.1 决定要点

1. **策略是 AgentSpec 的一部分**：新增 `execution` 段，字段为策略编码、策略参数（如最大重规划次数、反思轮数、评估器引用）、是否允许运行时覆盖（默认不允许）。随 Agent 版本冻结，发布校验检查策略已注册、参数合法、引用的评估器或模板存在。
2. **策略只决定「下一步」，不碰边界**。策略在 ADR-016 循环里替换「组装本步提示 → 调模型 → 解释结果」这一段。模型选择（ADR-024）、工具调用管线（ADR-033 / ADR-039）、审批挂起、取消与插话、预算、item 落库、流式推送一律不变。策略不能自己调模型 SDK，只能经 ModelGateway；不能直接执行工具，只能交给 ToolGateway。
3. **新增两种 item 类型**：`plan`（计划及其版本，记录每步状态）与 `reflection`（评审结论与反思文字）。轨迹视图按 item 类型展示，ADR-031 的轨迹导出带上它们。
4. **步数与预算统一计量**：规划、执行、反思中的每次模型调用都计入 `maxSteps` 与 Turn token 预算，防止策略放大成本。

### 4.2 首批策略

| 编码 | 行为 | 参数与上限 | 何时用 | 阶段建议 |
| --- | --- | --- | --- | --- |
| `REACT` | 现有循环，默认值 | maxSteps | 绝大多数对话与工具型 Agent | 已有 |
| `PLAN_EXECUTE` | 首步强制产出 `plan` item（结构化输出，ADR-037）；之后逐步执行，每步结束更新步骤状态；步骤失败或模型声明「计划需调整」时重规划 | 重规划 ≤ 2；计划步数 ≤ 12；可指定「规划用路由组」与「执行用路由组」不同（ADR-024 组），以实现「大模型规划、小模型执行」 | 步骤多、可预见，但不值得做成 Flow 的任务；也是 ADR-038 团队主管在单 Agent 内的轻量版 | M4 |
| `REFLECT` | 生成最终答复前，用引用的评估器（ADR-031 评估器 SPI：规则或 LLM-as-a-Judge）打分；不过则写 `reflection` item 并重试 | 反思轮数 ≤ 2；评估调用 purpose=EVALUATION，计入本 Turn 预算 | 有明确质量标准的产出（报告、抽取、合同条款判断的自检） | M4 |
| `CODE_ACT` | 模型输出代码在沙箱执行；代码中的工具调用经「工具桥」回到 ToolGateway，逐个走授予、Cedar、审批；需要审批的调用让 Turn 挂起 | 依赖 RT-11 沙箱；单次执行时限沿用工具调用时限 | 数据分析、批量处理 | M4 之后（沙箱就绪后） |
| `GOAL_LOOP` | 见 4.3 | — | 跨天的大任务 | M4 之后 |
| 树搜索 | 不做 | — | 有副作用的工具无法回退，企业场景性价比低 | 不做 |

### 4.3 长时目标循环（GOAL_LOOP）的落法

不新造调度器，组合已有能力：

- **目标与完成标准**由发起方给出（编排规则或用户），写进工单；完成标准必须可检查（清单项、测试、评估器分数）。
- **进度文件**放在工作盘（ADR-027），首次 Run 由 Agent 创建「功能清单 + 进度日志」，之后每次 Run 开工先读、收工更新。参照 Anthropic 的经验，清单用 JSON 且只允许改状态字段，平台在 Run 结束时校验清单结构未被破坏 [16]。
- **每次 Run 只推进一项**，结束时回执 `later`（ADR-020）安排下一次，或在清单全部通过时 `resolve`。
- **上限**：最大 Run 数、总预算、总时长由规则配置，超限转人。

### 4.4 与多 Agent、Flow 的分工（补 ADR-043 第 1.1 节）

| 场景 | 用什么 |
| --- | --- |
| 单 Agent，步骤少 | REACT |
| 单 Agent，步骤多但同一角色能做完 | PLAN_EXECUTE |
| 单 Agent，产出要过质量门槛 | REFLECT |
| 需要不同角色、不同权限、并行 | 多 Agent 团队（ADR-038） |
| 步骤固定、要可视化与发布 | Flow（ADR-043） |
| 跨天推进 | GOAL_LOOP（编排 `later` + 工作盘进度） |

---

## 5. 设计建议二：外部执行器 SPI（选项 J）

### 5.1 决定要点

1. **执行器是 Agent 的一种运行方式**：AgentSpec 的 `execution` 段增加 `executor`：`NATIVE`（Tioka Runtime，默认）或外部执行器引用（执行器登记表中的一项）。对用户、编排、团队来说它仍是一个 Agent，有版本、ACL、授权书、自治等级。
2. **Tioka 保留的职责**：接单与 Mandate（ADR-020）、身份与授予（ADR-039）、审批（通用审批表）、预算与配额、审计、回执校验、轨迹落库、通知。**外部执行器只负责「在沙箱里把活干完」**。
3. **执行器一律在沙箱里运行**（ADR-027）：每个 thread 一个沙箱与工作盘，网络默认关闭，只开放到 Tioka 自身（模型直通与 MCP）和授予内的出网白名单。未启用沙箱的部署不能使用外部执行器（与 ADR-027「绝不在应用进程内执行」一致）。
4. **模型调用回到 Tioka**：外部 Agent 的模型 Base URL 指向 Tioka 新增的「模型直通」接口（OpenAI 兼容与 Anthropic 兼容，`model` 写模型别名），用一次性、绑定本 thread 授予的短期令牌。这样配额、成本、路由、审计、数据标签选模（ADR-040）全部生效，外部 Agent 拿不到供应商真实密钥。
5. **平台工具经 MCP 提供**：外部 Agent 需要的 Tioka 工具（知识检索、业务插件、`flow.*`）由 Tioka 作为 MCP Server 暴露（ADR-021），令牌即本 thread 的授予，ToolGateway 全管线照常执行。外部 Agent 自带的内置工具（读写文件、跑命令）只受沙箱约束。
6. **审批对接**：执行器协议里的权限请求（ACP 的权限请求、Codex app-server 的审批请求）映射为 Tioka 通用审批（kind=tool），Turn 进入 `WAITING_APPROVAL`；答复后回传给执行器。协议不支持权限请求的执行器（通用命令行），只能以「无交互、全部在沙箱内」方式运行。
7. **回执**：优先用协议的结构化结束信号；通用命令行执行器沿用 autoloop 的做法，最终消息里带一个约定格式的回执块，格式错时在同一会话要求重写一次（与 ADR-020 回执重写同一机制）。
8. **轨迹**：执行器事件映射为 item（消息、命令、文件改动、工具调用），粒度取决于协议。ACP 与 Codex app-server 能给出逐项事件；通用命令行只有最终输出与工作盘变更。
9. **自治等级上限**：外部执行器的内置工具不经 ADR-033 预演与撤销，因此其写外部系统的动作只能通过 Tioka MCP 工具完成；内置工具的副作用限定在工作盘内。授权书编译时，外部执行器 Agent 的自治等级上限默认 L2（ADR-034），不能直接 L3 以上。

### 5.2 执行器类型与接入顺序

| 类型 | 覆盖 | 交互能力 | 建议顺序 |
| --- | --- | --- | --- |
| `ACP` | Claude（Zed 适配器）、Codex CLI（ACP 适配器）、Gemini CLI、Qwen Code、Kimi CLI、OpenCode 等 [11] | 会话、多轮、取消、权限请求、逐项事件 | 第一 |
| `COMMAND` | 任意支持无头模式的命令行 Agent（如 `dsh --profile headless`、Claude Code 无头模式）[10][19] | 只有输入与最终输出，续接依赖各 CLI 的会话参数 | 第二 |
| `CODEX_APP_SERVER` | Codex [18] | 线程、Turn、插话、审批、逐项事件 | 第三（ACP 已覆盖 Codex 时可不做） |
| `A2A` | 远端 Agent | 见 01 号调研选项 F | 随选项 F |

执行器登记表字段：编码、类型、镜像与启动命令、协议版本、可用的模型别名、默认沙箱规格、所需 MCP 工具清单、状态。登记与变更属于平台管理员权限，按 ADR-041 版本化。

### 5.3 适用与不适用

- 适用：编码类数字员工（对标 dsh-superagent 的仓库维护场景）、批量文档处理、数据分析。
- 不适用：租赁合同审查等需要逐步留痕、逐条引用、结构化产物校验的场景，继续用 NATIVE 执行器与 ADR-037 / ADR-038。
- 许可与合规：接入第三方 Agent 时要遵守其条款，例如 Claude Agent SDK 要求第三方产品使用 API Key 认证 [19]。执行器登记时记录许可类型与认证方式。

---

## 6. 待负责人决定的选项

人日为粗估；编号接 01 号调研的 A〜H。

| 选项 | 内容 | 涉及 | 粗估 | 可选阶段 | 建议 |
| --- | --- | --- | --- | --- | --- |
| **I0** | M3 契约预留：AgentSpec 增加 `execution` 段（只允许 `REACT` + `NATIVE`），`plan`、`reflection` item 类型先登记 | ADR-023、ADR-016 | 后端约 0.5 | M3 | **建议采纳**，避免 M4 迁移快照 |
| **I1** | 新 ADR「执行策略 SPI」：`PLAN_EXECUTE`、`REFLECT`；轨迹视图展示计划与反思 | 新 ADR-046、ADR-016、ADR-023、ADR-031 | 后端约 8，前端约 3 | ① M4 P1 ② M4 之后 | 建议 ① |
| **I2** | `CODE_ACT`（工具桥） | ADR-046、ADR-027 | 后端约 5 | M4 之后（依赖 RT-11） | 建议 M4 之后 |
| **I3** | `GOAL_LOOP`（目标、完成标准、工作盘进度、`later` 续跑） | ADR-046、ADR-020 | 后端约 5，前端约 2 | ① M4 P2 ② M4 之后 | 建议 ② |
| **J1** | 新 ADR「外部执行器 SPI」+ ACP 执行器 + 执行器登记表 + 审批映射 | 新 ADR-047、ADR-027、ADR-034、ADR-039 | 后端约 10，前端约 2 | ① M4 P2 ② M4 之后 | 建议 ②；若编码类数字员工成为 M4 目标则改 ① |
| **J2** | 「模型直通」接口（OpenAI / Anthropic 兼容，短期令牌绑定授予） | ADR-017、ADR-022、ADR-024 | 后端约 3 | 随 J1 | 随 J1 |
| **J3** | `COMMAND` 与 `CODEX_APP_SERVER` 执行器 | ADR-047 | 后端约 3 + 4 | M4 之后 | 随需求 |

如果按建议执行：M3 只增加 I0 约 0.5 人日；M4 增加 I1 约后端 8、前端 3 人日；其余放到 M4 之后。与 01 号调研的建议合计，M4 净增约后端 25、前端 7 人日，写 M4 计划时一并核算。

ADR 编号 044（意图路由）、045（A2A）、046（执行策略）、047（外部执行器）为建议编号，以写 ADR 时的实际空号为准。

---

## 参考资料

所有资料于 2026-11-06 读取。

1. Yao et al., *ReAct: Synergizing Reasoning and Acting in Language Models*. https://arxiv.org/abs/2210.03629
2. LangChain, *Plan-and-Execute Agents*（2024-02-13）. https://www.langchain.com/blog/planning-agents
3. Xu et al., *ReWOO*. https://arxiv.org/abs/2305.18323
4. Kim et al., *An LLM Compiler for Parallel Function Calling*. https://arxiv.org/abs/2312.04511
5. Shinn et al., *Reflexion*. https://arxiv.org/abs/2303.11366
6. Google ADK, Loop agents. https://adk.dev/agents/workflow-agents/loop-agents/ ；Workflow agents https://adk.dev/agents/workflow-agents/
7. Wang et al., *Executable Code Actions Elicit Better LLM Agents*（CodeAct）. https://arxiv.org/abs/2402.01030 ；7b. Hugging Face smolagents https://huggingface.co/docs/smolagents/conceptual_guides/react 、https://huggingface.co/docs/smolagents/conceptual_guides/intro_agents
8. DeepSeek Harness. https://www.deepseek.com/en/harness/ ；源码 https://github.com/deepseek-ai/deepseek-harness ；Cordis https://github.com/cordiverse/cordis
9. deepseek-ai, dsh-superagent-prototype 架构. https://github.com/deepseek-ai/dsh-superagent-prototype/blob/main/docs/007-architecture.md
10. KKKKhazix, agent-autoloop. https://github.com/KKKKhazix/agent-autoloop/blob/main/README.en.md
11. Agent Client Protocol. https://agentclientprotocol.com/ ；支持的 Agent https://agentclientprotocol.com/get-started/agents
12. Spring AI Alibaba, Agents 教程. https://java2ai.com/docs/frameworks/agent-framework/tutorials/agents
13. Anthropic, *Building effective agents*. https://www.anthropic.com/research/building-effective-agents
14. Anthropic, *Introducing advanced tool use*. https://www.anthropic.com/engineering/advanced-tool-use
15. Zhou et al., *Language Agent Tree Search*（LATS）. https://arxiv.org/abs/2310.04406
16. Anthropic, *Effective harnesses for long-running agents*（2025-11-26）. https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents
17. OpenAI Agents SDK, Running agents. https://openai.github.io/openai-agents-python/running_agents/
18. OpenAI Codex App Server. https://learn.chatgpt.com/docs/app-server
19. Claude Agent SDK overview. https://code.claude.com/docs/en/agent-sdk/overview

**本次未核实**：DeepSeek Harness 的 bundle 清单格式、钩子 API、无头 profile 语义（开发者文档未读）；ACP 权限请求章节；Codex SDK 页面；smolagents 中 CodeAgent 相对 ToolCallingAgent 的步数或调用次数数据；「Ralph 循环」的出处（Anthropic 长时 harness 一文未使用该名称）。

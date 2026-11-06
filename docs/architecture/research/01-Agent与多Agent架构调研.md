# Agent、多 Agent、意图路由、SuperAgent 与 A2A 调研

- 日期：2026-11-06
- 落地：负责人 2026-11-06 同意按本文建议设计，已写成 ADR-044〜047 与相关修订，见 ADR-029 第 66 条。
- 状态：调研报告（背景材料，不是执行依据）。文中「建议」需负责人决定后，按 [ADR-030](../adr/ADR-030-anti-drift-process.md) 修订 ADR 与 34 号计划才生效。
- 范围：单 Agent 设计、多 Agent 协作模式、意图识别路由、SuperAgent、A2A（及其与 MCP 的关系）；主流框架与产品；与 Tioka 现有设计 ADR-016/020/023/024/027/031/038〜040/043 的对照。
- 写法：只有文字和表格，不含代码。引用编号见文末「参考资料」，所有资料于 2026-11-06 联网读取；读不到原文或原文不一致之处，文中标「未核实」。

---

## 0. 结论摘要

1. **Tioka 的主干方向与业界共识一致，不需要推翻。** 业界在 2025〜2026 年形成的共识是：能用确定性工作流的不用 Agent，能用单 Agent 的不用多 Agent；多 Agent 首选「主管-专家」（orchestrator-workers / manager），不做去中心的自由群聊 [1][2][4][5]。Tioka 的三分法（Flow 管确定流程、Runtime Turn 管单 Agent、ADR-038 团队管自主分派）与 ADR-038「不做自由对话式协作」正是这一共识；在**持久化、权限收窄、可审计**三方面，Tioka 的设计比多数开源框架更严（框架普遍把这些留给使用者）。
2. **最明显的缺口是「入口意图路由」。** Tioka 有模型路由（ADR-024，选哪个模型）和事件路由（ADR-020，CEL 规则只看可信属性），但没有「一句话进来，交给哪个 Agent / Flow / 知识库」的 Agent 级路由。业界做法是分层：显式指令 → 向量语义匹配 → LLM 分类 → 低置信度澄清 [12][13][1]。建议新立一份 ADR（选项 B）。
3. **A2A 已成熟到可以规划，但不必抢进 M3。** A2A 已于 2026-03 发布 v1.0（2026-05 有 v1.0.1），归 Linux Foundation，官方 Java SDK 要求 Java 17+ [8][9][10]；MCP 也已捐给 Agentic AI Foundation，并在 2026-07-28 发布了「无状态核心」的新版规范 [11]。Tioka 目前只有 MCP，没有 A2A。建议 M3 只在 ADR 里预留映射（零人日），M4 或之后再做（选项 F）。
4. **MCP 新规范对 M3 的 CON-05 有直接影响。** ADR-021 按「Streamable HTTP + 兼容旧 SSE、有会话」设计，而 2026-07-28 版去掉了握手和会话、废弃了 HTTP+SSE 传输与 Sampling [11]。CON-05 还没实现，现在修订 ADR-021 成本最低（选项 E，建议做）。
5. **多 Agent 的代价要显式管理。** Anthropic 的数据：多 Agent 系统 token 约为普通对话的 15 倍，对可并行的研究任务效果提升 90.2%，但对强依赖、需共享上下文的任务不适合 [2]；Cognition 指出子 Agent 之间的「隐含决定」冲突是主要失败源 [3]；MAST 研究把多 Agent 失败归为系统设计、Agent 间失配、任务验证三类 [15]。ADR-038 已有预算、黑板、仲裁、复核节点，建议再补四处小修订（选项 A）：任务简报的必填结构、共享决定日志、投入分级规则、「团队对比单 Agent 基线」的上线门槛。
6. **租赁合同审查这个旗舰场景，应长期保持「模板化团队」而不是自由规划。** 它的角色和步骤基本固定（主管、复核员、判断员、质检员），正是 Anthropic 所说的「可预定义子任务，用工作流更合适」[1]。M3 用 `agent.delegate` + Flow 骨架的做法是对的；M4 上 TeamSpec 后也建议用 `TEMPLATE` 模式，`FREE` 模式留给开放式任务。

需要负责人决定的事项集中在第 9 节（选项 A〜H）。

---

## 1. 概念与术语

| 术语 | 本文含义 | 业界对应 |
| --- | --- | --- |
| 工作流（Workflow） | 由代码或 DSL 预先定义路径，模型只在节点内工作 | Anthropic「workflows」[1]、OpenAI「orchestrating via code」[4]、CrewAI Flows [7c]、Microsoft Agent Framework Workflows [6]、ADK workflow agents [7b]、Tioka Flow（ADR-043） |
| Agent | 模型在循环里自主决定调用哪些工具、何时结束 | Anthropic「LLMs autonomously using tools in a loop」[16]；Tioka Runtime Turn（ADR-016） |
| 多 Agent | 多个各有指令、工具、上下文的 Agent 协作完成一件事 | 主管-专家、交接（handoff）、群聊、蜂群（swarm）等拓扑 |
| 意图路由 | 判断一次输入应交给哪个 Agent、流程、模型或工具 | Anthropic「Routing」模式 [1]、OpenAI triage + handoff [4]、LangChain Router 模式 [7a]、semantic-router [12] |
| SuperAgent | 一个通用主管 Agent + 沙箱 + Skill + 记忆 + 子 Agent 的「通用执行底座」，或主动接活的 Agent | DeerFlow 2.0「super agent harness」[18]、dsh-superagent「proactive coding agent」[17] |
| A2A | Agent 与 Agent 之间（跨系统、跨厂商）的通信协议 | Agent2Agent 协议 [8] |
| MCP | Agent 与工具、资源之间的协议 | Model Context Protocol [11] |

A2A 官方对两者的定位是：「MCP 是纵向的，加深单个 Agent 的能力；A2A 是横向的，跨边界连接 Agent」，二者互补，典型系统里 Agent 之间走 A2A、每个 Agent 内部走 MCP [9]。

---

## 2. 单 Agent 设计：业界现状

### 2.1 共识要点

| 要点 | 出处 | 说明 |
| --- | --- | --- |
| 先把单 Agent 做到极致 | OpenAI 实践指南 [5]；Anthropic [1] | OpenAI：「Our general recommendation is to maximize a single agent's capabilities first」。Anthropic：只在能证明提升时才增加复杂度 |
| 什么时候该拆 | OpenAI [5] | 两个信号：逻辑分支太多、Prompt 模板难以维护；工具过载。原文：有的实现能管好 15 个以上界限清楚的工具，有的不到 10 个重叠工具就出问题 |
| 工具设计比 Prompt 更重要 | Anthropic [1][2] | Anthropic 在 SWE-bench 工作中「花在优化工具上的时间多于整体 Prompt」；改写一个工具描述让任务时间下降 40% |
| 上下文是有限资源 | Anthropic 上下文工程 [16] | 「context rot」：token 越多召回越差。手段：压缩（compaction）、结构化笔记（把待办和笔记写到上下文之外）、子 Agent 隔离（子 Agent 用几万 token 探索，只回 1000〜2000 token 摘要） |
| 大工具库用「工具检索」 | Anthropic advanced tool use [14] | 工具标记为延迟加载，模型先搜再用。58 个工具的例子从约 77K token 降到约 8.7K；内部 MCP 评测准确率 Opus 4 由 49% 升到 74%。代价是多一步检索的延迟。适用于工具定义超过 10K token 或 10 个以上工具 |
| 分层护栏 | OpenAI [5] | 相关性、越狱、PII、内容审核、按风险分级的工具护栏、规则、输出校验，「layered defense」；人工介入触发条件：连续失败超阈值、高风险动作 |
| 先用最强模型做原型，再按评测降级 | OpenAI [5] | 与 Tioka ADR-024 路由组 + ADR-031 实验的组合一致 |
| 可插拔的中间件 / 钩子 | AgentScope Java 2.0 [19]、Claude Agent SDK [20] | AgentScope Java 有五个中间件阶段（Agent、推理、执行、模型调用、系统提示）与 allow / approve / deny 权限门；Claude Agent SDK 有 hooks 与权限模式 |

### 2.2 主流框架的单 Agent 内核

| 框架 | 语言 | 单 Agent 内核要点 | 对 Tioka 的参考价值 |
| --- | --- | --- | --- |
| OpenAI Agents SDK [4] | Python / TS | Agent = 模型 + 指令 + 工具；输入、输出护栏；tracing；会话 | 护栏分层的产品形态 |
| Claude Agent SDK [20] | Python / TS | 与 Claude Code 同一套 Agent 循环；子 Agent、Skill、hooks、权限模式、自动压缩、预算上限 | 与 Tioka Runtime 同构（thread/turn/item 对标 Codex，ADR-016） |
| LangChain / LangGraph [7a] | Python / JS | 图式状态机；官方现在推荐用 Deep Agents 做内置多 Agent | 持久化与中断的思路，Tioka 已用 PostgreSQL 实现 |
| Microsoft Agent Framework [6] | .NET / Python | AutoGen 与 Semantic Kernel 的继任者，「由同一批团队打造」；Agent + 图式 Workflow，带检查点与人审；支持 A2A、AG-UI、MCP | 官方概览页仍标「public preview」；第三方报道称 2026 年 4 月发布 1.0，**未在官方页核实** |
| Google ADK [7b] | Python / TS / Go / Java | Agent + 工作流（顺序、并行、循环模板；2.0 起的图式、动态、协作式工作流；实验性 Agent 路由）；A2A 支持 Python、Go、Java | 有 Java 版 |
| AgentScope Java 2.0 [19] | Java（JDK 17+） | ReAct、31 种类型化事件、权限门、中间件、沙箱（本地 / Docker / K8s / E2B）、会话状态存储（含 PostgreSQL、Redis）、A2A / AG-UI | 与 Tioka 技术栈最接近，可作为实现参考或局部组件 |
| Spring AI Alibaba [21] | Java（JDK 17+） | ReactAgent、Graph（持久化、编排、流式）、人审、A2A + Nacos 注册发现 | Spring 生态，A2A 与 Nacos 集成现成 |

### 2.3 与 Tioka 单 Agent 设计的对照

| 能力 | Tioka 现状 | 判断 |
| --- | --- | --- |
| Agent 循环、多步工具、并行工具、最大步数 | ADR-016（maxSteps 20、并行 4） | 一致 |
| 持久化、崩溃恢复、多实例 | ADR-016（PostgreSQL 为事实来源、租约、回收器） | 优于多数框架（框架多为内存或 SQLite 检查点） |
| 上下文组装顺序、压缩、大工具结果落盘 | ADR-027（75% 阈值压缩、>8000 token 落工作盘） | 一致；可补「结构化笔记」（见建议） |
| Skill 渐进加载 | ADR-023（always / auto / manual，`skill.load`） | 与 Claude Agent Skills、DeerFlow 的渐进加载一致 |
| 工具权限与审批 | ADR-033 的 12 步管线、ADR-039 授予、Cedar | 显著强于框架默认 |
| **大工具库的工具检索** | 没有。`listTools` 按授予过滤后全部给模型 | **缺口**：一个 Agent 绑定多个 MCP Server 后工具数很容易过 10 个 |
| **输入 / 输出护栏** | 有 PII 正则（GOV-10）、工具前置策略；没有统一的「输入相关性 / 越狱」与「最终输出校验」挂点 | **部分缺口** |
| 结构化输出 | ADR-037 | 一致 |
| 评测驱动迭代 | ADR-031 | 一致 |

---

## 3. 多 Agent 协作：业界现状

### 3.1 协作拓扑

| 拓扑 | 谁控制流程 | 代表 | 适用 | 风险 |
| --- | --- | --- | --- | --- |
| 主管-专家（Agent 作为工具） | 主管始终掌控、负责最终答复 | OpenAI manager / `as_tool` [4][5]；Anthropic orchestrator-workers [1][2]；LangChain Subagents [7a]；Spring AI Alibaba SupervisorAgent [21]；AgentScope TeamPipeline [19] | 子任务无法预定义、要统一汇总 | 主管成为瓶颈；子 Agent 上下文不足 |
| 交接（handoff） | 控制权转移给专家，专家直接面对用户 | OpenAI handoffs（`transfer_to_<agent>`，只在一次 run 内）[4]；LangChain Handoffs [7a]；Spring AI Alibaba Handoffs [21] | 客服式分诊：分诊后由专家接着对话 | 去中心，责任与审计难归属 |
| 路由（一次性分派） | 路由步骤分类后派给专家，再汇总 | LangChain Router [7a]；Spring AI Alibaba LlmRoutingAgent [21]；ADK Agent 路由（实验）[7b] | 入口分发 | 分类错误代价高 |
| 固定流水线 / 并行 / 循环 | 代码或 DSL | ADK 顺序、并行、循环 [7b]；CrewAI sequential 与 Flows [7c]；MS Agent Framework Workflows [6] | 步骤确定 | 不灵活 |
| 制作-复核（evaluator-optimizer） | 生成者 + 评审者循环 | Anthropic [1]；OpenAI「while loop with evaluator」[4] | 有明确评判标准 | 循环成本 |
| 群聊 / 蜂群 | 多个 Agent 轮流发言或互相交接 | AutoGen 群聊、LangGraph swarm（**本次未读到官方页，未核实细节**） | 研究、头脑风暴 | Cognition 称「talk-it-out」式系统「fragile」[3] |

### 3.2 关键经验与数据

1. **多 Agent 只对「可并行、超过单个上下文、工具很多」的任务划算。** Anthropic 研究系统：Opus 主管 + Sonnet 子 Agent 比单个 Opus 好 90.2%；Agent 约用 4 倍于对话的 token，多 Agent 约 15 倍；token 用量本身解释了 BrowseComp 80% 的方差。强依赖、需共享上下文的任务（含多数编码任务）不适合 [2]。
2. **子 Agent 必须拿到完整简报。** 每个子任务需要「目标、输出格式、可用工具与来源、清晰边界」；早期版本曾为简单问题派出 50 个子 Agent，后来在提示中写入投入分级规则：简单事实 1 个 Agent 3〜10 次工具调用；比较类 2〜4 个子 Agent；复杂研究 10 个以上 [2]。
3. **隐含决定会冲突。** Cognition 的两条原则：共享完整上下文与轨迹，而不仅是单条消息；「行动隐含决定，冲突的决定带来坏结果」。默认推荐单线程 Agent + 历史压缩 [3]。
4. **子 Agent 交回引用而不是全文。** 子 Agent 把产出写进文件系统，只回传轻量引用，避免「传话游戏」失真 [2]；子 Agent 只回 1000〜2000 token 的摘要 [16]。
5. **生产化要点。** 有状态 Agent 要能从检查点续跑，不能从头重来；全量追踪；用「彩虹部署」让新旧版本并行、逐步切流；同步等待子 Agent 会成为瓶颈 [2]。
6. **失败分类。** MAST 基于 7 个框架的 1600 多条标注轨迹，把失败归为 14 种模式、3 大类：系统设计问题、Agent 间失配、任务验证；并指出多 Agent 在流行基准上的收益「often minimal」[15]。（14 种模式的具体名称本次未读全文，未核实。）
7. **评测从小样本开始。** Anthropic 从约 20 条查询起步，用 LLM 评审按事实准确、引用准确、完整、来源质量、工具效率打分，有状态任务看终态 [2]。

### 3.3 与 ADR-038〜040 的对照

| 业界要点 | Tioka 现状 | 判断 |
| --- | --- | --- |
| 主管-专家为默认拓扑，不做自由群聊 | ADR-038「协作拓扑」表，明确不做去中心对话 | 一致 |
| 主管生成提示词，平台控制能力 | ADR-038「主管可以生成提示词，但不能生成能力」；ADR-039 授予只收窄 | 领先：框架普遍只有「工具白名单」，没有链式收窄与祖先禁区 |
| 子 Agent 上下文隔离，只回摘要 / 引用 | 子 Agent 只交 ADR-037 结构化产物到黑板；工作盘文件被引用的提升为团队产物 | 一致，且产物有 Schema 校验 |
| 隐含决定冲突 | 同一 `subjectKey` 的矛盾结论进冲突记录、仲裁 ≤2 轮、超限转人 | 只覆盖「结论冲突」，**没有覆盖「过程中的假设与取舍冲突」**（Cognition 的核心担忧） |
| 子任务简报质量 | `team.plan` 节点有 `role`、`inputs`、`expectedType`、`narrow`、`budgetTokens` | **缺「目标、边界、完成标准」的必填结构** |
| 投入分级、防止过度派生 | 团队预算、`maxTasks`、`maxParallel` 3（上限 8）、`maxDepth` 2 | 有硬上限；**缺「按任务复杂度决定派几个」的软规则与校验** |
| 15 倍 token 的代价要算得过来 | 团队预算、ADR-035 价值账本 | **缺上线门槛**：没有要求证明团队比单 Agent 好 |
| 检查点续跑、异步不阻塞 | 子 Turn `WAITING_CHILD` 释放 worker，任务行在 PostgreSQL，双实例可续派 | 领先（Anthropic 自述仍是同步等待子 Agent） |
| 任务验证 | 任务图 `verify` 节点（可选）；ADR-020 回执校验含必审检查点 | 基本覆盖；建议高风险场景把 `verify` 设为必选 |
| 交接（handoff） | 没有。`agent.delegate` 是「调用后返回」 | 企业审计视角下不做去中心交接是合理的；但 Chat 入口的「分诊后由专家接着聊」需要一种受控形式（见第 4 节） |
| 追踪 | 团队 Run 根 span + 子任务子 span（GOV-08） | 一致；建议对齐 OTel GenAI 的 Agent span 语义（第 8 节） |

---

## 4. 意图识别路由

### 4.1 三种「路由」不要混为一谈

| 层次 | 问题 | Tioka 现有 |
| --- | --- | --- |
| 事件路由 | 外部事件命中哪条规则、归哪个工单 | ADR-020：CEL 只看信封与可信 `attrs`，「事件正文不决定路由和权限」 |
| **Agent 路由（入口意图路由）** | 用户一句话应交给哪个 Agent、Flow、知识库，或先澄清 | **没有** |
| 模型路由 | 本 Turn 用哪个模型 | ADR-024：FIXED / DIFFICULTY / BRANCH / FREE_FIRST，决策器 SPI |
| 工具路由 | 在大工具库里挑本步要用的工具 | 没有（见 2.3 的工具检索） |

### 4.2 业界做法

| 做法 | 代表 | 特点 |
| --- | --- | --- |
| 显式指令 | @某 Agent、斜杠命令、IM 机器人绑定 | 零成本、零歧义；Tioka 的 CON-02「在飞书里 @Agent」即此类 |
| 向量语义匹配 | aurelio-labs semantic-router：每个路由给若干示例话术，用向量相似度匹配，不等待大模型生成；无匹配时返回空 [12] | 毫秒级、便宜；需要维护示例话术与阈值 |
| 小模型分类器 / 决策层 | vLLM Semantic Router：「models and compute 的可编程决策层」，按策略在质量、延迟、成本间选择 [13]；RouteLLM：README 称在 MT Bench 上保持 95% GPT-4 水平的同时最多降本 85% [13b] | 偏模型路由，思路可复用到 Agent 路由 |
| LLM 分类 + 结构化输出 | Anthropic Routing 模式 [1]；OpenAI triage Agent + handoff [4]；Spring AI Alibaba LlmRoutingAgent [21]；LangChain Router [7a] | 准确、可解释，延迟与成本较高 |
| 工作流内的分类节点 | Dify「问题分类器」、百炼工作流的意图分类节点（**百炼页面本次未读到，未核实**） | 画布内一步完成「分类 → 分支」 |
| 工具检索 | Anthropic Tool Search [14] | 本质是「工具级意图路由」 |

成熟实现普遍是**分层级联**：显式指令命中就结束；否则先走便宜的向量匹配，高置信度直接派；中等置信度交 LLM 分类；仍低就向用户澄清或交默认通用 Agent。每次决定都要留痕，用评测数据集持续量化准确率。

### 4.3 Tioka 的缺口与建议设计要点（对应选项 B）

现状：Chat 由用户自选 Agent；IM 由机器人绑定的 Agent 接；编排由规则决定。企业门户、飞书「一个助手入口」等场景缺一个「入口路由」。建议设计要点：

1. **入口路由是一种 Agent 资产**（与 ADR-038「团队是一种 Agent」同理），`profile.type = ROUTER`，有版本、ACL、发布校验。候选目标为 Agent、Flow 发布、知识库问答。
2. **先按权限过滤候选，再分类。** 只把调用人有 USE 权限的目标交给分类器，路由结果不改变任何权限；这延续 ADR-020 的硬边界「正文不决定权限」：正文可以决定「交给谁」，但只能在调用人本来就能用的范围内选。
3. **分层级联**：显式指令 → 向量匹配（候选目标的描述 + 示例话术，复用知识层的单一向量维度）→ LLM 分类（经 ModelGateway，purpose=decision，结构化输出：目标、置信度、理由、需澄清的问题）→ 澄清卡片或默认 Agent。阈值可配。
4. **复用 ADR-024 的决策器 SPI**：模型路由的「启发式 / LLM / 外部 HTTP」决策器与 Agent 路由同构，可共用实现，再加一个「向量匹配」决策器，两种路由都能用。
5. **受控交接**：路由选中后，在同一会话里切换「当前 Agent」，相当于 OpenAI handoff 的受控版：链根身份不变、写一条路由事件、会话历史按目标 Agent 的配置决定是否带入（类似 OpenAI 的 `input_filter` [4]）；切换只能发生在 Turn 边界。
6. **可评测**：路由决定写入轨迹；ADR-031 数据集增加「期望目标」字段，批次评测给出路由准确率与混淆矩阵；线上用「用户改选 Agent」作为负反馈信号。
7. **Flow 侧**：ADR-043 已有 StructuredExtract + Switch，可组合出分类分支；建议在节点目录或模板库里提供「意图分类」节点模板，降低搭建成本，不必新增引擎能力。

---

## 5. SuperAgent

### 5.1 两种含义

| 含义 | 代表 | 要点 |
| --- | --- | --- |
| 主动接活的 Agent（proactive） | dsh-superagent-prototype（DeepSeek，MIT，标注「研究原型，非官方产品」）[17] | 面向一个 GitHub 仓库，监听 GitHub 与可选的飞书；来源把载荷转成 Trigger，引擎存一次；Trigger 归组进 Eval，每个 Eval 得到一个 Decision（accept / stop / ignore / unavailable）；Mandate 记录来由（addressed / followup / admission / bot）；主 Session 研判，Executor Session 干活，每个工单一个 Session 和目录，并发受 `maxWorkers` 限制；在原会话答复（如提 PR）。设计理念「占用尽量少的人类注意力，给出尽量多的价值」 |
| 通用执行底座（harness） | DeerFlow 2.0（字节跳动，MIT）[18] | 「open-source super agent harness that orchestrates sub-agents, memory, and sandboxes」；主管 Agent 规划并派生子 Agent；本地 / Docker / K8s 沙箱；Skill 渐进加载；长期记忆；手动压缩；定时任务、MCP、IM 渠道。2.0 是与 1.x 不共享代码的重写，2026-02 底登上 GitHub Trending 第一。基于 LangGraph |

autoloop（KKKKhazix/agent-autoloop，MIT）是更小的同类内核：事件 → 规则 → 领活 → 回执 → 卡片，等待人回复时进程退出、不耗 token，跳数超过 8 停止派发 [17b]。Manus、Genspark 等商业产品本次只读到 Manus 首页（定位「action engine」），架构细节未核实。

### 5.2 与 Tioka 的对照

Tioka 已经把 SuperAgent 的两半都设计了：

| SuperAgent 要素 | Tioka |
| --- | --- |
| 事件接入、去重、研判、接单模式、Mandate、跟进订阅、回执、卡片 | ADR-020（已在 2026-11-05 修订中逐条对齐 dsh-superagent 与 autoloop） |
| 主管 + 子 Agent | ADR-027 RT-12、ADR-038 |
| 沙箱、代码执行、后台 Job | ADR-027 |
| Skill、记忆、经验 | ADR-023、ADR-022、ADR-031 |
| 权限与可信放权 | ADR-032〜034、ADR-039（两个原型都没有） |

差距不在机制，而在**产品形态**：Tioka 没有定义一个开箱即用的「通用助手」（DeerFlow 式：内置搜索、文件、代码沙箱、Skill 库、可派生临时子 Agent 的默认主管）。企业里它常作为「数字员工」的默认入口，并与第 4 节的入口路由配合（路由不确定时交给通用助手）。建议列为 M4 之后的产品项（选项 G）；其依赖（MAS-06 临时子 Agent、RT-11 沙箱）都在 M4。

---

## 6. A2A 与 MCP

### 6.1 A2A 现状（2026-11）

| 项 | 内容 | 出处 |
| --- | --- | --- |
| 版本 | v1.0.0（2026-03-12），v1.0.1（2026-05-26，最新）；v0.3.0 为 2025-07-30 | [8] |
| 治理 | Google 发起，归 Linux Foundation，Apache-2.0；A2A 官网 2026-08 文章称已加入 Agentic AI Foundation（该文未读全文） | [8] |
| Agent Card | A2A Server 发布的 JSON 元数据，描述能力、技能、认证方式；v0.3 起可签名、well-known 路径改为 `agent-card.json`；可有需认证后才给出的扩展卡片 | [8] |
| Task | 状态：submitted、working、input-required、auth-required、completed、failed、canceled、rejected；终态为后四个，中断态为 input-required 与 auth-required | [8] |
| Message / Part / Artifact | 消息由 Part 组成（text、raw、url、data 四选一）；任务产出用 Artifact，不用 Message；`contextId` 把相关任务和消息归组 | [8] |
| 传输 | JSON-RPC、gRPC、HTTP+JSON（REST）三种绑定；流式用 SSE；推送通知为向客户端 Webhook POST | [8] |
| 认证 | API Key、HTTP、OAuth2、OIDC、mTLS；v1.0 删除 implicit / password 流程，增加 device code / PKCE | [8] |
| v1.0 主要变化 | 协议定义与传输映射分离；新增 `tasks/list`（过滤、分页）；`A2A-Version` 头做版本协商；推送配置合并；枚举与命名规范化 | [8] |
| Java SDK | 官方 a2a-java：「Requires Java 17+」，Maven 多模块，JSON-RPC / gRPC / REST，参考实现基于 Quarkus，另有 Jakarta EE 社区实现；带 OpenTelemetry、JPA 任务存储、Kafka 复制队列 | [10] |
| Java 生态 | Spring AI Alibaba：A2A Server、Nacos 注册与发现；ADK Java；AgentScope Java 2.0 支持 A2A | [21][7b][19] |
| 注册中心 | Nacos 3.x 支持 Prompt、MCP、A2A 的「AI Registry」场景 | [22] |

### 6.2 MCP 现状（2026-11）

| 项 | 内容 | 出处 |
| --- | --- | --- |
| 治理 | 2025-12-09 Anthropic 将 MCP 捐给 Linux Foundation 下新成立的 Agentic AI Foundation | [11] |
| 2025-11-25 版 | 实验性 Tasks、URL 模式的 elicitation、带工具的 sampling、Client ID Metadata Documents、OIDC 发现、增量授权 | [11] |
| **2026-07-28 版（最新）** | **无状态核心**：去掉 initialize 握手与 `Mcp-Session-Id`，每个请求在 `_meta` 携带协议版本与能力，新增 `server/discover`；订阅改为 `subscriptions/listen`；Tasks 移到扩展；服务端发起的 sampling / elicitation / roots 改为「input_required」多轮请求；废弃 Roots、Sampling、Logging、**HTTP+SSE 传输**、动态客户端注册 | [11] |

### 6.3 Tioka 的缺口

1. **没有 A2A。** ADR-021 只有 MCP 客户端与「Tioka 作为 MCP Server 暴露知识检索和 Flow」。ADR-039 提到「对外暴露的 Agents API」与 Token Exchange（DAT-06），但没有协议选型。
2. **ADR-021 的 MCP 设计基于旧规范**：「Streamable HTTP 为主，兼容旧 SSE」；健康检查用「握手探活」；新规范已无握手、HTTP+SSE 已废弃。CON-05 在 M3 实现，现在修订不产生返工。
3. **概念映射是现成的**，A2A 接入不需要新造运行时：

| A2A | Tioka |
| --- | --- |
| Agent Card | 由已发布 AgentSpec 生成（名称、描述、技能 ≈ Agent 的能力说明与输出契约、认证方式 = Keycloak OAuth） |
| Task | 一个 Turn（对话型）或一个编排 Run（事件型） |
| contextId | Runtime thread |
| submitted / working | Turn `QUEUED` / `RUNNING`、`WAITING_CHILD` |
| input-required | `WAITING_APPROVAL`（kind=card）或回执 `ask` |
| auth-required | 代用户令牌缺失（ADR-021 已定「返回授权链接而不是静默改用应用身份」） |
| rejected | 编排 Decision 的 IGNORE / STOP / UNAVAILABLE，或授予校验拒绝 |
| Artifact | ADR-037 结构化产物、`file_ref` |
| SSE 流式 / 推送通知 | `rt:events` 流 / outbox 事件 |

4. **安全要点**（若做）：
   - 远端 Agent Card 与 MCP 工具描述一样视为不可信数据，接入时存快照，Agent 版本引用快照号，与 ADR-021 对 MCP 的做法一致。
   - 外发给远端 Agent 的内容要过 ADR-040 数据标签的「对外去向许可」和出网白名单（EgressClient）。
   - 委派授予不跨越系统边界：远端 Agent 只拿到发过去的内容，不继承授予；对 Tioka 而言调用远端 Agent 就是一次 EXTERNAL 副作用的工具调用，按 ADR-033 审批。
   - 入站时远端调用方是一个 Principal（服务账号或经 OAuth 的用户），走同样的授权书、Cedar 与配额。

---

## 7. 对照 Tioka 各 ADR 的逐项意见

### 7.1 ADR-020（编排，对标 agent-autoloop / dsh-superagent）

- 与两个原型的对齐已在 2026-11-05 修订中完成，复核无新差距：处置与决定、接单模式（推断事件在非 auto 下只记 would-accept）、Mandate 来由、回执重写、卡片去重、跟进订阅都与 dsh 架构文档一致 [17]。
- dsh 用「主 Session」做 LLM 研判、Executor Session 干活；Tioka 对应「前置研判 Agent」+ 正式 Run，一致。
- 建议（不需要改排期）：在 ADR-020 的「刻意不同之处」补一句 Tioka 与 SuperAgent 产品形态的关系，指向第 5 节，避免后续再被问「SuperAgent 在哪」。

### 7.2 ADR-031（观测评测，对标 AgentLoop）

- AgentLoop 的概念（AgentSpace、Agent 应用可含多个协作 Agent 并作为一个观测单元、数据飞轮、评估、实验、Agent 资产、记忆、经验、Playground）与 ADR-031 的对照表一致 [23]。
- 缺口：**多 Agent 与路由的评测维度**。建议增加三项：团队 Run 按角色出分；路由准确率（第 4 节）；「团队 vs 单 Agent 基线」实验模板，作为团队上线门槛（选项 A 第 4 点）。
- 外部 trace 接入引用的 OpenTelemetry GenAI 语义约定已迁到独立仓库 `open-telemetry/semantic-conventions-genai` [24]，实现时以该仓库为准（选项 H）。

### 7.3 ADR-038〜040（多 Agent 团队、委派、数据权限）

保持主体不变，建议四处小修订（选项 A）：

1. **任务简报必填结构**：`team.plan` 的节点增加「目标、完成标准、不做什么（边界）、建议来源」四个字段，平台校验非空。依据：Anthropic 子 Agent 简报四要素 [2]。
2. **共享决定日志**：黑板增加一类产物「决定 / 假设」（如「按 2024 版租赁合同模板理解违约金条款」），下游任务的输入自动带上与其相关的决定；与已有决定矛盾时按冲突处理。依据：Cognition「行动隐含决定」[3]；MAS-05 黑板本来就有，增量很小。
3. **投入分级**：TeamSpec 增加可选的「复杂度档位 → 最多任务数」表，主管规划时声明档位，平台校验任务数；默认档位写进平台预置的主管提示词。依据：Anthropic 的 1 / 2〜4 / 10+ 规则 [2]。
4. **上线门槛**：团队型 Agent 首次发布时，若绑定了回归数据集，EVO-06 发布门禁同时跑一个单 Agent 基线臂，报告质量差与成本比；差异不显著且成本更高时给出警告（不阻断）。依据：15 倍 token [2]、MAST「收益往往很小」[15]。

另：租赁合同审查（M3-04 LSE-05）建议在 M4 迁到 TeamSpec 时使用 `TEMPLATE` 模式，四个角色写进 `mandatoryNodes`；质检员「与判断员不同的模型族」建议做成 `verify` 节点上的可校验约束（模型多样性），而不只是配置约定。

ADR-039 的链式收窄、祖先禁区、`on_behalf_of` 在调研范围内没有发现同等机制的框架（Claude Agent SDK 有工具白名单、最大派生深度 3、并发 20、预算上限 [20]；OpenAI handoff 有输入过滤 [4]），无需修改。

### 7.4 ADR-043（Flow）

- 「Flow 只做一次 Run 内的确定性流程、自主协作归 ADR-038」与 Anthropic 的 workflow / agent 划分 [1]、OpenAI「LLM 决策 vs 代码编排」[4]、CrewAI Flows 编排 Crews [7c]、MS Agent Framework 的 Agent / Workflow 二分 [6]、ADK 工作流 Agent [7b] 一致。
- 节点目录覆盖了 Anthropic 五种工作流模式：链式（顺序连线）、路由（Switch）、并行（Map / 并行分支）、编排-执行（Agent 节点 + 团队 Agent）、评审-优化（Loop + StructuredExtract）。
- 缺口很小：建议增加「意图分类」节点模板（第 4.3 节第 7 点），不改引擎。

### 7.5 ADR-024（Model Hub 路由）

- 设计（类型化路由计划、决策器 SPI、超时兜底、只在 Turn 开始决策、胜任度先记录不参与决策）与 RouteLLM、vLLM Semantic Router 的「可编程决策层」思路一致 [13][13b]。
- 建议：
  1. 增加内置的「向量匹配」决策器（毫秒级，适合 BRANCH 模式的类别判定），并与 Agent 路由共用（选项 B 的一部分）。
  2. 「同组优先沿用上一模型」（`preferPreviousInGroup`）对提示缓存命中很关键，建议在 ADR 里写明这一理由，防止实现时被当作可有可无。
  3. 多 Agent 场景下各角色的模型由角色 Agent 自己的路由计划决定，这一点正确；补一条「团队内可声明模型多样性约束」，供 `verify` 节点使用（见 7.3）。

### 7.6 ADR-016 / ADR-023 / ADR-027（单 Agent 内核）

- **工具检索**（选项 C）：Agent 绑定的工具超过阈值（建议 15 个或定义超过 10K token）时，`listTools` 只给常用工具 + 内置 `tool.search`，其余按需加载；检索范围仍限于授予内的工具。MCP 快照里的工具描述作为检索语料。
- **护栏挂点**（选项 D）：在 Turn 管线明确三个挂点：输入（相关性、越狱、PII）、工具前（已有，ADR-033）、最终输出（Schema、PII、敏感词、引用存在性）。挂点为 SPI，与 AgentScope Java 的中间件阶段 [19] 类似；规则型检查直接实现，模型型检查经 ModelGateway。
- **结构化笔记**：长任务允许 Agent 在工作盘维护待办 / 笔记文件，压缩时保留（Anthropic「structured note-taking」[16]）。这只需在 RT-10 压缩规则里加一句，不单列选项。

---

## 8. 风险与注意事项

| 风险 | 说明 | 对策 |
| --- | --- | --- |
| 成本失控 | 多 Agent 约 15 倍 token [2] | 团队预算（已有）+ 投入分级 + 基线门槛（选项 A） |
| 跨 Agent 的提示注入 | 合同原文、MCP 工具结果、远端 A2A 回复都可能含注入，经子 Agent 产物传到主管 | 已有：MCP 自述不可信、主管不能生成能力、授予只收窄。补充：产物中来自外部原文的字段在主管上下文里标注为引用数据（结构化产物天然便于做到） |
| 路由误判 | 入口路由把请求交错 Agent | 权限先过滤、低置信度澄清、留痕与评测（选项 B） |
| 协议漂移 | MCP 2026-07-28 是破坏性修订；A2A 1.0 与 0.3 不兼容 | CON-05 按新规范实现并兼容 2025-11-25；A2A 只做 1.0（选项 E、F） |
| 框架依赖 | 引入 AgentScope Java / Spring AI Alibaba 整套框架会与 Tioka 自有 Runtime 重叠 | 不整体引入；仅在 A2A 协议层考虑直接用官方 a2a-java SDK 或参考其实现 |
| 标准未定 | OTel GenAI Agent span 语义仍在独立仓库演进 [24] | 导出层做映射，不把字段名写死进核心表 |

---

## 9. 待负责人决定的选项

人日为粗估，后端 / 前端分列；「M3 净增」会推后 M3 结束周（当前 M3 为 W20–W48，后端实际 W24 起步）。

| 选项 | 内容 | 涉及 ADR | 粗估 | 可选阶段 | 建议 |
| --- | --- | --- | --- | --- | --- |
| **A** | ADR-038 四处小修订：任务简报必填结构、共享决定日志、投入分级、团队上线基线门槛；租赁审查迁移时用 TEMPLATE 并把模型多样性做成可校验约束 | ADR-038、ADR-031、ADR-024（一句） | 后端约 3（并入 MAS-04/05、EVO-06） | M4（随 MAS-04） | **建议采纳**，不影响 M3 |
| **B** | 新立 ADR：入口意图路由（ROUTER 型 Agent，分层级联，权限先过滤，受控交接，路由评测）；ADR-024 增加向量匹配决策器并与 Agent 路由共用 | 新 ADR-044、ADR-024、ADR-022、ADR-031 | 后端约 7，前端约 4 | ① M3（M3 再延约 1 周）② M4 P1 ③ M4 之后 | **建议 ②**：合同场景不依赖它，M4 的「数字员工入口」需要它 |
| **C** | 工具检索：超过阈值时延迟加载 + `tool.search` | ADR-016、ADR-023 | 后端约 3 | ① M3 随 CON-05 ② M4 P2 | **建议 ②**；若 M3 试点 Agent 绑定的 MCP 工具已超 15 个则改 ① |
| **D** | 护栏挂点 SPI（输入、最终输出），与已有工具前置策略并列 | ADR-016、ADR-025 | 后端约 4 | ① M4 P2 ② M4 之后 | 建议 ① |
| **E** | ADR-021 修订：MCP 客户端与服务端按 2026-07-28 规范（无状态、`server/discover`、input_required），兼容 2025-11-25；不实现已废弃的 HTTP+SSE 与 Sampling；健康检查改用 discover | ADR-021、34 号计划 CON-05 | M3 净增后端约 2 | M3（CON-05 本身在 M3） | **建议采纳**，越早改越便宜 |
| **F** | A2A：第 1 步只在 ADR 里写映射与安全规则（第 6.3 节）；第 2 步出站客户端（远端 Agent 作为 Connector 资产，Agent Card 快照，可作工具或团队角色）；第 3 步入站服务端（已发布 Agent 新增 A2A 发布形态，Keycloak OAuth） | 新 ADR-045、ADR-021、ADR-038、ADR-039 | 第 1 步 0；第 2 步后端约 5；第 3 步后端约 6、前端约 2 | 第 1 步 M3 文档；第 2、3 步 ① M4 P2 ② M4 之后 | **建议第 1 步现在做，第 2、3 步放 M4 之后**；单租户企业内部先用不上跨系统 Agent 互通，除非客户已有其他 Agent 平台需要对接 |
| **G** | 「通用助手」产品模板（DeerFlow 式默认主管 + 沙箱 + Skill 库 + 临时子 Agent），作为入口路由的兜底 Agent | ADR-038、ADR-023 | 后端约 4，前端约 3 | M4 之后 | 建议 M4 之后 |
| **H** | 轨迹导出与 GOV-08 链路追踪对齐 OTel GenAI 语义约定（新仓库） | ADR-031、ADR-025 | 约 0.5 | 随 GOV-08 / EVO-01 | 建议采纳 |

如果全部按「建议」执行：M3 净增后端约 2 人日（选项 E），M3 排期基本不变；M4 净增后端约 17、前端约 4 人日（A、B、C、D、H），需要在写 M4 计划时核算是否顺延。

---

## 参考资料

所有资料于 2026-11-06 读取。

1. Anthropic, *Building effective agents*（2024-12-19，2026-10 更新）. https://www.anthropic.com/research/building-effective-agents
2. Anthropic, *How we built our multi-agent research system*（2025-06-13）. https://www.anthropic.com/engineering/multi-agent-research-system
3. Cognition（Walden Yan）, *Don't Build Multi-Agents*（2025-06-12）. https://cognition.com/blog/dont-build-multi-agents
4. OpenAI Agents SDK 文档：Agent orchestration https://openai.github.io/openai-agents-python/multi_agent/ ；Handoffs https://openai.github.io/openai-agents-python/handoffs/
5. OpenAI, *A practical guide to building agents*. https://cdn.openai.com/business-guides-and-resources/a-practical-guide-to-building-agents.pdf
6. Microsoft, Agent Framework overview. https://learn.microsoft.com/en-us/agent-framework/overview/agent-framework-overview
7. 框架文档：
   - 7a. LangChain, Multi-agent. https://docs.langchain.com/oss/python/langchain/multi-agent ；Deep Agents https://docs.langchain.com/oss/python/deepagents/overview
   - 7b. Google ADK, Workflows https://adk.dev/workflows/ ；Workflow agents https://adk.dev/agents/workflow-agents/ ；Agent routing https://adk.dev/agents/routing/ ；A2A https://adk.dev/a2a/
   - 7c. CrewAI, Tasks / Processes https://docs.crewai.com/en/concepts/tasks ；Flows https://docs.crewai.com/en/concepts/flows
8. A2A 协议：仓库 https://github.com/a2aproject/A2A ；发布记录 https://github.com/a2aproject/A2A/releases ；规范 https://a2a-protocol.org/latest/specification/
9. A2A and MCP. https://a2a-protocol.org/latest/topics/a2a-and-mcp/
10. A2A Java SDK. https://github.com/a2aproject/a2a-java
11. MCP 规范：最新版 https://modelcontextprotocol.io/specification/latest ；变更记录 https://modelcontextprotocol.io/specification/latest/changelog ；2025-11-25 变更 https://modelcontextprotocol.io/specification/2025-11-25/changelog ；MCP 加入 AAIF https://blog.modelcontextprotocol.io/posts/2025-12-09-mcp-joins-agentic-ai-foundation/
12. aurelio-labs, semantic-router. https://github.com/aurelio-labs/semantic-router
13. vLLM Semantic Router. https://github.com/vllm-project/semantic-router ；13b. RouteLLM https://github.com/lm-sys/RouteLLM
14. Anthropic, *Introducing advanced tool use*（2025-11-24）. https://www.anthropic.com/engineering/advanced-tool-use
15. Cemri et al., *Why Do Multi-Agent LLM Systems Fail?*（arXiv 2503.13657 v3）. https://arxiv.org/abs/2503.13657
16. Anthropic, *Effective context engineering for AI agents*（2025-09-29）. https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents
17. deepseek-ai, dsh-superagent-prototype. https://github.com/deepseek-ai/dsh-superagent-prototype ；架构 https://github.com/deepseek-ai/dsh-superagent-prototype/blob/main/docs/007-architecture.md ；17b. KKKKhazix, agent-autoloop https://github.com/KKKKhazix/agent-autoloop
18. bytedance, DeerFlow. https://github.com/bytedance/deer-flow
19. AgentScope：Python https://github.com/agentscope-ai/agentscope ；Java https://github.com/agentscope-ai/agentscope-java
20. Claude Agent SDK, Subagents. https://code.claude.com/docs/en/agent-sdk/subagents
21. Spring AI Alibaba：仓库 https://github.com/alibaba/spring-ai-alibaba ；多智能体 https://java2ai.com/docs/frameworks/agent-framework/advanced/multi-agent ；A2A https://java2ai.com/docs/frameworks/agent-framework/advanced/a2a
22. Nacos. https://nacos.io/
23. 阿里云 AgentLoop 核心概念. https://help.aliyun.com/zh/agentloop/product-overview/core-concept
24. OpenTelemetry GenAI 语义约定（已迁移）. https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-agent-spans/ → https://github.com/open-telemetry/semantic-conventions-genai

**本次未核实**：LangGraph supervisor / swarm 的现行官方说明；Microsoft Agent Framework 1.0 GA 日期与 handoff / group chat / magentic 模式名；百炼多智能体与意图分类节点文档；OWASP Agentic Top 10（2026）条目清单（资源页 2025-12-09 发布，下载失败）；MAST 14 种失败模式名称；Genspark Super Agent 架构。

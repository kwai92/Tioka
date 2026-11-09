# Tioka 代码与设计一致性校验 · 行业调研报告

> 本文档是 G2 阶段产物，定位为**行业调研报告（research_report）**。上游输入为主理人转交的用户诉求与 G1 冻结产物 `material_digest.md`；下游用途是为 `business-architect` 在高层架构设计 §3 提供可追溯的行业证据链。
>
> **工具说明**：由 `research-analyst`（研究分析师 - 查有据）产出，经 G2 自动校验与 AskUserQuestion 人工审核通过后方可进入下游消费。
>
> **结构纪律**：全文按「事实 → 对比 → 建议 → 风险」四段式组织（§2 事实、§3 对比、§4 建议、§5 风险），四段之间不倒序、不跳段。事实、推断、综合归纳、建议、风险分别标注，不混用。
>
> **裁决定位**：本报告全部结论均为**建议**，不构成对下游业务架构师的冻结输入；加权打分是评估而非授权，最终业务边界由 `business-architect` 在 G3 冻结。

---

## 0. 元信息：修订记录

```yaml
标题: Tioka 代码与设计一致性校验 - 行业调研报告 v1.0
版本: v1.0
状态: Reviewing
创建日期: 2026-11-08
最后更新: 2026-11-08
调研人: research-analyst（研究分析师 - 查有据）
审核人:
  - team-lead（主理人，G2 自动校验与 AskUserQuestion 人工审核）
关联文档:
  上游输入:
    - 用户诉求: 现在这个项目已经有了一部分设计，我现在需要你们进行代码与设计的一致性校验。并且审查现有的代码以及设计，进行后续的链路规划与实现
    - 调研目标: 主理人转交的 Q1 至 Q5 五条收敛问题
    - 上游完整原文: .workbuddy/output/material_digest.md（G1 已冻结，511 行）
  下游产出:
    - 高层架构设计 §3 行业调研: 由 business-architect 整合，本角色不冻结边界
```

| 版本 | 日期 | 作者 | 变更内容 | 评审状态 |
| --- | --- | --- | --- | --- |
| v1.0 | 2026-11-08 | research-analyst（查有据） | 首轮全量行业调研：五条收敛问题、六家标杆、五维加权矩阵、取舍建议、风险与待确认项、可核验来源目录、四次中间确认自检 | 待 G2 审核 |

### 0.1 项目基线对齐（引自 G1 冻结原文，非本报告假设）

本节数字全部直接引用 `material_digest.md`，确保调研结论不凭空假设项目形态。

| 基线项 | 登记值 | 出处 |
| --- | --- | --- |
| 产品定位 | 企业级 Agent 套件：自研 Runtime + Orchestration + Chat/KB/Flow + Connector + HCI + Foundation + Model Hub + 资产控制面 | D1，§1 首段 |
| 后端栈 | Java 17 / Spring Boot 3.2.12 多模块；883 个 main 源 + 258 个 test 源 Java | D13，§1；D19，§5；X14 |
| 业务域 | 15 个业务域，每域 api 与 biz 双子模块 | D13，§2 与 §3；D15，§1 |
| 数据层 | 41 份 Flyway 迁移；另有 45 份 contracts/db SQL 副本并存 | D16，§2；X7；V5 |
| 前端 | Vue 3 + Pinia + Element Plus + Vite | D17，§1；X12 |
| 私有化中间件 | compose 五服务：PostgreSQL pgvector、Redis、Keycloak、RocketMQ namesrv、RocketMQ broker | D18，§2 |
| 测试信号 | 85 份 Surefire 报告、330 用例、0 失败、0 错误、1 跳过 | D19，§2；D5，§progress |
| 已登记差距 | 假实现 API、单机内存状态、MQ 闭环未证实、模块树漂移、完成度口径多套并存、非 mock 验收缺口 | D7，§01；X2/X8/X9/X13；V6/V7 |
| 硬边界 | 允许自研全部运行时与产品面、借鉴任意开源能力清单与协议；禁止把外部 harness 或云 AgentRun 当执行依赖、biz 直连外部 SDK、事件正文决定路由或权限、biz 互依 | D3，§S0 1.1 至 1.2 |
| 生命周期口径 | 系统级生命周期走 MQ（S0 规定） | D3，§S0 5；X13 |
| G1 锁定口径 | X1 以 34-M0至M4推进规划与工作项.md 为执行权威；X9 采用 34 号 G0–G4/GF 为里程碑口径、S0 §13 的 G1–G7 为能力门禁清单，两套标签不混用 | 主理人 G1 锁定说明 |

---

## 1. 调研问题收敛

### 1.1 原始调研种子

从用户诉求、主理人给定方向与 G1 冻结的差距登记中提取，逐条给出优先级。

| 编号 | 待调研论题 | 来源（用户诉求要点 / G1 证据） | 调研优先级 | 备注 |
| --- | --- | --- | --- | --- |
| S1 | 企业级 Agent Runtime 主链路的异步 Turn、多步工具循环、SSE 事件续传、审批挂起与恢复、按会话加锁，业界如何做成可持久化、可水平扩展的真实链路 | 用户诉求「审查现有的代码」「后续链路规划」；D7，§01「单机内存状态、Runtime 同步请求模型」；主理人 Q1 | 高 | 直接对应 G1 登记的 L3 语义玩具化 |
| S2 | 编排与异步骨干的事务 Outbox、MQ 生产消费幂等、数据库行锁 claim、事件溯源如何落地 | D3，§S0 5；X13「生产消费为 0、闭环需实测」；D14，§3 messaging 三类；主理人 Q2 | 高 | 对应 34 号 M2「RocketMQ 加 outbox」 |
| S3 | 非 mock 验收与完成度门禁：真实依赖集成测试、LLM 录制回放桩、双实例 HA、Gate 分级的工程做法 | V2/V7；X5；D5，§31 11 完成度词表；w1 签字页 SLICE/FIDELITY/EXIT；主理人 Q3 | 高 | 绿信号不等于 Exit，G1 已冻结 |
| S4 | 模型网关统一路由、配额与成本归集、审计哈希链、OpenTelemetry 全链路 trace 的标杆实践 | D4，§ADR-017/024/041/042/072/073/075；D3，§S10 2；主理人 Q4 | 中高 | 对应 M1-03 模型网关与配额 |
| S5 | 代码与架构一致性守护：架构规则自动测试、契约测试、文档单源与自动生成的标准化做法 | D2，§44-EA 2 R1 至 R6 与 W0-09；D11，§contracts/README 契约测试；X3/X4/X8；主理人 Q5 | 高 | 本轮诉求的核心动作「一致性校验」 |

### 1.2 调研问题收敛

收敛为 5 条可执行问题，每条明确调研对象、目标与预期产出。方向严格沿用主理人给定的 Q1 至 Q5，不泛化发散。

| 编号 | 调研问题 | 调研对象 | 调研目标 | 预期产出 | 关联种子 |
| --- | --- | --- | --- | --- | --- |
| Q1 | 主流开源与商业 Agent Runtime 如何把异步 Turn、多步工具循环、SSE 续传、审批挂起恢复、按会话加锁做成可持久化且可水平扩展的工程范式 | LangGraph、Dify、OpenAI Responses API 与 Agents SDK、Amazon Bedrock Agents、MDN SSE 标准 | 抽取状态持久化、事件续传锚点、interrupt 与 resume、会话级并发控制的可借鉴设计 | 主链路范式清单 + 横向事实表 + 对标 Tioka 单机内存状态的差距映射 | S1 |
| Q2 | 事务 Outbox、MQ 幂等、行锁 claim、事件溯源在同类系统里的落地约束与失败语义是什么 | microservices.io 模式库、PostgreSQL 官方邮件列表队列实践、Fowler 事件架构文章 | 明确至少一次投递下的幂等键设计、claim 的 in-flight 状态与崩溃清理、事件日志作为主记录的边界 | 异步骨干工程约束清单 + 与 Tioka framework/messaging 和 41 份迁移的对接点 | S2 |
| Q3 | 真实依赖集成测试、LLM 录制回放桩、双实例 HA、Gate 分级在 CI 与验收中的分工是什么 | Testcontainers 官方文档、OpenTelemetry demo Agent Service、Langfuse 评测与数据集 | 区分契约回归与真实模型验收，避免把回放绿灯当作 Exit | 非 mock 验收分层建议 + 与 31 完成度词表和 X9 口径的对齐关系 | S3 |
| Q4 | 模型网关如何统一路由、配额、成本归集，审计哈希链与 OTel 全链路 trace 如何标准化 | LiteLLM Gateway 架构文档、OpenTelemetry GenAI 语义约定、AWS QLDB 与 Aurora PostgreSQL 审计迁移 | 抽取请求生命周期、后台异步记账、哈希链完整性与 gen_ai 字段约定 | 网关与可观测参考架构 + 与 ADR-072/073/075 的字段对齐建议 | S4 |
| Q5 | 架构规则自动测试、契约测试、文档单源在同类项目里如何标准化守护代码与设计一致 | ArchUnit 官方、Spring Cloud Contract CDC、microservices.io 模式语言 | 明确分层与环检测规则、契约独立版本化、破坏性变更流程 | 一致性守护工具链建议 + 与 44-EA R1 至 R6 和 W0-17 契约冻结的落点 | S5 |

### 1.3 自检留痕（自检点 1）

完成 §1 收敛后按公共协议 §2.1 判定、§2.3 反向验证 3 问；完整判定与证据见附录 C.1，此处仅登记结论：**未命中触发标准，不发起中间确认**。主因是主理人已给定 Q1 至 Q5 五条收敛问题并明确「围绕这些收敛、不得泛化发散」，上游对该决策点已做出选择。

---

## 2. 事实：标杆系统盘点和方案详述

> 四段式「事实」段。只陈列调研发现的事实，按已核实 / 推断 / 综合归纳逐行标注置信度，不做引申建议、不排序、不做边界裁决。

### 2.1 行业标杆清单

**硬指标**：共 6 家，≥ 3 家；头部 SaaS 代表为 B3 OpenAI 与 B4 AWS Bedrock Agents；开源或自研代表为 B1 Dify、B2 LangGraph、B5 LiteLLM、B6 Langfuse。

| 编号 | 标杆系统 | 厂商 / 社区 | 部署形态 | 场景覆盖 | 技术亮点 | 商业模式 | 调研来源 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| B1 | Dify | LangGenius / 开源社区 | 开源自托管 + Cloud SaaS | Q1 SSE 续传 / workflow 人审暂停 / Agent 工具循环 | workflow_run_id 重连、include_state_snapshot、continue_on_pause | 开源 + 商业订阅 | SR-01 / SR-02 / SR-03 |
| B2 | LangGraph | LangChain | 开源库 + LangGraph Platform 托管 | Q1 状态机 / 审批挂起恢复 / 按会话加锁 | checkpointer、interrupt 与 Command(resume)、thread_id、PostgresSaver | 开源 + 平台商用 | SR-04 / SR-05 / SR-06 |
| B3 | OpenAI Responses API 与 Agents SDK | OpenAI | 头部商业 SaaS（闭源托管） | Q1 agentic loop / conversation state / paused runs | previous_response_id、WebSocket stream_id 通道、Items 事件模型 | 按量计费 | SR-08 / SR-09 / SR-10 / SR-11 |
| B4 | Amazon Bedrock Agents | AWS | 头部商业 SaaS（云托管） | Q1 多步编排 / 会话历史 / 分步 trace | InvokeAgent、TracePart、OrchestrationTrace、FailureTrace | 云按量计费 | SR-12 / SR-13 |
| B5 | LiteLLM Gateway | BerriAI 社区 | 开源自托管 + Enterprise | Q4 统一路由 / 配额 / 成本归集 | virtual key 预算、rpm/tpm 限流、router 回退重试、异步 spend logging | 开源 + Enterprise 许可 | SR-25 / SR-26 |
| B6 | Langfuse | Langfuse / ClickHouse | 开源自托管 MIT + Cloud | Q3 评测 / Q4 trace 与成本 | 层次化 trace、datasets 与 experiments、LLM-as-a-judge、OTel 原生 | 开源 MIT + Cloud 订阅 | SR-24 / SR-27 |

### 2.2 标杆方案详述

> 每家一张 10 维度卡片，置信度逐行标注。**已核实** = 官方文档或规范正文明确记载；**推断** = 由官方事实结合本项目语境的判断，已注明来源局限；**综合归纳** = 多条官方事实的压缩归并。定价与私有化条款公开资料不足处一律标推断，不写成已核实事实。

#### 2.2.1 B1 - Dify

| 维度 | 内容 | 置信度 |
| --- | --- | --- |
| 产品定位 | 开源 LLMOps 与 Agent 应用平台，提供 Chatflow、Workflow、Agent 三类应用形态与服务 API | 已核实（SR-02） |
| 目标用户 | 需要可视化编排与私有化交付的应用开发团队、平台工程团队 | 综合归纳（SR-01 / SR-02 / SR-03） |
| 核心能力 | SSE 流式回复、workflow 人工介入表单、Agent 自主工具循环、会话与消息历史、文件与语音 | 已核实（SR-02 / SR-03） |
| 架构特点 | 流上带 task_id 与 workflow_run_id 双标识；断线可用 workflow_run_id 重开事件流并先重放已完成节点状态；continue_on_pause 让同一条流跨多次人工介入不断开 | 已核实（SR-01「Reconnect and Resume」段） |
| 部署形态 | 开源自托管为主，另有 Cloud SaaS；Agent 运行有 1 小时默认上限与单轮 500 次模型请求上限，自托管可调环境变量 | 已核实（SR-02） |
| 集成方式 | 服务 API（HTTP 加 SSE）、CLI difyctl、表单 form_token 与 submit run form | 已核实（SR-01 / SR-03） |
| 定价模式 | 开源自托管可自行部署；Cloud 为订阅与用量计费；中国区私有化商务报价未取得公开可核验资料 | 推断（官方仅给功能与环境变量，未给本轮所需报价章节） |
| 优势 | 事件续传锚点、状态快照重放、跨多次人审保持单流，直接对应 Tioka 的 SSE 续传与审批挂起恢复缺口 | 综合归纳（SR-01 与 G1 的 D7，§01） |
| 局限 | 工作流引擎与 Tioka 自研 Orchestration 职责重叠；Python 栈，不可作为 Java 运行时依赖引入 | 推断（依据 D3，§S0 1.1 至 1.2 硬边界与 D13，§1 栈） |
| 对本项目的参考价值 | 复刻「事件 id 单调递增 + 运行 id 重连 + 状态快照 + 暂停续跑」四件套的事件契约，不引入其引擎 | 推断 |

#### 2.2.2 B2 - LangGraph

| 维度 | 内容 | 置信度 |
| --- | --- | --- |
| 产品定位 | 面向有状态、长时运行 Agent 的底层编排框架，提供持久化、记忆、human-in-the-loop 与流式 | 已核实（SR-06 开篇） |
| 目标用户 | 需要自建 Agent 运行时与 durable execution 的框架使用者 | 综合归纳（SR-04 / SR-05 / SR-06） |
| 核心能力 | checkpointer 线程状态快照、store 跨线程长期记忆、interrupt 动态挂起、Command(resume) 恢复、time travel、故障恢复 | 已核实（SR-04 / SR-05） |
| 架构特点 | Graph API 每个 super-step 落 checkpoint；thread_id 是持久游标，复用即恢复同一 checkpoint；interrupt 在任意节点条件触发并用持久 checkpointer 落库 | 已核实（SR-04「Checkpointer vs store」；SR-05「Pause using interrupt」） |
| 部署形态 | 开源库可嵌入任意进程；生产用 PostgresSaver 或 SqliteSaver；官方明确指出 InMemorySaver 与 MemorySaver 在进程重启后全部丢失，不可用于生产 | 已核实（SR-04「Troubleshooting」段） |
| 集成方式 | Python 与 JavaScript 双 SDK；Functional API 用 entrypoint 与 task 把持久化能力加到普通函数上 | 已核实（SR-06 / SR-05） |
| 定价模式 | 开源框架，另有 LangGraph Platform 商用托管；本轮未取得平台公开定价表 | 推断（官方文档仅描述能力，未给定价章节） |
| 优势 | 直接给出「状态必须落库而非内存」「按 thread_id 定位恢复点」「审批作为一等中断」的可复刻范式 | 综合归纳（SR-04 / SR-05） |
| 局限 | 无 Java 一等实现；checkpoint 无限增长需保留策略；thread_id 在 PostgresSaver 有 255 字符列长限制 | 已核实（SR-04「Troubleshooting」段） |
| 对本项目的参考价值 | 用 Tioka 现有 PostgreSQL 建 checkpoint 与 interrupt 表，按 thread_id 加行锁；对应 G1 登记的单机内存状态与 RT Turn 状态机 | 推断 |

#### 2.2.3 B3 - OpenAI Responses API 与 Agents SDK

| 维度 | 内容 | 置信度 |
| --- | --- | --- |
| 产品定位 | 有状态的 agentic API 原语与轻量 Agent SDK，单请求内可多轮工具调用 | 已核实（SR-11「About the Responses API」） |
| 目标用户 | 直接调用托管模型服务构建 Agent 应用的开发者 | 综合归纳（SR-08 / SR-09 / SR-11） |
| 核心能力 | previous_response_id 链继续、store 默认开启、Items 统一消息与工具调用、流式事件增量、审批作为暂停运行、WebSocket 通道 | 已核实（SR-08 / SR-09 / SR-10 / SR-11） |
| 架构特点 | 官方明确要求把审批视为 paused run 而非新 turn，取消或中断后从同一 state 恢复未完成 turn；WebSocket 同一 stream_id 按先进先出且不重叠，不同通道可并发 | 已核实（SR-09「Handle pauses and failures deliberately」；SR-10「Connection behavior and limits」） |
| 部署形态 | 纯托管 SaaS；Response 对象默认留存 30 天，可设 store 为 false；零数据留存组织强制 store 为 false 并用加密 reasoning item 维持无状态 | 已核实（SR-08「Data retention」；SR-11「Migrate」） |
| 集成方式 | HTTP Responses API、WebSocket、官方多语言 SDK 与 Agents SDK | 已核实（SR-08 / SR-09 / SR-10） |
| 定价模式 | 按 token 计费；即使使用 previous_response_id，链上全部历史输入 token 仍计入计费 | 已核实（SR-08「Conversation state」） |
| 优势 | 会话状态与暂停恢复的语义定义清晰，可作为 Tioka SSE 事件契约与 Turn 状态机的语义参照 | 综合归纳（SR-08 / SR-09） |
| 局限 | 闭源托管、数据需出境、无法私有化；明确落入 Tioka S0 硬边界「禁止把外部 harness 或云 AgentRun 当执行依赖」 | 已核实（硬边界见 D3，§S0 1.1 至 1.2） |
| 对本项目的参考价值 | 仅借鉴 previous_response_id 血缘语义、paused run 与通道先进先出的契约命名，不引入其服务 | 推断 |

#### 2.2.4 B4 - Amazon Bedrock Agents

| 维度 | 内容 | 置信度 |
| --- | --- | --- |
| 产品定位 | 全托管的多步任务编排 Agent 服务，用基础模型推理拆解任务并调用企业系统 API | 已核实（SR-12；AWS 文档总览） |
| 目标用户 | 已在 AWS 上、希望免运维基础设施的企业 | 综合归纳（SR-12 与 AWS 文档总览） |
| 核心能力 | 预处理、编排循环、知识库检索、后处理、运行时 trace、会话历史保留 | 已核实（SR-12「协同运作包含下列步骤」） |
| 架构特点 | 编排阶段循环调用 FM、解析响应、决定动作组或知识库查询并把观察回灌增强提示，直到产出最终响应或需追问用户；trace 随 InvokeAgent 流的每个 chunk 返回 TracePart，含 sessionId、traceId、OrchestrationTrace 与 FailureTrace | 已核实（SR-12 / SR-13「Structure of the trace」） |
| 部署形态 | 全托管 serverless，无需管理基础设施；不提供本地私有化运行时 | 已核实（AWS 文档总览「fully managed service」） |
| 集成方式 | InvokeAgent API、动作组 Lambda、知识库、trace 事件 | 已核实（SR-12 / SR-13） |
| 定价模式 | 云按量计费；本轮未取得可核验的具体单价章节 | 推断（官方仅描述能力，未取到定价表） |
| 优势 | TracePart 字段（agentId、agentVersion、sessionId、traceId、callerChain）对统一审计事件模型有字段级参考意义 | 已核实（SR-13「Structure of the trace」） |
| 局限 | 云托管执行依赖与数据出境，与 Tioka 私有化 compose 五服务及 S0 硬边界直接冲突 | 已核实（硬边界见 D3，§S0 1.1 至 1.2；compose 见 D18，§2） |
| 对本项目的参考价值 | 仅作审计与 trace 字段命名的对照，不作为执行底座候选 | 推断 |

#### 2.2.5 B5 - LiteLLM Gateway

| 维度 | 内容 | 置信度 |
| --- | --- | --- |
| 产品定位 | 开源、OpenAI 兼容的统一 LLM 网关，单端点接 100 加家供应商并管密钥、预算与成本 | 已核实（SR-25 / SR-26） |
| 目标用户 | 需要集中管控多模型接入与成本归集的平台团队 | 综合归纳（SR-25 / SR-26） |
| 核心能力 | virtual key 与预算校验、key/用户/团队/服务端四级 rpm 与 tpm 限流、router 负载均衡与回退重试、spend 与 usage 记账、guardrails | 已核实（SR-25「Life of a Request」） |
| 架构特点 | 请求先查 virtual key（先 Redis 或内存缓存后 DB）、再限流、再 router、最后供应商翻译；响应返回客户端后，spend logging、限流计数与日志回调全部走异步后台任务，官方明确「no database write sits in the request path」 | 已核实（SR-25「High Level architecture」与「Frequently Asked Questions」） |
| 部署形态 | 自托管容器或 CLI，另有 Enterprise；PostgreSQL 存 keys/teams/spend，Redis 存 key 缓存与限流计数 | 已核实（SR-25 架构图；SR-26） |
| 集成方式 | OpenAI 兼容端点、Python SDK、回调（Langfuse、MLflow 等）、virtual key | 已核实（SR-25 / SR-26） |
| 定价模式 | 开源自托管；Enterprise 许可提供 SSO、SCIM、管理员变更审计日志与多区域；官方称压测可处理每秒 1500 加请求 | 已核实（SR-26；Enterprise 说明见 SR-25 关联页） |
| 优势 | 请求生命周期四段式与「记账异步、不进请求路径」的隔离，直接对应 Tioka Model Hub 的配额与成本归集 | 综合归纳（SR-25 与 D4，§ADR-017） |
| 局限 | Python 栈且需额外 PostgreSQL 与 Redis；整体引入会与 Tioka 自研 model 域职责重叠并改变部署面 | 推断（依据 D15，§3 model 域与 D18，§2 五服务） |
| 对本项目的参考价值 | 复刻 virtual key、四级限流、router 回退与异步 spend logging 的职责划分，不改现有 compose 五服务 | 推断 |

#### 2.2.6 B6 - Langfuse

| 维度 | 内容 | 置信度 |
| --- | --- | --- |
| 产品定位 | 开源 Agent 可观测与评测平台，把 tracing、监控、数据集、实验、评测连成闭环 | 已核实（SR-24 / SR-27） |
| 目标用户 | 需要用生产数据驱动评测与回归的 AI 工程团队 | 综合归纳（SR-24 / SR-27） |
| 核心能力 | 层次化 trace（每次 LLM 调用、工具调用、检索步骤）、datasets 与 experiments、LLM-as-a-judge 与人工评审、human annotation 生成 golden dataset、prompt 管理与回滚、cost 与 latency 看板 | 已核实（SR-24「The full AI engineering loop」） |
| 架构特点 | ClickHouse OLAP 库、经 Redis 队列异步摄取、大 payload 放 S3 或 blob、prompt 边缘缓存；官方称 tracing 不阻塞应用，后台处理并自动批处理 | 已核实（SR-27「Architecture」「Async by default」） |
| 部署形态 | 全功能 MIT 开源自托管（Docker Compose、Helm、Terraform）；另有 Cloud | 已核实（SR-24「Open platform. Open source.」；SR-27「Full-stack open-source」） |
| 集成方式 | OTel 原生、Python 与 TypeScript SDK、100 加集成、REST API、CLI 与 MCP | 已核实（SR-24「Works with any stack」） |
| 定价模式 | 自托管 MIT 全功能无用量限制；Cloud 有免费额度与公开定价；官方列 SOC 2 Type II、ISO 27001、GDPR 与欧美数据区域 | 已核实（SR-24 / SR-27「Security and compliance」） |
| 优势 | 把一条生产 trace 转成测试用例再对比 prompt 版本，为 Tioka 的非 mock 验收与 Gate 分级提供范式 | 综合归纳（SR-27「A complete platform」） |
| 局限 | 自托管需引入 ClickHouse、Redis、S3，超出 Tioka compose 五服务；不宜整体引入，宜只借鉴语义与字段 | 推断（依据 D18，§2 五服务） |
| 对本项目的参考价值 | 借鉴 gen_ai 字段约定、trace 转数据集的评测闭环与成本归集口径，落在现有 OTel 埋点上 | 推断 |

### 2.3 关键技术能力横向事实

> 不评分、不排序，仅按能力维度横陈各方案事实，并在末列给出来源编号。

| 能力维度 | B1 Dify | B2 LangGraph | B3 OpenAI | B4 Bedrock | B5 LiteLLM | B6 Langfuse | 说明 / 来源 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 会话状态持久化 | conversation 消息历史可查 | checkpointer 线程快照，生产用 PostgresSaver | previous_response_id 链，Response 默认留存 30 天 | session 存储对话历史，随 InvokeAgent 续接 | 不在职责范围 | sessions 聚合 trace | SR-01 / SR-04 / SR-08 / SR-12 / SR-24 |
| 审批挂起与恢复 | workflow 人工介入表单，form_token 提交后继续 | interrupt 动态挂起，Command(resume) 恢复 | 官方要求把审批当 paused run 而非新 turn | 需追问用户时循环暂停 | 不在职责范围 | human annotation 工作流 | SR-03 / SR-05 / SR-09 / SR-12 / SR-24 |
| SSE 事件续传锚点 | workflow_run_id 重开流，include_state_snapshot 重放 | 检查点即持久游标，thread_id 复用即恢复 | WebSocket stream_id 先进先出，断线用 previous_response_id 恢复 | 会话 id 续接 | 不在职责范围 | 不在职责范围 | SR-01 / SR-05 / SR-10 / SR-13 |
| 按会话加锁 / 并发控制 | 文档未明确内部锁实现 | thread_id 为持久游标，同线程状态由 checkpointer 串行化 | 同一 stream_id 的请求先进先出且不重叠，不同通道并发 | 文档未明确内部锁实现 | virtual key 做 rpm/tpm 并发限流 | 不在职责范围 | SR-05 / SR-10 / SR-25 |
| 事务 Outbox 与幂等消费 | 文档未公开该内部实现 | 检查点写入避免任务重复执行 | 文档未公开该内部实现 | 文档未公开该内部实现 | spend logging 异步且幂等记账 | 异步摄取与自动批处理 | SR-06 / SR-25 / SR-27；模式见 SR-15 / SR-16 |
| 行锁 claim 与 in-flight | 运行态有 running、paused、stopped、suspended 状态机 | 文档未公开行锁实现 | 文档未公开 | 文档未公开 | 文档未公开 | 文档未公开 | SR-03 状态机；行锁范式见 SR-17 / SR-18 |
| 事件溯源 | 节点事件流可重放 | checkpoint 序列近似事件日志 | Items 序列作为上下文 | TracePart 事件序列 | 请求事件回调 | trace 与 observation 序列 | SR-01 / SR-04 / SR-11 / SR-13 / SR-24；范式见 SR-19 / SR-20 |
| 非 mock 集成测试 | 文档未公开 | 文档未公开 | 文档未公开 | 文档未公开 | 文档未公开 | datasets 与 experiments 可跑真实输入 | 工具范式见 SR-21 / SR-22 / SR-23 |
| LLM 录制回放桩 | 文档未公开 | 文档未公开 | 文档未公开 | 文档未公开 | 文档未公开 | 生产 trace 转测试用例 | 实锤见 SR-23（USE_VCR 回放录制响应） |
| Gate 与评测分级 | 运行有终态枚举 | 文档未公开 | 官方区分 paused 与失败两类非快乐路径 | FailureTrace 标注失败原因 | 预算与限流作为准入 | LLM-as-a-judge、人工评审、评分配置 | SR-03 / SR-09 / SR-13 / SR-25 / SR-24 |
| 统一路由与配额成本 | 应用参数与模型配置 | 不在职责范围 | 单 API 多内置工具 | 单 API 多基础模型 | virtual key、四级限流、router、spend 记账 | cost 与 latency 看板 | SR-02 / SR-11 / SR-25 / SR-24 |
| 审计哈希链 | 文档未公开 | 文档未公开 | 文档未公开 | trace 可审计但无哈希链 | 管理员变更审计日志（Enterprise） | 全量 trace 与导出 | 哈希链范式见 SR-31 / SR-32 / SR-33 |
| OTel 全链路 trace | 文档未公开 | 文档未公开 | 文档未公开 | 私有 TracePart 格式 | 回调对接 Langfuse、MLflow | OTel 原生、支持 Java 语言栈 | SR-13 / SR-25 / SR-24；标准见 SR-28 / SR-29 / SR-30 |
| 架构规则自动测试 | 不在调研范围 | 不在调研范围 | 不在调研范围 | 不在调研范围 | 不在调研范围 | 不在调研范围 | 工具范式见 SR-34 / SR-35 |
| 契约测试与文档单源 | 事件契约表随 API 文档给出 | 检查点序列化契约 | API 事件 Schema | TracePart 字段契约 | OpenAI 兼容契约 | REST API 与 SDK 契约 | 工具范式见 SR-36 / SR-37 |

> **补充事实（工程范式专项，覆盖 Q2 / Q3 / Q5）**：
>
> - **Outbox**：microservices.io 明确要求把消息与业务实体在同一数据库事务内写入 outbox 表，由独立 Message relay 投递；并明确指出 relay 可能在投递后、记录前崩溃而重复发布，因此消息消费者必须幂等（SR-15「Result context」）。两种 relay 实现为事务日志尾随（Postgres WAL）与轮询发布（SR-20）。
> - **幂等消费者**：以 PROCESSED_MESSAGES 表的主键 `(subscriberId, messageID)` 去重，事务起始先插入消息 ID，冲突即回滚并忽略；官方文章指出 at-least-once 下「更新 DB 成功但 ack 前崩溃」必然导致重复执行，Kafka 的 exactly once 仅覆盖 messaging 不覆盖数据库事务（SR-16 / SR-16b）。
> - **行锁 claim**：PostgreSQL 官方邮件列表给出 `SELECT ... FOR UPDATE SKIP LOCKED` 让多 worker 各取不同行而不互斥阻塞；另一封明确「先把行改为 in-flight 并提交，再做非幂等的外部工作」，崩溃后需清理者回收（SR-17 / SR-18）。这直接对应 Tioka 34 号计划的 orch_timer 扫描派发与 claim API。
> - **事件溯源**：Fowler 把「用事件日志作为系统主记录」列为事件驱动四种模式之一；Memory Image 一文提醒事件日志需用通用数据结构序列化并可与模型解耦以支持长期演化（SR-19 / SR-22）。
> - **非 mock 验收**：Testcontainers 明确提出「不用 mock 或内存替代品」，指出用 H2 代替生产 Postgres 会掩盖特性与语法差异，并提供 wait strategies、随机端口、Ryuk 自动清理（SR-21 / SR-22 / SR-23）。OTel demo 的 Agent Service 默认「replays recorded LLM responses」，通过 USE_VCR 开关切换真实模型，是录制回放桩的公开实锤（SR-23）。
> - **一致性守护**：ArchUnit 用纯 Java 单元测试检查包依赖、分层、环、切片与 PlantUML 组件图，支持 Freezing Arch Rules 冻结既有违规（SR-34 / SR-35）；Spring Cloud Contract 把契约放独立仓库、由 producer 生成测试与 stub jar，破坏性契约变更走 PR 与 CODEOWNERS 式审批（SR-36），与 Tioka W0-17 契约冻结「破坏性变更须新版本字段或并行 schema」同构（D5，§w0-contract-freeze）。

### 2.4 自检留痕（自检点 2）

完成 §2.1 标杆清单后按协议 §2.1 判定、§2.3 反向验证 3 问；完整判定与证据见附录 C.2，结论：**未命中触发标准，不发起中间确认**。主因是主理人已把名单裁决权与「按证据可得性决定」的取舍标准明确授予本角色，上游对该决策点已做出选择。

---

## 3. 对比：对比矩阵与加权评分

> 四段式「对比」段。在 §2 事实基础上建立加权矩阵并打分；打分对象是「该标杆的工程范式对 Tioka 自研链路的借鉴适用度」，不是采购或引入评估。

### 3.1 对比矩阵

**权重口径**：沿用模板 §3.1 给定的默认权重（0.30 / 0.20 / 0.15 / 0.15 / 0.20），权重之和 = **1.00**。本角色不重设权重，逐行给出在本项目语境下的权重理由。

| 评估维度 | 权重 | 权重理由 | B1 得分 | B2 得分 | B3 得分 | B4 得分 | B5 得分 | B6 得分 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 场景契合度 | 0.30 | 本轮诉求是「一致性校验加后续链路规划」，与 G1 登记主链路缺口的直接对应度是首要判据 | 5 | 5 | 4 | 4 | 4 | 4 |
| 技术成熟度 | 0.20 | 借鉴范式需有可核验的官方文档与生产验证，避免把实验特性写进设计基线 | 5 | 5 | 5 | 5 | 5 | 5 |
| 集成难度（反向） | 0.15 | 得分越高代表借鉴代价越低：范式能否在 Java 17 与 Spring Boot 3.2.12 栈上自研复刻，而不引入外部运行时 | 4 | 4 | 3 | 2 | 4 | 3 |
| 成本（反向） | 0.15 | 得分越高代表代价越低：优先开源可自托管、无强制采购与长期商务条款 | 5 | 5 | 2 | 2 | 5 | 4 |
| 合规可控性 | 0.20 | 私有化 compose 五服务、数据不出境、S0 硬边界禁止外部执行依赖，是本项目的一票否决项 | 5 | 4 | 2 | 1 | 5 | 5 |
| **加权总分** | **1.00** | 五维权重之和 = 1.00 | **4.85** | **4.65** | **3.35** | **3.00** | **4.55** | **4.25** |

**评分标尺**：每项 1 至 5 分；1 为严重不符合，3 为基本满足但存在明显局限，5 为完美契合。标注「反向」的两个维度得分越高代表该代价越低。加权总分按各维得分乘权重求和，保留两位小数。

**逐家打分依据（均为综合归纳，引用 §2.2 已核实事实）**：

- B1 = 5×0.30 + 5×0.20 + 4×0.15 + 5×0.15 + 5×0.20 = 4.85。SSE 续传、状态快照重放、跨人审单流三项最贴合 Q1；开源可私有化。
- B2 = 5×0.30 + 5×0.20 + 4×0.15 + 5×0.15 + 4×0.20 = 4.65。checkpoint 与 interrupt 直击「单机内存状态」；扣 1 分因其 Platform 为托管、纯借鉴需自研映射。
- B5 = 4×0.30 + 5×0.20 + 4×0.15 + 5×0.15 + 5×0.20 = 4.55。请求生命周期与异步记账覆盖 Q4；场景契合扣 1 分因其不覆盖 Runtime 主链。
- B6 = 4×0.30 + 5×0.20 + 3×0.15 + 4×0.15 + 5×0.20 = 4.25。评测闭环覆盖 Q3 与 Q4；集成难度与成本各扣分因需 ClickHouse、Redis、S3 额外底座。
- B3 = 4×0.30 + 5×0.20 + 3×0.15 + 2×0.15 + 2×0.20 = 3.35。语义定义优秀但闭源托管、按量计费、数据出境，合规与成本显著失分。
- B4 = 4×0.30 + 5×0.20 + 2×0.15 + 2×0.15 + 1×0.20 = 3.00。编排与 trace 语义完整，但云托管执行依赖触碰 S0 硬边界，合规可控性仅 1 分。

**权重稳健性复核（自检点 3 的量化证据）**：另拟一套「私有化强化权重」做敏感性复算——场景契合 0.28、技术成熟 0.16、集成难度 0.16、成本 0.12、合规可控 0.28，权重之和仍为 1.00。复算结果为 B1 4.84、B2 4.56、B5 4.56、B6 4.28、B3 3.20、B4 2.76。三层结论的分组与默认权重**完全一致**（优先 B1/B2/B5，部分 B6/B3，不借鉴 B4），证明按默认权重打分不会反转推荐排名。

### 3.2 评分结论

- **优先借鉴**：**B1 Dify（4.85）**、**B2 LangGraph（4.65）**、**B5 LiteLLM（4.55）**。三者均为开源可自托管、范式可在 Java 栈自研复刻、合规可控性 4 至 5 分，且分别直击 Q1 主链路、Q1 状态机、Q4 网关配额三处登记缺口。借鉴点是工程范式与事件契约，不是引入运行时。
- **部分借鉴**：**B6 Langfuse（4.25）** 借鉴 OTel GenAI 字段语义、trace 转数据集的评测闭环与成本归集口径，不引入 ClickHouse 底座（集成难度 3 分、成本 4 分）；**B3 OpenAI（3.35）** 仅借鉴 previous_response_id 血缘语义、paused run 与通道先进先出的契约命名，不引入闭源托管服务（合规 2 分、成本 2 分）。
- **不借鉴（否决）**：**B4 Amazon Bedrock Agents（3.00）**。否决理由：云托管执行依赖违反 D3，§S0 1.1 至 1.2「禁止把外部 harness 或云 AgentRun 当执行依赖」，与 D18，§2 私有化 compose 五服务不兼容且涉及数据出境；合规可控性 1 分、成本 2 分、集成难度 2 分为全体最低。仅其 TracePart 字段命名可作为 ADR-072/075 审计事件模型的语义对照，不计入借鉴面。

### 3.3 方案组合分析

单一标杆无法覆盖 Q1 至 Q5 全部需求，建议按「借鉴面组合」理解，而非选型组合。

| 组合方式 | 覆盖哪些能力 | 未覆盖能力 | 组合复杂度 | 总体成本估算 |
| --- | --- | --- | --- | --- |
| B2 状态机语义 加 B1 事件续传契约 加 B5 网关职责划分 | Q1 异步 Turn、审批挂起恢复、SSE 续传；Q4 路由配额成本 | Q2 异步骨干、Q3 验收、Q5 一致性守护 | 中 | 纯设计借鉴，无采购成本；自研工作量落在 Runtime 与 Model Hub 两域 |
| 上一组合 加 工程范式专项（Outbox、幂等、SKIP LOCKED、Testcontainers、ArchUnit、CDC、OTel GenAI、PG 哈希链） | Q1 至 Q5 全覆盖 | 真实模型与飞书租户运行时证据（对应 V2） | 中高 | 需新增测试期依赖与迁移，但不改生产 compose 五服务 |
| 引入 B3 或 B4 托管执行面 | 编排与 trace 语义 | 私有化合规、数据不出境、S0 硬边界 | 高 | 合规不可控，已由 §3.2 否决 |

### 3.4 自检留痕（自检点 3）

设定权重前按协议 §2.1 判定、§2.3 反向验证 3 问；完整判定与证据见附录 C.3，结论：**未命中触发标准，不发起中间确认**。主因是本角色沿用模板已给定的默认权重（上游已选择），且 §3.1 敏感性复算证明排名不反转。

---

## 4. 建议：取舍决策支持

> 四段式「建议」段。**本节全部内容为建议而非裁决**，最终业务边界由 `business-architect` 在 G3 冻结并逐 Gate 审核；本节不替代其判断。

### 4.1 自研 / 采购 / 复用边界建议

| 能力项 | 建议方式 | 建议依据 | 候选方案 / 系统 | 关键前提 |
| --- | --- | --- | --- | --- |
| Runtime 异步 Turn 状态机、checkpoint、审批挂起恢复 | 自研 | B2 得分 4.65、B1 得分 4.85；S0 硬边界允许自研全部运行时并禁止外部执行依赖 | 借鉴 LangGraph checkpointer 与 interrupt 语义 | 状态落 Tioka 现有 PostgreSQL，按 thread_id 加行锁；先确认 V101 与 V104 迁移基线 |
| SSE 事件续传与 Last-Event-ID | 自研 | B1 streaming 的重连与状态快照范式；MDN 的 id 与 retry 字段语义（SR-14） | 借鉴 workflow_run_id 等价物与状态快照重放 | 事件表 id 单调递增且持久化；与 w0-contract-freeze 的 Part Schema v0 和 RuntimeEventType 对齐 |
| MQ 生产消费闭环、事务 Outbox、幂等消费、行锁 claim | 自研 加 复用已有底座 | X13 登记闭环未证实；SR-15 至 SR-18 给出成熟约束；D14，§3 已有 EventPublisher、DomainEvent、IdempotentConsumer | RocketMQ 与 PostgreSQL（ADR-008 已冻结） | 先按 V5 收敛 41 份 migration 与 45 份 contracts/db SQL 的双份副本，再新增迁移；幂等键用事件 ID |
| 模型网关统一路由、配额与成本归集 | 自研（借鉴范式） | B5 得分 4.55，请求生命周期与异步记账划分清晰 | 借鉴 LiteLLM virtual key、四级限流、router 回退与 spend logging | 记账走异步后台，不进请求路径；与 D4，§ADR-017 模型网关配额审计对齐 |
| 审计哈希链与全链路 trace | 自研（对齐开放标准） | SR-31「用 Aurora PostgreSQL 替代 QLDB 做审计」给出 append-only 加哈希链在 PG 上的可行路径；OTel GenAI 语义约定避免厂商锁定（SR-30） | PostgreSQL append-only 审计表 加 digest 锚定；gen_ai 字段约定 | digest 异步批量锚定并定期外存；字段对齐 ADR-072/073/075 与 D14 的 trace 包 |
| 非 mock 验收基座 | 复用 加 引入测试期开源件 | SR-21 至 SR-23 指出 H2 与 mock 会掩盖生产差异；G1 登记默认 H2 加 mock；V7 绿灯口径 | Testcontainers、录制回放桩（CI 契约回归）、ArchUnit、Spring Cloud Contract | 仅 test scope 或 CI 旁路，不改生产 compose 五服务；真实模型验收单列，不并入自动化绿灯 |
| 外部云托管 Agent 运行时 | 不采购 | B4 得分 3.00 已否决；D3，§S0 1.1 至 1.2 硬边界禁止外部执行依赖 | 无 | 由 G3 复核该边界，本角色不冻结 |
| 文档单源与进度自动生成 | 复用已有底座 | ENG-07 已定义 docs/architecture 唯一权威与 publish、gen-progress 两脚本（D2，§README 1；D10，§9） | 现有 scripts 与 CODEOWNERS 机制 | 先修 X3 与 X4 的飞书镜像漂移与行数不一致，再谈自动生成 |

### 4.2 MVP 范围建议

对齐用户诉求「审查现有的代码以及设计，进行后续的链路规划与实现」，并沿用 34 号计划「地基先于功能、假实现先下线」四原则（D5，§34 摘要）。

| 功能（对齐用户诉求） | 建议 MVP？ | 理由 |
| --- | --- | --- |
| R1 单机内存状态改为落库并按会话加锁 | 建议纳入 | B2 PostgresSaver 语义成熟，直击 D7，§01 登记的「Runtime 不能水平扩展」；依赖现有 PostgreSQL |
| R2 六个假实现 API 下线或替换 | 建议纳入 | 34 号四原则明确「假实现先下线」；V6 状态待按 D7，§01 表名逐类检索确认，本报告不假设其已下线 |
| R3 MQ 生产消费闭环加 Outbox 幂等 | 建议纳入 | D3，§S0 5 规定生命周期走 MQ；X13 登记闭环需按域实测；SR-15 至 SR-18 提供可复刻约束 |
| R4 SSE 事件续传与审批挂起恢复 | 建议纳入（分两步） | 第一步做事件 id 与状态快照重放，第二步做跨多次人审不新开 Turn；对应 SR-01 与 SR-09 |
| R5 双实例 HA 与真实模型夜间连跑 3 天 | 不建议并入首个 MVP | 依赖 U-01 与 U-02 的仓库外取证；34 号把「夜间真实模型连跑 3 天」列为 G1 判定条件（D5，§34 2） |
| R6 审计哈希链与全量 OTel trace | 建议 MVP 仅做字段语义与 traceId 贯通 | 哈希链涉及 digest 锚定与迁移，排期依赖 D-03；字段语义可先行对齐 ADR-072/073/075 |
| R7 新增 ontology、governance、eval 三模块的密度对齐 | 不建议并入 MVP | X8 模块树漂移需先由下游判定属于「漂移」还是「ADR 补充未回写模块树」，优先级低于地基 |

### 4.3 技术栈参考建议

| 技术层 | 推荐方案 | 替代方案 | 选择理由 |
| --- | --- | --- | --- |
| 状态持久化 | 现有 PostgreSQL pgvector 加自建 checkpoint 表 | Redis 缓存态（明确不推荐作持久化） | B2 官方指出内存 saver 重启即丢失，生产须用 PostgresSaver；同源 PG 已在 D18 compose 五服务内 |
| 会话锁与任务 claim | 数据库行级锁加 thread_id 唯一约束，配合 FOR UPDATE SKIP LOCKED | Redis 分布式锁 | SR-17 与 SR-18 证明 PG 原语可同时服务 Outbox 与 claim，减少中间件与一致性面 |
| 异步骨干 | RocketMQ 加 outbox 表 加 IdempotentConsumer（复用 framework/messaging） | Kafka Adapter（ADR-008 允许客户替换） | ADR-008 已冻结 RocketMQ 5.x；D14，§3 已有三个 messaging 类 |
| 事件契约 | 现有 contracts/events 39 份 JSON Schema 与 mq-envelope-v1 | 补 Spring Cloud Contract 做 CDC | 与 w0-contract-freeze「破坏性变更须并行 schema」及 D11 契约测试同构 |
| 集成测试 | Testcontainers（Java 原生）加录制回放桩做 CI 契约回归 | H2 in-memory（明确不推荐） | SR-22 与 SR-23 指出 H2 掩盖生产差异；G1 登记默认 H2 加 mock 是待收口差距 |
| 架构守护 | ArchUnit 的 layer、cycle、slice 与 freezing 规则 | Checkstyle 规则补充 | D2，§44-EA 2 R1 至 R6 与 W0-09 已规划 ArchUnit 落地，可直接覆盖 X8 模块树漂移 |
| 可观测字段 | OpenTelemetry GenAI 语义约定的 gen_ai 字段 | 厂商私有 trace 格式 | SR-30 明确标准化是为避免框架与厂商锁定；B4 私有 TracePart 不宜照搬 |
| 模型网关 | 自研 ModelGateway，借鉴 LiteLLM 请求生命周期 | 引入 LiteLLM Proxy 旁路 | S0 硬边界与 Java 栈一致性；引入会改变 compose 五服务并新增 PG 与 Redis |

### 4.4 建议定位声明

本节所有「建议方式」「建议 MVP」「推荐方案」均为 `research-analyst` 的**评估性建议**，不构成对下游的已冻结输入。特别是 §4.1 的自研/采购/复用边界，须由 `business-architect` 结合 X1、X8、X9、X13 的裁决结果与 G3 Gate 审核后冻结；本角色不替其做边界裁决，也不进入系统设计、部署或安全设计。

---

## 5. 风险与待确认项

> 四段式「风险」段。列出调研发现的主要风险、外部信息不可得的待确认项，以及需下游持续关注的依赖项。

### 5.1 主要风险清单

| 编号 | 风险描述 | 触发条件 | 影响范围 | 严重程度 | 缓解建议 |
| --- | --- | --- | --- | --- | --- |
| R-01 | 标杆范式与 Tioka Java 17 栈发生语义漂移：B1/B2/B5/B6 均以 Python 为主，checkpoint、interrupt、virtual key 等概念直接照搬易与 Spring 事务边界和 RocketMQ 语义错位 | 直接移植概念名或引入其运行时到 biz 层 | Runtime 状态机、模型网关契约、G3 一致性判定 | 高 | 只抽取状态机、事件契约与字段语义，用 Java 原语重写；以 ArchUnit 加契约测试固定边界；对照 44-EA R1 至 R6 |
| R-02 | 云托管标杆触碰 S0 硬边界：B4 与 B3 的执行依赖若被误采纳为 Runtime 底座，违反 D3，§S0 1.1 至 1.2 并触发数据出境 | G3 冻结边界时把云托管当候选底座 | 部署形态、合规底线、私有化交付 | 高 | §3.2 已将 B4 列为不借鉴；§4.1 全部自研加复用已有底座；§5.3 列为 D-02 交 business-architect 复核 |
| R-03 | 审计哈希链的性能与密钥管理代价：把 QLDB journal 的 append-only 加逐行哈希范式迁到 PostgreSQL，若同步写入高 QPS 模型调用链会拖慢主链路 | 把哈希链强同步写进请求路径 | 模型网关延迟、审计完整性 | 中 | 参照 SR-25「no database write sits in the request path」，digest 异步批量锚定并定期外存；先做字段语义后做链 |
| R-04 | 录制回放桩掩盖非 mock 验收缺口：SR-23 的 USE_VCR 回放录制响应能稳定 CI，但会被误当作真实模型验收通过 | 用回放桩绿灯替代真实模型夜间连跑或双实例 HA | Gate 判定可信度、完成度口径 | 高 | 回放桩仅用于 CI 契约回归；真实模型连跑 3 天、双实例 HA、Keycloak 验收单列为 U-01 与 U-02，不并入自动化绿灯；沿用 31 完成度词表与 w1 签字页口径 |
| R-05 | Outbox 与 claim 新增迁移放大双份 SQL 漂移：X7 与 V5 登记 41 份 db/migration 与 45 份 contracts/db 副本并存，X13 登记 MQ 闭环未证实 | 未先对齐两处 SQL 版本就新增 outbox 或 receipt 表 | 数据一致性、迁移可执行性 | 中 | 先按 V5 由数据域逐份 diff 收敛到 db/migration 单源再新增迁移；幂等键统一用事件 ID，claim 用 in-flight 状态加崩溃清理 |
| R-06 | 调研打分被当作已冻结裁决：加权结论是评估而非授权，若下游直接把「优先借鉴 B1/B2/B5」当成采购或引入决定，会越过 G3 人工审核 | business-architect 未复核即冻结边界 | 业务边界、合规底线 | 中 | §4.4 明确全篇为建议；§5.3 列 D 类依赖并注明 G3 关注阶段；G2 审核弹窗单独确认 |
| R-07 | OTel GenAI 语义约定仍处实验状态：SR-29 标注 Status 为 Development，并要求用 OTEL_SEMCONV_STABILITY_OPT_IN 控制版本，直接锁死字段可能面临破坏性变更 | 把实验字段写进已冻结契约 | Runtime SSE 事件 Schema、审计事件模型 | 中 | 字段先做内部适配层，不直接暴露为对外契约；与 w0-contract-freeze 的并行 schema 策略一致 |

### 5.2 待确认项

> 因外部信息不可得而暂不能确认的事实。编号与 G1 冻结的 V1 至 V7 待外部验证点对齐，逐条给备选路径，不隐瞒、不伪造 URL。

| 编号 | 待确认项 | 不确定性说明 | 若无法确认的备选路径 |
| --- | --- | --- | --- |
| U-01 | 真实飞书租户同步、FEISHU_APP_ID 与 FEISHU_APP_SECRET 配置、真实模型夜间 workflow 连跑 3 天结果（V2） | 属仓库外运行时证据，公开检索不可得；34 号把连跑结果列为 G1 判定条件 | 由主理人向用户或运维取证；取证前以录制回放桩做 CI 契约回归，真实连跑结论单列，不并入绿灯 |
| U-02 | Testcontainers 与 Keycloak 非 mock 验收记录、双实例 HA 验证记录是否在仓库内存在（V2 与 V7） | 85 份 Surefire 报告仅能证明自动化信号，不能证明覆盖非 mock 与双实例 | 由主理人协调运维取证；备选是按 SR-21 与 SR-22 在 compose 五服务上补最小验收脚本，由 G3 判定是否覆盖 exit |
| U-03 | 六个假实现 API 是否已按 ENG-03 下线（V6） | 归档快照（2026-11-05）与当前代码未对账 | 由下游按 D7，§01 表名逐类检索代码；本报告 §4.2 只建议「先下线再补真实现」，不假设其状态 |
| U-04 | 330 用例全绿是否覆盖非 mock 路径与双实例场景（V7） | 31 完成度词表与 w1 签字页均声明绿不等于 Exit；X9 需主理人锁定 Gate 定义 | 沿用 G1 已锁定的 X9 口径（34 号 G0–G4/GF 为里程碑、S0 §13 G1–G7 为能力门禁，两套标签不混用），保守记为 slice |
| U-05 | 41 份 db/migration 与 45 份 contracts/db SQL 是否同一版本、哪些已执行（V5 与 X7） | 双份副本未做逐行 diff | 由数据域架构师按版本号逐份对账；新增 outbox 迁移前先做单源收敛 |
| U-06 | 商业 SaaS 的中国区私有化能力与商务报价（B1、B3、B5 Enterprise、B6 Cloud） | 公开资料仅见 MIT 自托管、Cloud 计费与合规认证，未取得可核验的中国区报价与数据驻留条款；design-notes 的六个 Claude Docs 外部链接亦未取回（V1） | 本报告一律按「开源自托管范式借鉴」处理，不引入商务依赖、不写入报价数字；如需采购评估由主理人另行授权外部访问 |

### 5.3 需业务架构持续关注的依赖项

| 编号 | 依赖项 | 说明 | 建议关注阶段 |
| --- | --- | --- | --- |
| D-01 | Outbox、幂等消费、SKIP LOCKED claim 的职责边界 | D14，§3 的 EventPublisher、DomainEvent、IdempotentConsumer 与 Orchestration 的 claim API（D2，§47-GL 4 遗留）、orch_timer 扫描派发如何分工 | 高层架构设计 §5 与 G3 |
| D-02 | 云托管与外部执行依赖的排除边界 | B3 与 B4 在 §3.2 被否决，须在边界冻结时显式确认 D3，§S0 1.1 至 1.2 仍适用 | G3 边界冻结 |
| D-03 | 模型网关配额、成本归集、审计哈希链纳入 M1 还是后移 | 对齐 34 号 M1-03 与 X9 Gate 口径；哈希链涉及 digest 锚定与迁移 | G3 排期与高层架构设计 §3 |
| D-04 | 非 mock 验收的 Gate 分级落点 | slice、fidelity、exit 与 34 号 G0–G4/GF、S0 §13 G1–G7 的映射，按 G1 锁定不混用 | G3 与 G5 |
| D-05 | ArchUnit、契约测试、文档单源的落地顺序 | 对齐 44-EA §2 R1 至 R6、W0-09 与 W0-17 契约冻结；先修 X3 与 X4 镜像漂移 | G3 工程基线 |
| D-06 | 模块树漂移 X8 的定性 | 15 个业务域与 44-EA 十三模块之差，需判定属「漂移」还是「ADR 补充未回写」 | G3 域一致性校验 |

### 5.4 自检留痕（自检点 4）

整理 §5.2 待确认项时按协议 §2.1 判定、§2.3 反向验证 3 问；完整判定与证据见附录 C.4，结论：**未命中触发标准，不发起中间确认**。六个待确认项均属主理人已给出处理方式（如实列出并给备选路径）的外部证据缺口，非方案分歧裁决。

---

## 6. 关键来源目录

> 全部来源均为本轮联网检索取得的公开 URL，已去除跟踪参数保留可核验路径；关键事实在「相关章节或段落」列指定到文档小节。最后访问日期统一为 2026-11-08。

| 编号 | 来源类型 | 标题 / 名称 | URL | 相关章节或段落 | 最后访问日期 |
| --- | --- | --- | --- | --- | --- |
| SR-01 | 官方文档 | Dify Consume Streaming Responses | https://docs.dify.ai/en/api-reference/guides/streaming | Reconnect and Resume、Keep the Connection Alive、Handle Errors Mid-Stream | 2026-11-08 |
| SR-02 | 官方文档 | Dify Agent API | https://docs.dify.ai/en/api-reference/guides/agent | Send Messages and Stream Replies、Two limits end a run early | 2026-11-08 |
| SR-03 | 官方文档 | Dify 运行应用（difyctl） | https://docs.dify.ai/zh/cli/run-apps | 填写人工介入表单、form_token 与 submit run form、include-state-snapshot | 2026-11-08 |
| SR-04 | 官方文档 | LangGraph Persistence | https://docs.langchain.com/oss/python/langgraph/persistence | Checkpointer vs store、Troubleshooting common issues（PostgresSaver 与 InMemorySaver 不持久） | 2026-11-08 |
| SR-05 | 官方文档 | LangGraph Interrupts（Python） | https://docs.langchain.com/oss/python/langgraph/interrupts | Pause using interrupt、Resuming interrupts、thread_id 是持久游标 | 2026-11-08 |
| SR-06 | 官方文档 | LangGraph Functional API overview | https://docs.langchain.com/oss/python/langgraph/functional-api | Entrypoint、Task、Checkpointing、thread 是 durable identity | 2026-11-08 |
| SR-07 | 官方文档 | LangGraph Interrupts（JavaScript） | https://docs.langchain.com/oss/javascript/langgraph/interrupts | Resume with Command、durable checkpointer 要求 | 2026-11-08 |
| SR-08 | 官方文档 | OpenAI Conversation state | https://platform.openai.com/docs/guides/conversation-state | previous_response_id、Data retention for model responses（30 天）、计费说明 | 2026-11-08 |
| SR-09 | 官方文档 | OpenAI Running agents | https://platform.openai.com/docs/guides/agents/running-agents | Stream runs incrementally、Handle pauses and failures deliberately（审批为 paused run） | 2026-11-08 |
| SR-10 | 官方文档 | OpenAI WebSocket Mode | https://platform.openai.com/docs/guides/websocket-mode | Connection behavior and limits、Reconnect and recover、previous_response_not_found | 2026-11-08 |
| SR-11 | 官方文档 | OpenAI Migrate to the Responses API | https://platform.openai.com/docs/guides/responses-vs-chat-completions | About the Responses API（agentic loop）、Messages vs. Items、store 与 ZDR | 2026-11-08 |
| SR-12 | 官方文档 | Amazon Bedrock 代理的工作原理 | https://docs.aws.amazon.com/zh_cn/bedrock/latest/userguide/agents-how.html | 协同运作包含下列步骤、调用代理时的跟踪、会话历史记录 | 2026-11-08 |
| SR-13 | 官方文档 | Bedrock trace events | https://docs.aws.amazon.com/bedrock/latest/userguide/trace-events.html | Structure of the trace（TracePart 字段）、OrchestrationTrace 与 FailureTrace | 2026-11-08 |
| SR-14 | 规范文档 | MDN Using server-sent events | https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events | Fields（id 与 retry）、Event stream format、默认断线自动重连 | 2026-11-08 |
| SR-15 | 模式库 | microservices.io Pattern: Transactional outbox | https://microservices.io/patterns/data/transactional-outbox.html | Solution、Result context（relay 可能重复发布、消费者须幂等） | 2026-11-08 |
| SR-16 | 模式库 | microservices.io Pattern: Idempotent Consumer | https://microservices.io/patterns/communication-style/idempotent-consumer | Solution（PROCESSED_MESSAGES 表主键去重） | 2026-11-08 |
| SR-16b | 技术文章 | Handling duplicate messages using the Idempotent consumer pattern | https://microservices.io/post/microservices/patterns/2020/10/16/idempotent-consumer.html | Why can duplicate messages occur、Kafka exactly once 仅覆盖 messaging | 2026-11-08 |
| SR-17 | 官方邮件列表 | PostgreSQL 队列实践：FOR UPDATE SKIP LOCKED | https://postgresql.org/message-id/CALnU-rMhU4AJfAGOrj_f_x7rgXw48LaihAbQT-ByV67TSr4AEw%40mail.gmail.com | 多 worker 用 SKIP LOCKED 各取不同客户端行的队列实现 | 2026-11-08 |
| SR-18 | 官方邮件列表 | PostgreSQL 队列实践：in-flight 状态与崩溃清理 | https://postgresql.org/message-id/flat/3612e660-add2-6541-5fe9-25fe9bd6d162%40joh.to | status 改为 in-flight 并提交后再做非幂等外部工作、崩溃回收 | 2026-11-08 |
| SR-19 | 权威文章 | Martin Fowler tagged by: event architectures | https://martinfowler.com/tags/event%20architectures.html | The Many Meanings of Event-Driven（Event Sourcing 事件日志作为主记录、CQRS） | 2026-11-08 |
| SR-20 | 模式库 | microservices.io Pattern: Transaction log tailing | https://microservices.io/patterns/data/transaction-log-tailing.html | Solution（Postgres WAL）、Tricky to avoid duplicate publishing | 2026-11-08 |
| SR-21 | 官方文档 | Testcontainers 首页 | https://testcontainers.com/ | Test dependencies as code、No more need for mocks、Modules 与 Use cases | 2026-11-08 |
| SR-22 | 官方文档 | Testcontainers Getting Started | https://testcontainers.com/getting-started | What problems does Testcontainers solve（不用 in-memory 替代）、Benefits（wait strategies、Ryuk 清理） | 2026-11-08 |
| SR-23 | 官方指南 | Testcontainers 入门指南 | https://testcontainers.com/guides/introducing-testcontainers/ | 用 H2 替代 Postgres 的隐患、集成测试价值 | 2026-11-08 |
| SR-23b | 官方文档 | OpenTelemetry Demo Agent Service | https://opentelemetry.io/pt/docs/demo/services/agent | LLM Configuration（默认 replays recorded LLM responses、USE_VCR 开关）、Traces | 2026-11-08 |
| SR-24 | 官方站点 | Langfuse 开源 Agent 评测与可观测平台 | https://langfuse.com/ | The full AI engineering loop、Observability 与 Evaluation、Self-host at scale、MIT license | 2026-11-08 |
| SR-25 | 官方文档 | LiteLLM Life of a Request | https://docs.litellm.ai/docs/proxy/architecture | High Level architecture、Request Flow、The router、FAQ（异步记账、no DB write in request path） | 2026-11-08 |
| SR-26 | 官方文档 | LiteLLM Proxy CLI Quick Start | https://docs.litellm.ai/docs/proxy/quick_start | 管理 Unified Interface、Cost tracking、Virtual Keys、Load Balancing、压测吞吐 | 2026-11-08 |
| SR-27 | 官方站点 | Langfuse 公开指标与架构说明 | https://langfuse.com/public-metrics-dashboard | Full-stack open-source、Architecture（ClickHouse、Redis、S3）、Async by default、Security and compliance | 2026-11-08 |
| SR-28 | 官方博客 | OpenTelemetry Inside the LLM Call: GenAI Observability | https://opentelemetry.io/blog/2026/genai-observability | Explore traces（invoke_agent、chat、execute_tool span）、gen_ai 属性、默认不采集内容 | 2026-11-08 |
| SR-29 | 官方规范 | OTel Semantic Conventions for GenAI agent and framework spans | https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-agent-spans | Status: Development、Spans 与 Attributes、OTEL_SEMCONV_STABILITY_OPT_IN | 2026-11-08 |
| SR-30 | 官方博客 | OpenTelemetry AI Agent Observability 2025 | https://opentelemetry.io/blog/2025/ai-agent-observability | Establishing a standardized semantic convention（避免厂商锁定）、telemetry 作为评测反馈环 | 2026-11-08 |
| SR-31 | 官方博客 | AWS Replace Amazon QLDB with Aurora PostgreSQL for audit use cases | https://aws.amazon.com/blogs/database/replace-amazon-qldb-with-amazon-aurora-postgresql-for-audit-use-cases/ | append-only journal、块用加密哈希链相连、密码学验证、关系库审计替代 | 2026-11-08 |
| SR-32 | 官方产品页 | Amazon QLDB 概览 | https://aws.amazon.com/pt/qldb/ | Immutable and Transparent、Cryptographically Verifiable（SHA-256 digest） | 2026-11-08 |
| SR-33 | 官方博客 | AWS Shinsegae International 用 QLDB 防伪 | https://aws.amazon.com/blogs/database/how-shinsegae-international-enhances-customer-experience-and-prevents-counterfeiting-with-amazon-qldb/ | journal 区块以加密哈希相连、Merkle tree digest 与最小哈希集验证 | 2026-11-08 |
| SR-34 | 官方文档 | ArchUnit Why test your architecture | https://www.archunit.org/motivation | 架构规则进 CI、组件依赖自动测试、规则随架构演进 | 2026-11-08 |
| SR-35 | 官方文档 | ArchUnit User Guide 1.5.0 | https://www.archunit.org/userguide/html/000_Index.html | Package Dependency Checks、Layer Checks、Cycle Checks、Slices、Freezing Arch Rules、PlantUML 作为规则 | 2026-11-08 |
| SR-36 | 官方文档 | Spring Cloud Contract Reference Documentation | https://docs.spring.io/spring-cloud-contract/docs/3.0.0-RC1/reference/htmlsingle | 2.4.2 Consumer Flow、2.4.3 Producer Flow、契约独立仓库与 stub jar | 2026-11-08 |
| SR-37 | 模式库 | microservices.io A pattern language for microservices | https://microservices.io/patterns/ | Consumer-driven contract test、Idempotent Consumer、Event sourcing、Audit logging、Distributed tracing | 2026-11-08 |

**来源覆盖核对**：B1 由 SR-01 至 SR-03 覆盖；B2 由 SR-04 至 SR-07 覆盖；B3 由 SR-08 至 SR-11 覆盖；B4 由 SR-12 与 SR-13 覆盖；B5 由 SR-25 与 SR-26 覆盖；B6 由 SR-24 与 SR-27 覆盖；Q1 另有 SR-14 标准补充；Q2 由 SR-15 至 SR-20 覆盖；Q3 由 SR-21 至 SR-23b 覆盖；Q4 由 SR-28 至 SR-33 覆盖；Q5 由 SR-34 至 SR-37 覆盖。共 38 条，全部为 https 公开 URL。

---

## 7. 硬指标清单

| 章节 | 硬指标项 | 当前状态 | 备注 |
| --- | --- | --- | --- |
| §1 | 调研问题已收敛为 ≥ 3 条可执行问题 | 达标 | 收敛为 Q1 至 Q5 共 5 条，逐条含对象、目标、产出、关联种子 |
| §2.1 | 标杆系统 ≥ 3 家 | 达标 | 6 家（B1 至 B6） |
| §2.1 | 含 ≥ 1 家头部 SaaS 代表 | 达标 | B3 OpenAI Responses API、B4 AWS Bedrock Agents |
| §2.1 | 含 ≥ 1 家开源或自研代表 | 达标 | B1 Dify、B2 LangGraph、B5 LiteLLM、B6 Langfuse |
| §2.2 | 每家标杆有独立详述卡片且覆盖 10 维度 | 达标 | 6 张卡片，各 10 维度并逐行标注置信度 |
| §2.3 | 关键能力横向事实表无遗漏 | 达标 | 14 个能力维度横陈 6 家，另有 Q2/Q3/Q5 工程范式专项补充 |
| §3.1 | 对比矩阵含 5 维度、权重与评分 | 达标 | 权重 0.30/0.20/0.15/0.15/0.20，之和 = 1.00，逐家 1 至 5 分并给出算式 |
| §3.1 | 权重稳健性证据 | 达标 | 另拟一套权重复算，三层分组不变，排名不反转 |
| §3.2 | 评分结论含优先 / 部分 / 不借鉴三层 | 达标 | 优先 B1/B2/B5，部分 B6/B3，不借鉴 B4，均引用得分 |
| §4.1 | 自研/采购/复用边界有明确建议 | 达标 | 8 行能力项，逐条含依据、候选与关键前提 |
| §4.2 | MVP 范围建议与用户诉求对齐 | 达标 | R1 至 R7，沿用 34 号四原则与 G1 差距登记 |
| §4.3 | 技术栈参考建议含推荐与替代 | 达标 | 8 个技术层，逐条给推荐、替代与理由 |
| §4.4 | 明确标注建议而非裁决 | 达标 | 保留 business-architect 的 G3 裁决空间 |
| §5.1 | 主要风险 ≥ 3 条且有缓解建议 | 达标 | R-01 至 R-07 共 7 条，逐条含触发条件、影响、严重度、缓解 |
| §5.2 | 待确认项与备选路径 | 达标 | U-01 至 U-06，与 G1 的 V1 至 V7 对齐 |
| §5.3 | 需下游关注的依赖项 | 达标 | D-01 至 D-06，注明建议关注阶段 |
| §6 | 关键来源可追溯到 URL 与章节 | 达标 | 38 条 https 来源，关键事实指定到文档小节 |
| 全文 | 明确区分事实 / 推断 / 综合归纳 / 建议 / 风险 | 达标 | §2.2 逐行置信度；§4 建议段；§5 风险段 |
| 全文 | 事实 → 对比 → 建议 → 风险 四段式不倒序 | 达标 | §2 / §3 / §4 / §5 顺序固定 |
| 全文 | 无编造来源、无残留占位符或示例前缀 | 达标 | 全部 URL 来自本轮联网检索；定价与商务条款不足处已标推断并列入 U-06 |
| 附录 C | 四次中间确认自检含反向验证 3 问证据 | 达标 | C.1 至 C.4 逐次给出 §2.1 判定与三问量化证据 |

---

## 附录 A：生成流程

### A.1 流程总览

| 步骤 | 动作 | 落入章节 |
| --- | --- | --- |
| Step 0 | 逐字读取上游完整原文 material_digest.md（511 行）、模板、校验脚本与中间确认协议，识别全部占位符与硬指标 | §0 / §7 |
| Step 1 | 从用户诉求与 G1 差距登记提取种子，收敛为 Q1 至 Q5，插入自检点 1 | §1 / C.1 |
| Step 2 | 联网检索六家标杆与工程范式，盘点清单、逐家 10 维度详述、横向事实表，插入自检点 2 | §2 / C.2 |
| Step 3 | 沿用模板默认权重建立 5 维矩阵，逐家打分、稳健性复算、三层结论与组合分析，插入自检点 3 | §3 / C.3 |
| Step 4 | 给出自研/采购/复用、MVP、技术栈三类建议，并声明建议定位 | §4 |
| Step 5 | 整理风险、待确认项与下游依赖项，插入自检点 4（最后一次完整复核） | §5 / C.4 |
| Step 6 | 汇总 38 条可核验来源，逐项核验硬指标与占位符，运行合规校验脚本 | §6 / §7 |

```mermaid
flowchart LR
    S0[读取上游原文与模板] --- S1[收敛调研问题并自检]
    S1 --- S2[联网检索标杆并盘点事实]
    S2 --- S3[五维加权评分与三层结论]
    S3 --- S4[取舍与 MVP 建议]
    S4 --- S5[风险与待确认项复核]
    S5 --- S6[来源汇总与硬指标自检]
```

### A.2 本轮实际执行顺序

先读模板与校验脚本确认占位符正则及 12 项检查矩阵，再逐字读取上游 511 行原文建立项目基线（§0.1），随后按 Q1 至 Q5 分五批并行联网检索官方文档、规范与官方邮件列表，取得 38 条可核验 URL；之后按四段式依次产出事实、对比、建议、风险四段，在每个自检点按公共协议 §2.1 判定并回答 §2.3 反向验证 3 问；最后汇总来源目录、核验硬指标并运行自动校验脚本。

### A.3 调研纪律

1. **不泛化**：Q1 至 Q5 严格沿用主理人给定方向，未引入与 Tioka 主链路无关的行业话题。
2. **不编造**：所有 URL 来自本轮联网检索；厂商私有化报价、中国区数据驻留等无公开资料处标为推断并列入 U-06。
3. **不越权**：未做业务边界冻结、未进入系统设计或部署安全设计、未修改仓库任何源码与既有设计文档、未生成本报告之外的交付物。
4. **事实可分离**：已核实、推断、综合归纳、建议、风险分别标注，推断不写成已核实事实。

---

## 附录 B：置信度口径与来源分级

| 标记 | 定义 | 使用位置 |
| --- | --- | --- |
| 已核实 | 官方文档、规范正文、官方邮件列表或官方博客明确记载的事实 | §2.2 卡片主体、§2.3 事实表、§6 段落定位 |
| 推断 | 由已核实事实结合 Tioka 项目语境（G1 冻结原文）作出的判断，注明来源局限 | §2.2 的定价模式、局限性、参考价值行；§3.1 打分依据；§4 全部建议 |
| 综合归纳 | 两条及以上官方事实的压缩归并，未引入新事实 | §2.2 的目标用户、优势行；§2.3 说明列 |

**来源可信度分级**：一级为厂商或项目官方文档与规范（SR-01 至 SR-14、SR-21 至 SR-29、SR-34 至 SR-36）；二级为权威模式库与官方工程博客（SR-15 至 SR-20、SR-30 至 SR-33、SR-37）；三级为官方邮件列表的工程实践讨论（SR-17、SR-18，用于 claim 语义，已标注为社区讨论而非产品承诺）。全部来源均为一级或二级权威来源，三级来源仅作补充且不单独支撑结论。

**关键数据的来源定位**：330 用例与 0 失败引自 `material_digest.md` D19，§2 与 D5，§progress（G1 冻结）；15 个业务域、41 份迁移、compose 五服务、883 加 258 Java 引自 §0.1 所列 D 编号出处；所有业界能力描述均指定到 §6 的小节级定位。

---

## 附录 C：中间确认自检报告

> 按公共协议 §2.4，在 §1 完成、§2.1 完成、§3.1 设定权重前、§5.2 整理待确认项时各插入一次自检。每次先按 §2.1 判定，再按 §2.3 反向验证 3 问并给出具体证据。本轮四次自检均判定**未命中触发标准**，故未发起中间确认；依据 §2.4 将全部判定与证据随最终回传提交，供 G2 审核弹窗追溯。

### C.1 自检点 1：§1 调研问题收敛完成后

| 项 | 判定与证据 |
| --- | --- |
| §2.1 条件 1（≥2 方案且无法单方裁决） | 不成立。主理人转交的调研目标已给定 Q1 至 Q5 五条具体问题并明确「收敛为 3 至 5 条可执行问题，覆盖下列方向」「必须围绕这些收敛，不得泛化发散」；不存在两种合理理解导致标杆名单显著变化的分歧 |
| §2.1 条件 2（影响下游产出） | 成立（问题清单决定 §2 至 §4 的证据面），但需三条件全部成立才触发 |
| §2.1 条件 3（上游未明确选择） | 不成立。上游 prompt 已对该决策点做出明确选择 |
| §2.1 结论 | 三条件未全部成立，未命中方案分歧型 |
| §2.3 Q1 返工成本 | 返工范围 = §1.1 种子表、§1.2 收敛表两张表，以及 §2.1 清单、§2.2 六张卡片、§3.1 矩阵共三个章节的映射；切换成本 = 按新方向重新检索与改写约 2 至 3 人日，远低于 1 人月，且不涉及源码，返工占比低于本阶段产物 30%。判定可控，未命中 §2.2(1) |
| §2.3 Q2 可感知方 | 调研问题清单是内部工作分解，用户、客户、监管三方均不感知：不改变用户可见功能与交互路径，不涉客户合同 SLA，不改变数据驻留或审计要求。判断依据：用户诉求是「一致性校验加链路规划」，问题清单是达成该产物的内部执行口径，最终产物仍需 G2 审核。判定感知不到，未命中 §2.2(2) |
| §2.3 Q3 与原始诉求一致性 | `material_digest.md` 第 45 行用户诉求原文：「现在这个项目已经有了一部分设计，我现在需要你们进行代码与设计的一致性校验。并且审查现有的代码以及设计，进行后续的链路规划与实现」。Q1 至 Q5 全部服务于「后续的链路规划」所需证据链，未改变产品形态与对外承诺。判定一致，未命中 §2.2(3) |
| 本点结论 | 未命中，不发起中间确认 |

### C.2 自检点 2：§2.1 标杆清单完成后

| 项 | 判定与证据 |
| --- | --- |
| §2.1 条件 1 | 部分成立：候选池有 8 家以上，理论上存在「只选 3 家聚焦 Q1」与「选 6 家覆盖 Q1 至 Q5」两种合理名单 |
| §2.1 条件 2 | 成立：名单决定 §3.1 矩阵列与 §4 的证据来源，影响 business-architect 读到的借鉴面 |
| §2.1 条件 3 | **不成立**。主理人转交原文：「标杆候选可考虑：Dify、LangGraph、OpenAI Agents SDK、Microsoft AutoGen / Semantic Kernel、Coze/扣子、AWS Bedrock Agents、Langfuse、n8n 等；硬指标为 ≥ 3 家且至少含 1 家头部 SaaS 代表 + 1 家开源/自研代表，最终名单由你按证据可得性决定」。上游已把名单裁决权与「按证据可得性」取舍标准明确授予本角色，属协议 §2.1 条件 3 所指「上游已对该决策点做出过明确选择」；依协议 §6，不得就已裁决事项包装成中间确认重复打扰用户 |
| §2.1 结论 | 三条件未全部成立，未命中 |
| §2.3 Q1 返工成本 | 返工范围 = §2.1 清单表一张、§2.2 六张详述卡片、§2.3 横向事实表一张、§3.1 矩阵列共约 3 个章节 9 张表；切换成本 = 重新检索与改写约 2 至 3 人日，无供应商绑定、无长期商务条款、不锁定合规框架，远低于 1 人月。判定可控，未命中 §2.2(1) |
| §2.3 Q2 可感知方 | 名单是调研取样，用户、客户、监管均不感知。判断依据：`material_digest.md` D3，§S0 1.1 至 1.2 已冻结「借鉴任意开源能力清单与协议、禁止把外部 harness 或云 AgentRun 当执行依赖」，且 D18，§2 私有化 compose 五服务不含任何云接入；因此名单内任何标杆都不会作为执行依赖引入，不改变部署形态、客户合同或合规属性。判定感知不到，未命中 §2.2(2) |
| §2.3 Q3 与原始诉求一致性 | 用户诉求原文（第 45 行）未显式提及任何标杆名；名单是达成证据链的手段，不改变产品形态与对外承诺。判定一致，未命中 §2.2(3) |
| 本点结论 | 未命中，不发起中间确认；名单按证据可得性纳入 6 家，覆盖 5 条调研问题并满足全部硬指标 |

### C.3 自检点 3：§3.1 设定权重前

| 项 | 判定与证据 |
| --- | --- |
| §2.1 条件 1 | **不成立**。本角色沿用模板 §3.1 已给定的默认权重（场景契合 0.30、技术成熟 0.20、集成难度 0.15、成本 0.15、合规可控 0.20），**不重设权重**，故不存在「两种权重方案」的抉择；同时模板原文授权「评估维度与权重可根据本次调研问题调整，但必须保留并给出理由」，本角色选择保留上游给定值并逐行写出本项目语境下的权重理由（§3.1） |
| §2.1 条件 2 | 较弱：权重影响的是借鉴侧重的建议，不是接口契约、业务边界、资源选型、合规底线、数据一致性或部署形态等硬约束；协议 §2.1 重要澄清明确「加权打分是评估而非授权」 |
| §2.1 条件 3 | 不成立。模板与主理人 prompt 已给定 5 个维度与默认权重值，上游已做出选择 |
| §2.1 结论 | 三条件未全部成立，未命中 |
| 触发条件专项反证 | 角色高频提示要求「默认权重明显不适用、按默认权重打分会反转推荐排名时」须发起。§3.1 已用「私有化强化权重」（0.28/0.16/0.16/0.12/0.28）复算：B1 4.84、B2 4.56、B5 4.56、B6 4.28、B3 3.20、B4 2.76，三层分组与默认权重完全一致，排名不反转，故该触发条件不成立 |
| §2.3 Q1 返工成本 | 返工范围 = §3.1 矩阵一张、§3.2 结论一节、§4.1 与 §4.3 中引用评分的若干单元格，共约 2 个章节 3 张表；切换成本 = 重算 6 家乘 5 维得分与加权总分约 0.5 人日。判定可控，未命中 §2.2(1) |
| §2.3 Q2 可感知方 | 权重是内部评估口径，用户、客户、监管均不感知：不改变用户可见功能与交互路径，不涉 SLA 与服务范围条款，不改变数据驻留与审计要求。判断依据：§4.4 已明确全篇为建议，最终边界由 business-architect 在 G3 冻结并经 Gate 审核，权重不传导为对外承诺。判定感知不到，未命中 §2.2(2) |
| §2.3 Q3 与原始诉求一致性 | 用户诉求原文（第 45 行）未显式提及评分权重；本决策不改变产品形态与对外承诺。判定一致，未命中 §2.2(3) |
| 本点结论 | 未命中，不发起中间确认 |

### C.4 自检点 4：§5.2 整理待确认项时（最后一次完整复核）

| 项 | 判定与证据 |
| --- | --- |
| §2.1 条件 1 | 不成立。U-01 至 U-06 全部对应 G1 已冻结的 V1 至 V7 与 X 类冲突，属「因外部信息不可得而暂不能确认的事实」，协议与角色定义规定的处理是生成待确认项并给出备选路径，而非在方案间裁决 |
| §2.1 条件 2 | 部分成立（取证结果会影响 G3 判定），但需三条件全部成立 |
| §2.1 条件 3 | 不成立。主理人 prompt 明确「若外部检索确实不可用，须在 §5.2 如实列出待确认项与备选路径，并在回传中说明，不得伪造 URL」，上游已给出处理方式；G1 亦已锁定 X9 Gate 口径，本角色不另起裁决 |
| §2.1 结论 | 三条件未全部成立，未命中 |
| §2.3 Q1 返工成本 | 返工范围 = §5.2 待确认表一张、§4.2 中 R5 一行；切换成本 = 取证后回填约 0.5 人日，无供应商绑定、无合规框架锁定。判定可控，未命中 §2.2(1) |
| §2.3 Q2 可感知方 | 真实飞书租户、真实模型连跑、Keycloak 与双实例 HA 验收属仓库外运行时证据（V2），用户与运维可感知的是「验收是否通过」这一结论；但本角色不判定该结论，仅在报告中列为待确认并交主理人取证。判断依据：`material_digest.md` D5，§31 11 完成度词表与 w1 签字页已冻结「自动化绿不等于 Exit」，本决策不改变用户可见功能、合同或合规属性。判定感知不到，未命中 §2.2(2) |
| §2.3 Q3 与原始诉求一致性 | 用户诉求原文（第 45 行）未显式提及这些仓库外证据；本决策不改变产品形态与对外承诺。判定一致，未命中 §2.2(3) |
| 本点结论 | 未命中，不发起中间确认；未确认事实已如实列入 §5.2 并逐条给出备选路径 |

### C.5 自检汇总与未发起依据

| 自检点 | 时机 | §2.1 判定 | Q1 返工 | Q2 感知 | Q3 一致性 | 是否发起 |
| --- | --- | --- | --- | --- | --- | --- |
| C.1 | §1 收敛后 | 未命中（上游已给定 Q1 至 Q5） | 可控（2 至 3 人日、3 章 2 表） | 感知不到（内部工作分解） | 一致（引用第 45 行原文） | 否 |
| C.2 | §2.1 清单后 | 未命中（上游已授权名单裁决） | 可控（2 至 3 人日、3 章 9 表） | 感知不到（S0 硬边界已冻结） | 一致（诉求未提及标杆名） | 否 |
| C.3 | §3.1 设权重前 | 未命中（沿用模板默认权重） | 可控（0.5 人日、2 章 3 表） | 感知不到（评估结果非承诺） | 一致（诉求未提及权重） | 否 |
| C.4 | §5.2 待确认项 | 未命中（上游已给处理方式） | 可控（0.5 人日、1 表 1 行） | 感知不到（不判定验收结论） | 一致（诉求未提及该证据） | 否 |

**未发起依据（三条）**：其一，本阶段用户唯一输入是 G2 跳过批准弹窗中的「不跳过，启用行业调研」，上游已冻结 G1 编排口径、已给定 Q1 至 Q5、已授权标杆名单由本角色按证据可得性决定、S0 已冻结自研边界，四个自检点均落在协议 §6「禁止把用户原始诉求已经明确的事项包装成中间确认重复打扰用户」的范围内。其二，本阶段产物本质是证据链加建议，全部结论标注为建议且保留 business-architect 的 G3 裁决空间，天然不具备协议 §2.2 的不可逆代价或跨界感知属性；四次反向验证 3 问均无命中。其三，若主理人或 G2 人工审核认为任一论题应升级为阶段内中间确认，本角色可在收到回注后立即按协议 §3.2 的五项必填字段发起，并仅修订命中章节，不推翻已确认事实。

**边界声明（随最终回传提交）**：本角色未做最终业务边界决策、未冻结任何边界、未进入系统设计与部署安全设计、未修改仓库任何源码或既有设计文档、未生成 research_report.md 之外的交付文档。加权打分仅为评估，不是授权。

---

> **Gate 纪律**：本 decision 仅表示本角色交付物完整、证据链闭合、自校验通过，供主理人触发 G2 自动校验与 AskUserQuestion 人工审核；G2 未由主理人宣布通过前，不视为放行，下游不得消费本报告进入 business-architect 或后续阶段。
>
> decision: "调研报告完成，证据链闭合，下游可进入"

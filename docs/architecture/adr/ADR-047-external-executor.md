# ADR-047 — 外部执行器（DSH 式插件执行）

- 状态：Accepted（设计定案；实现在 M4 之后）
- 日期：2026-11-06
- 覆盖工作项：EXR-01〜03（新增，M4 之后）
- 来由：[调研 02](../research/02-Agent执行策略与执行器插件调研.md) 第 2、5 节，选项 J1〜J3；ADR-029 第 66 条
- 相关：[ADR-016](ADR-016-runtime-kernel.md)、[ADR-017](ADR-017-model-gateway-quota-audit.md)、[ADR-020](ADR-020-orchestration.md)、[ADR-021](ADR-021-connector-im-mcp.md)、[ADR-022](ADR-022-chat.md)、[ADR-027](ADR-027-runtime-sandbox-subagent.md)、[ADR-033](ADR-033-reversible-actions.md)、[ADR-034](ADR-034-autonomy-ladder.md)、[ADR-039](ADR-039-delegation-grant.md)、[ADR-046](ADR-046-execution-strategy.md)
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。
- 写法说明：只用文字和表格，不附代码。

## 背景

dsh-superagent 作为 DeepSeek Harness 的插件包，用执行器会话完成每个工单；agent-autoloop 用 Agent 插件把 Claude Code、Codex、ACP 兼容 Agent、任意命令行 Agent 接进来干活。两者的共同做法是：平台只管接什么活、以谁的名义、何时找人、结果怎么回，干活交给可替换的执行器。这与 ADR-020「编排只做调度和记账」同一思路。Tioka 目前只有自研 Runtime 一种执行器。

外部编码 Agent 在读写代码库、跑命令、改文件上比自研循环成熟得多，适合编码类、文档处理类数字员工。代价是轨迹粒度更粗、内置工具只能靠沙箱约束。

## 决定

1. **执行器是 Agent 的运行方式**：ADR-046 `execution` 段的「执行器」取 `NATIVE`（默认）或执行器登记表中的一项。对用户、编排、团队来说它仍是一个 Agent，有版本、ACL、授权书、自治等级。
2. **Tioka 保留全部治理职责**：接单与 Mandate、身份与授予、审批、预算与配额、审计、回执校验、轨迹落库、通知。外部执行器只负责在沙箱里把活干完。
3. **一律在沙箱里运行**（ADR-027）。未启用沙箱的部署不能使用外部执行器。
4. **模型调用回到 Tioka**：外部 Agent 的模型地址指向 Tioka 的「模型直通」接口，用绑定本 thread 授予的短期令牌；外部 Agent 拿不到供应商密钥。
5. **平台工具经 MCP 提供**：外部 Agent 需要的 Tioka 工具由 Tioka 作为 MCP Server 暴露（ADR-021），令牌即本 thread 的授予，ToolGateway 全管线照常执行。
6. **自治等级上限默认 L2**：外部 Agent 的内置工具不经 ADR-033 预演与撤销，其副作用只能落在工作盘内；对外系统的写操作只能通过 Tioka MCP 工具完成。
7. **接入顺序**：ACP → 通用命令行 → Codex app-server。

## 1. 执行器登记表

| 字段 | 说明 |
| --- | --- |
| 编码、名称 | |
| 类型 | `ACP`、`COMMAND`、`CODEX_APP_SERVER` |
| 镜像与启动命令 | 沙箱镜像（含 Agent CLI 与依赖）与启动参数；`COMMAND` 类型的提示词作为单个参数传入，不经 shell |
| 协议版本 | ACP v1 等 |
| 模型 | 允许使用的模型别名；直通接口的协议（OpenAI 兼容或 Anthropic 兼容） |
| 沙箱规格 | CPU、内存、最长存活、默认网络策略（只放行 Tioka 自身与授予内主机） |
| 平台工具 | 需要经 MCP 暴露给它的 Tioka 工具清单（上限声明，实际以授予为准） |
| 许可与认证 | 第三方 Agent 的许可类型与认证方式（例如 Claude Agent SDK 要求第三方产品使用 API Key 认证） |
| 状态 | 草稿、启用、停用；按 ADR-041 版本化 |

登记与变更需要平台管理员权限，启用前需在沙箱中通过一次冒烟用例。

## 2. 一次执行

1. Turn 开始（来源可以是对话、编排、团队子任务）：签发授予（ADR-039）；为 thread 获取沙箱与工作盘（ADR-027）；签发两个短期令牌（模型直通、MCP），有效期不超过授予有效期，作用域绑定本 thread。
2. 启动执行器进程，注入令牌与地址；`ACP` 与 `CODEX_APP_SERVER` 经 stdio 建立会话，`COMMAND` 直接带提示词运行。
3. 执行期间，执行器事件映射为 item：

| 执行器事件 | Tioka item |
| --- | --- |
| Agent 消息与增量 | `assistant` 文本（增量走 `rt:events`） |
| 命令执行、文件改动 | `tool_call` / `tool_result`（工具名前缀 `exec.`），文件改动带工作盘路径 |
| 经 MCP 调用的 Tioka 工具 | 照常由 ToolGateway 写 `tool_call` / `tool_result` 与审计 |
| 权限请求（ACP 权限请求、Codex 审批请求） | 通用审批（kind=tool），Turn 进入 `WAITING_APPROVAL`；答复后回传执行器 |
| 补充输入请求 | 对话入口：作为助手提问交付用户；编排入口：转为回执 `ask` |

4. 结束：优先使用协议的结束信号；`COMMAND` 类型在最终输出中携带约定格式的回执块（与 ADR-020 回执同一 Schema），格式错时在同一会话要求重写一次。
5. 回收：令牌立即失效；沙箱按 ADR-027 规则回收；工作盘中被引用的文件提升为产物。
6. 取消：Tioka 发协议取消信号，10 秒内未退出则终止沙箱。

等待人工答复期间，`ACP` 与 `CODEX_APP_SERVER` 执行器的会话可以保持（不超过沙箱最长存活），或按协议支持的会话续接在答复后恢复；`COMMAND` 类型不支持中途审批，只能以无交互方式运行。

## 3. 模型直通接口（EXR-02）

- 对外提供 OpenAI 兼容与 Anthropic 兼容的模型调用接口，`model` 写模型别名（ADR-041），只接受本 ADR 签发的短期令牌，不对普通 API Key 开放。
- 每次调用经 ModelGateway：配额、成本、路由、数据标签选模（ADR-040）、审计全部生效；用量记到本 Turn 与本 Agent。
- 与 ADR-022 的 `model = agent:{id}` 接口不同：那个接口调用的是 Tioka Agent，这个接口只做模型调用，不跑工具。

## 4. 安全边界

| 风险 | 控制 |
| --- | --- |
| 外部 Agent 越权访问外部系统 | 沙箱网络只放行 Tioka 自身与授予 `egressHosts`；对业务系统的写只能经 Tioka MCP 工具 |
| 密钥泄露 | 只注入短期令牌；令牌绑定 thread 与授予，回收即失效 |
| 内置工具的副作用不可撤销 | 副作用限定在工作盘；授权书编译时该 Agent 的自治等级上限为 L2（ADR-034） |
| 轨迹不完整 | 执行器类型决定粒度；`COMMAND` 类型只有最终输出与工作盘变更，界面明确标注「粗粒度轨迹」 |
| 供应链 | 执行器镜像由管理员登记并固定摘要；升级走登记表新版本 |

## 5. 适用范围

- 适用：编码类数字员工（对标 dsh-superagent 的仓库维护）、批量文档处理、数据分析。
- 不适用：商业租赁合同审查等需要逐步留痕、逐条引用、结构化产物校验的场景，继续使用 `NATIVE`。

## 6. 工作项

| 编号 | 工作项 | 阶段 | 优先级 | 人日 | 依赖 | 验收 |
| --- | --- | --- | --- | --- | --- | --- |
| EXR-01 | 执行器 SPI、登记表、ACP 执行器、事件映射、审批映射、令牌签发与回收 | M4 之后 | P2 | 后端 10，前端 2 | RT-11、CON-05、EXE-01、MAS-01 | 在沙箱里用 ACP 兼容 Agent 完成一个改代码任务，权限请求在 Tioka 审批台出现并能续跑 |
| EXR-02 | 模型直通接口（OpenAI / Anthropic 兼容，短期令牌） | M4 之后 | P2 | 后端 3 | EXR-01、MH-05 | 外部 Agent 的模型用量计入本 Agent 配额 |
| EXR-03 | `COMMAND` 与 `CODEX_APP_SERVER` 执行器 | M4 之后 | P3 | 后端 7 | EXR-01 | — |

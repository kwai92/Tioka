# ADR-045 — A2A 互通：概念映射与安全边界

- 状态：Accepted（映射与边界定案；实现在 M4 之后）
- 日期：2026-11-06
- 覆盖工作项：A2A-01、A2A-02（新增，M4 之后）
- 来由：[调研 01](../research/01-Agent与多Agent架构调研.md) 第 6 节、选项 F（第 1 步现在定映射，第 2、3 步放 M4 之后）；ADR-029 第 66 条
- 相关：[ADR-016](ADR-016-runtime-kernel.md)、[ADR-020](ADR-020-orchestration.md)、[ADR-021](ADR-021-connector-im-mcp.md)、[ADR-023](ADR-023-agent-skill-assets.md)、[ADR-033](ADR-033-reversible-actions.md)、[ADR-037](ADR-037-structured-outputs.md)、[ADR-038](ADR-038-multi-agent-team.md)〜[ADR-040](ADR-040-dataset-policy-data-label.md)
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。
- 写法说明：只用文字和表格，不附代码。

## 背景

A2A（Agent2Agent）是 Agent 与 Agent 之间的开放协议，2026-03 发布 v1.0（2026-05 有 v1.0.1），归 Linux Foundation；官方 Java SDK 要求 Java 17+。官方对它与 MCP 的定位是：MCP 让单个 Agent 连到工具（纵向），A2A 让 Agent 跨系统互连（横向）。Tioka 目前只有 MCP。

单租户的企业内部部署短期内很少需要跨系统 Agent 互通，所以实现放到 M4 之后。但 M3、M4 要定的契约（AgentSpec、Turn 状态、结构化产物、授予）会决定将来能否平滑接入，所以**映射与安全边界现在定**，后续实现只能按本 ADR 做。

## 决定

1. **只支持 A2A v1.0 及其补丁版本**，不做 0.3 兼容。传输先做 JSON-RPC 与 HTTP+JSON，gRPC 按需。
2. **不新造运行时**。入站 A2A Task 就是一个 Runtime Turn 或编排 Run；出站调用远端 Agent 就是一次工具调用。
3. **远端 Agent 是 Connector 资产**，接入时存 Agent Card 快照，Agent 版本引用快照号，与 ADR-021 对 MCP 的做法一致。
4. **授予不跨系统**。远端 Agent 只拿到发过去的内容；调用远端 Agent 是一次副作用级别为 EXTERNAL 的工具调用。
5. **实现方式**：协议层优先直接使用官方 a2a-java SDK（Java 17+），不引入整套第三方 Agent 框架。

## 1. 概念映射（入站：Tioka 作为 A2A Server）

| A2A | Tioka | 说明 |
| --- | --- | --- |
| Agent Card | 由已发布 AgentSpec 生成 | 名称、描述取 Profile；技能取 Agent 的能力说明与输出契约（ADR-037）；认证方式为 Keycloak 签发的 OAuth（与 Tioka 作为 MCP Server 相同，ADR-021）；可签名 |
| 扩展卡片 | 认证后返回 | 只列调用方有 USE 权限的技能 |
| Task | 对话型 Agent：一个 Turn；编排型目标：一个编排 Run（origin=a2a） | Task ID 与 Turn / Run ID 一一对应 |
| contextId | Runtime thread | 同一 contextId 的后续消息在同一 thread 上开新 Turn |
| submitted | Turn `QUEUED` | |
| working | `RUNNING`、`WAITING_CHILD` | |
| input-required | `WAITING_APPROVAL`（kind=card）或编排回执 `ask` | 工具审批不对外暴露：工具审批仍由 Tioka 审批台处理，对远端呈现为 working |
| auth-required | 代用户令牌缺失 | 与 ADR-021「返回授权链接而不是静默改用应用身份」一致 |
| completed / failed / canceled | 同名终态 | |
| rejected | 编排 Decision 的 IGNORE / STOP / UNAVAILABLE，或授予、授权书、Cedar 拒绝 | 原因写入状态消息，不泄露策略细节 |
| Message / Part | 用户 item | text、data、url 分别映射为文本、结构化输入、文件引用（经出入站扫描） |
| Artifact | ADR-037 结构化产物（`data` item）与 `file_ref` | |
| 流式（SSE） | `rt:events` 流 | |
| 推送通知 | outbox 事件投递到调用方登记的 Webhook | Webhook 地址须在出网白名单内 |
| `tasks/list` | 调用方自己发起的 Task | 按调用方身份过滤 |

入站调用方是一个 Principal：服务账号或经 OAuth 的用户。之后的授权书、Cedar、授予、配额、审计与普通入口完全相同。新增发布形态「A2A」，与 API、MCP 并列，由 Agent 负责人显式开启。

## 2. 概念映射（出站：Tioka 调用远端 Agent）

| 项 | 规则 |
| --- | --- |
| 资产 | 管理员登记远端 Agent：Agent Card 地址、认证方式、凭证引用（ADR-042 凭证管理）、允许的技能；平台拉取 Card 存不可变快照，签名校验通过才可启用 |
| 使用方式 | ① 作为工具：Agent 的 ToolSet 可绑定远端 Agent 的某个技能，暴露为 `a2a.{slug}.{skill}`；② 作为团队角色：ADR-038 的 Role 增加「远端 Agent」来源，规划、产物校验照常，远端返回的 Artifact 必须能映射到声明的产物类型，否则视为不合格产物 |
| 副作用级别 | EXTERNAL（不可撤回），走 ADR-033 管线的策略与审批 |
| 去向许可 | 发出内容按 ADR-040 数据标签检查对外去向许可；CONFIDENTIAL 及以上默认不允许发往远端 Agent，空间可按远端资产逐个放开 |
| 出网 | 经 EgressClient，主机须在授予的 `egressHosts` 内 |
| 长任务 | 远端返回 working 时，调用方 Turn 进入 `WAITING_CHILD`，推送通知或轮询结果唤醒（ADR-027 同一机制） |
| input-required | 远端要求补充输入时，转为本地 Agent 的一次工具结果（含问题）；由本地 Agent 决定答复或发起人审 |
| 不可信 | Agent Card、远端消息与产物一律作为不可信数据进上下文；不能据此扩展工具或权限 |

## 3. 注册与发现

单租户部署下，远端 Agent 的登记表就是注册中心，不引入外部注册中心作为依赖。客户已有 Nacos 等 AI 注册中心时，可在 M4 之后做一个只读同步适配器，把其中的 Agent Card 导入为待审核的远端 Agent 资产。

## 4. 契约约束（现在生效）

为保证将来可接入，M3、M4 的相关实现须遵守：

1. Turn 与编排 Run 的状态集不得少于第 1 节映射所需的状态（现有设计已满足）。
2. Agent 的能力说明与输出契约必须结构化存放在 AgentSpec（ADR-023、ADR-037 已满足），不能只写在提示词里。
3. ADR-038 的 Role 结构保留「来源类型」字段，取值先只有已发布专家与临时模板，为远端 Agent 预留。

## 5. 工作项

| 编号 | 工作项 | 阶段 | 优先级 | 人日 | 依赖 | 验收 |
| --- | --- | --- | --- | --- | --- | --- |
| A2A-01 | 出站：远端 Agent 资产、Card 快照与签名校验、作为工具调用、去向许可、长任务唤醒 | M4 之后 | P2 | 后端 5 | CON-05、DAT-02、RT-09 | CONFIDENTIAL 内容不能发往未放开的远端 Agent |
| A2A-02 | 入站：A2A 发布形态、Card 生成、Task 与 Turn 映射、SSE 与推送；作为团队角色 | M4 之后 | P2 | 后端 6，前端 2 | A2A-01、MAS-04 | 外部 A2A 客户端经 OAuth 调用已发布 Agent，状态流转与映射表一致 |

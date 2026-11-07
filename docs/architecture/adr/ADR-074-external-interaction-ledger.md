# ADR-074 — 外部交互台账：入站与出站统一记录

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-07
- 覆盖工作项：OBS-04a、OBS-07（新增）；修订 CON-01、CON-03、CON-07a、NTF-01、KNW（ADR-057 外部知识库）、PRS（ADR-061 外部引擎）、FND-07（出网守卫）
- 来由：[调研 09](../research/09-审计与可观测缺口分析.md) 第 3 节 D4、D9，选项 B2；ADR-029 第 75 条
- 相关：[ADR-021](ADR-021-connector-im-mcp.md)、[ADR-033](ADR-033-reversible-actions.md)、[ADR-036](ADR-036-business-ontology-domain-pack.md)、[ADR-040](ADR-040-dataset-policy-data-label.md)、[ADR-045](ADR-045-a2a-interop.md)、[ADR-057](ADR-057-external-knowledge-providers.md)、[ADR-061](ADR-061-parse-engine-spi-external-ocr.md)、[ADR-072](ADR-072-audit-event-model-coverage.md)、[ADR-073](ADR-073-correlation-trace-propagation.md)、[M3-03](../construction/M3-03-业务本体与文档层.md) 第 15 节
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。
- 写法说明：只用文字和表格，不附代码。

## 背景

Tioka 与外部的往来很多：飞书回调与消息、业务系统（租赁业务系统）事件与写回、通知（飞书、邮件、短信、钉钉、企业微信）、外部知识库（飞书、腾讯乐享）、外部 OCR 与解析引擎、云端模型、MCP 远端服务、A2A、Webhook、数据源同步。各 ADR 都写了「记审计」，但字段不统一，允许的出网请求不留记录，入站回调在审计覆盖测试中被豁免。出现「业务系统说没收到」「客户问合同发给了哪家模型」时无从回答。

## 决定

### 1. 一张台账 `ext_interaction`

| 字段组 | 字段 | 说明 |
| --- | --- | --- |
| 标识 | `id`、`ts`、`direction`（IN / OUT）、`kind` | `kind` 见第 2 节 |
| 对方 | `counterparty`（连接器或渠道实例 ID）、`endpoint`（主机 + 路径模板，不含查询串）、`operation`（如 `contract.pushReviewResult`、`im.message.send`） | — |
| 身份 | `auth_mode`（DELEGATED / SERVICE / SIGNED / NONE）、`credential_id`（凭证 ID，不存值）、`actor_id`、`run_as` | 用谁的身份 |
| 关联 | `trace_id`、`request_id`、`subject`、`turn_id`、`run_id`、`change_set_id`、`tool_call_id` | ADR-073 |
| 幂等与回执 | `idempotency_key`（我方）、`external_ref`（对方回执号：飞书 `message_id`、邮件 `Message-ID`、业务系统单号、外部任务 ID）、`dedupe_key`（入站去重键） | 纠纷时最关键的字段 |
| 内容 | `request_digest`、`response_digest`（SHA-256）、`request_size`、`response_size`、`payload_ref`（可选，对象存储引用）、`data_label` | 缺省只存哈希与大小；按连接器配置保存载荷（第 3 节） |
| 结果 | `status`（OK / ERROR / TIMEOUT / REJECTED / DUPLICATE）、`http_status`、`error_code`、`attempt`、`latency_ms` | 每次重试一条，`attempt` 递增 |

- 台账只增；纳入 ADR-072 的哈希链与保留类别（带 `subject` 的为 `BIZ`，其余 `SEC`）。
- 台账与审计的分工：**台账记「跨边界发生了什么」**，审计记「谁决定做这件事、是否被允许」。工具调用写业务系统时，审计有一条 `tool.invoke`，台账有一条或多条 OUT，二者以 `tool_call_id` 关联。

### 2. 覆盖范围与写入点

| `kind` | 方向 | 写入点 | 里程碑 |
| --- | --- | --- | --- |
| `im` | IN / OUT | 飞书事件回调入口（验签后、去重前写，重复的记 DUPLICATE）；`ImChannelAdapter.send/update` | M2 |
| `webhook` | IN | Flow 发布的 Webhook、业务系统事件入口 | M2 |
| `http` | OUT | `EgressClient`（所有经出网守卫且被允许的请求；拒绝的仍按 ADR-072 记审计） | M2 |
| `notify` | OUT | `NotificationChannel.send/update`；`ntf_delivery` 保留为投递状态机，每次实际发送写台账 | M3 |
| `biz` | IN / OUT | 业务连接器（CON-07a、CON-SDK）入站事件与写回 | M3 |
| `ext_kb` | OUT | 外部知识库检索与同步（ADR-057） | M3 |
| `mcp` | OUT | MCP 远端调用（本地 stdio 不记） | M3 |
| `model` | OUT | **云端模型调用，只针对不在 Turn 内的调用**（入库嵌入、解析视觉模型、护栏分类、记忆抽取、评测裁判）；记文档 ID、页范围、数据标签与 `model_usage` ID，不记内容。Turn 内的调用已由 `model_usage` + 上下文清单覆盖 | M3 |
| `parse` | OUT | 外部 OCR / 解析引擎（ADR-061 的外发审计改为写台账 + 一条审计） | M4（随 PRS 外部引擎） |
| `a2a` | IN / OUT | A2A 入口与外部 Agent 调用（ADR-045） | 随 A2A |
| `datasource` | IN | 数据源同步批次（每批一条，不逐条） | M3 |

`EgressClient` 是唯一出网通道（ADR-021），所以 OUT 方向在 `EgressClient` 一处写即可覆盖大部分；适配器只需补充 `operation`、`external_ref` 与 `subject`。

### 3. 载荷保存

- 缺省只存哈希与大小。
- 连接器或渠道可配置保存载荷（如业务系统写回、通知内容），保存到对象存储，按数据标签加密，保留期随 `subject`；CONFIDENTIAL 载荷的查看需要 `trajectory:read:content` 权限并写审计。
- 合同场景缺省：业务系统写回保存请求与响应；通知保存渲染后的文本（INTERNAL，不含合同原文，见 NTF-01）；外部 OCR 不保存载荷（原文件已在 Tioka）。

### 4. 查询与统计

- 按 `request_id`、`subject`、`counterparty`、`external_ref`、时间段查询；支持「输入对方单号，找到我方全过程」。
- 统计：按对方与 `kind` 的调用量、失败率、P95 时延；按数据标签与对方的「数据去向」报表（哪些标签的数据去了哪些外部系统），供合规检查。

## 后果

- 纠纷时可以拿出带对方回执号与内容哈希的记录；数据去向可统计。
- 代价：M2 台账与 IM、Webhook、出网 4 人日；M3 扩展到通知、业务连接器、外部知识库、MCP、非 Turn 模型调用、数据源 3 人日。
- 写入量：合同场景每份约 20〜40 条（含解析、通知、写回），每天约 10 份，量很小；IM 与出网为主要量，按分区与保留类别管理。

## 未采用的备选

- 各适配器各自写审计（B1）：字段不统一，无法做去向统计。
- 在出网代理层记录（B3）：拿不到身份与关联键，HTTPS 内容不可见。

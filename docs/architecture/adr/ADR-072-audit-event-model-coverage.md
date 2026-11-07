# ADR-072 — 统一审计事件模型：动作目录、必填上下文、写入语义、覆盖门禁、保留与防篡改

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-07
- 覆盖工作项：OBS-01、OBS-02、OBS-05、OBS-09、OBS-10（新增）；修订 GOV-02、GOV-09、CON-01、MAS-01
- 来由：[调研 09](../research/09-审计与可观测缺口分析.md) 第 3、4 节（D3、D5〜D8、D12、D13；I1〜I7），选项 C2；ADR-029 第 75 条
- 相关：[ADR-017](ADR-017-model-gateway-quota-audit.md)、[ADR-025](ADR-025-governance.md)、[ADR-039](ADR-039-delegation-grant.md)、[ADR-041](ADR-041-config-versioning-observability.md)、[ADR-053](ADR-053-foundation-common-services.md)、[ADR-073](ADR-073-correlation-trace-propagation.md)、[ADR-074](ADR-074-external-interaction-ledger.md)、[ADR-075](ADR-075-unified-timeline-evidence.md)
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。
- 写法说明：只用文字和表格，不附代码。

## 背景

ADR-017 定了审计的三个写入来源与同事务语义，ADR-025 定了哈希链与外送，ADR-039 加了委派链字段。但实现（PR #24）暴露出：审计记录的 `trace_id` 全为空；约 55 个写接口挂在覆盖测试的「待补」白名单里；工具调用审计失败被吞；保留清理物理删除记录；客户端 IP 可伪造；定时转发在多实例下重复。根因是设计只说了「要写审计」，没有说**写什么、谁来补上下文、失败怎么办、留多久、怎么证明没被改过**。

## 决定

### 1. 审计事件的字段分三组

| 组 | 字段 | 来源 | 必填 |
| --- | --- | --- | --- |
| 基本 | `id`、`ts`、`action`、`resource_type`、`resource_id`、`outcome`（SUCCESS / DENIED / ERROR）、`reason` | 调用方 | `action`、`outcome` 必填 |
| 主体 | `actor_type`（user / service / agent / system）、`actor_id`、`run_as`（实际权限主体）、`root_principal`、`on_behalf_of`、`chain_json`、`grant_id`、`agent_version_id` | **由 `AuditService` 从当前 Principal 与运行上下文自动补齐**，调用方不传 | `actor_type`、`actor_id` 必填；Agent 执行时 `agent_version_id` 必填 |
| 上下文 | `workspace_id`、`trace_id`、`request_id`、`subject`、`origin`（web / im / api / orch / flow / a2a / system）、`client_ip`、`user_agent`、`turn_id`、`run_id`、`approval_id`、`policy_ids`、`data_label` | **由 `AuditService` 从关联上下文（ADR-073）与请求上下文自动补齐** | `request_id` 必填（系统任务用任务运行 ID）；有 HTTP 请求时 `client_ip` 必填 |
| 内容 | `details`（按动作目录登记的字段）、`before_hash`、`after_hash` | 调用方 | 按目录 |

要点：

- **调用方只管业务字段，上下文一律由写入端补**，这样新加的审计点不会漏 trace 和 IP。调用方显式传入的值优先。
- 委派链字段沿用 ADR-039 的列名，提前在 M2 的迁移里建列（MAS-01 到 M3 再填值）。
- 配置类动作不在审计里存全量前后值，只存 `before_hash` / `after_hash` 与 `cfg_change` 的 ID，详细差异到 ADR-041 的变更记录里看。
- `details` 内的敏感字段（密码、密钥、令牌、证件号）由脱敏器统一处理；合同原文、工具结果正文不进审计，只记长度与哈希。
- `client_ip` 只在请求来自**配置的可信代理网段**时才取 `X-Forwarded-For` 中最右侧的不可信地址，否则取连接地址；原始头另存 `details.xff_raw`（截断）供排查。

### 2. 动作目录（登记制）

- 所有动作名登记在一份动作目录（`docs/contracts/audit/actions.yaml` 一类的契约文件，随 M2 的契约 PR 合入）：动作名、分类、资源类型、`details` 字段与类型、是否敏感、保留类别、SIEM 严重级别、负责模块。
- 命名：`{域}.{对象}.{动词}`，如 `model.provider.update`、`model.credential.rotate`、`mcp.grant.create`、`flow.version.publish`、`auth.login`、`audit.export`。现有 `tool.invoke`、`auth.login` 等保留。
- 分类（用于保留与告警）：`SECURITY`（登录、令牌、权限、密钥、策略、访问拒绝、出网拒绝）、`CONFIG`（发布型与即时型配置）、`DATA`（导出、下载、外发、删除请求）、`EXECUTION`（工具调用、变更单提交与撤销、审批）、`ADMIN`（运维门面、重放、迁移）。
- 写入端在开发与测试环境校验动作是否已登记、`details` 是否符合登记字段，不符合时测试失败；生产只记一条告警指标。

### 3. 必须审计的范围

| 类别 | 必须审计的事件 |
| --- | --- |
| 登录与会话 | 本地登录成功 / 失败 / 锁定、OIDC 登录、登出、刷新、改密、令牌吊销、API Key 创建 / 吊销 / 使用失败、会话被管理员踢出 |
| 身份与权限 | 用户、成员、角色、组、资源 ACL、服务账号、外部身份映射的增删改；权限点变更 |
| 配置 | ADR-041 全部发布型与即时型对象（经 `cfg_change` 同步写一条审计）；Agent、Skill、MCP、Flow、知识库、数据源、路由、Prompt、护栏、Cedar 策略、授权书、编排规则 |
| 密钥 | 凭证创建、轮换、吊销、启停、**解密使用**（按凭证与小时聚合计数，不逐次写）、导出被拒 |
| 数据 | 审计查询与导出、轨迹查看、时间线导出与证据包、知识文档下载、批量导出、个人信息删除请求与执行 |
| 执行 | 工具调用（含拒绝）、变更单提交 / 撤销、审批决定、编排人工干预（取消、重放、暂停）、死信重放 |
| 拒绝 | 401 / 403、策略 DENY / ESCALATE、出网拒绝、护栏 BLOCK、配额拒绝（按主体与分钟聚合） |

### 4. 写入语义

| 场景 | 语义 |
| --- | --- |
| 成功的写操作 | 与业务同事务写入；审计写失败则业务回滚（「业务成功则审计必有」） |
| 拒绝与失败 | 独立事务写入；独立事务也失败时写入本地降级文件（只追加，带序号），恢复后由补录任务回灌，并发 `audit.write.degraded` 指标与告警 |
| 不在事务中的调用（工具调用、外部回调） | 先写审计（PENDING 不需要，直接写结果），失败按上一行的降级处理，**不允许只打日志** |
| 切面捕获范围 | 所有异常（不只是业务异常）都记 ERROR，`reason` 取异常类别与消息摘要 |
| 高频读类事件 | 按主体 + 动作 + 分钟聚合写一条，`details.count` 计数 |

### 5. 覆盖门禁

- 覆盖测试的白名单只允许四类，每条必须写类别与原因：`PUBLIC_AUTH`（自行写 `auth.*`）、`READ_ONLY_POST`（语义只读的 POST）、`NOT_IMPLEMENTED`、`INBOUND_LEDGER`（入站回调，记在外部交互台账，ADR-074）。「待补」不再是合法类别。
- 每个写接口要么挂 `@Audited`（动作名必须在目录中），要么由领域服务手动写并在测试里登记。
- G1 前完成现有约 55 个「待补」接口（OBS-02）；以后新增写接口不挂审计则 CI 失败。

### 6. 审计的审计与查询权限

- 权限点细分：`audit:read`（本空间）、`audit:read:all`（全局）、`audit:export`、`trajectory:read:content`（看轨迹中的工具结果与模型输出正文，缺省只看结构与摘要）。
- 查询、导出、轨迹正文查看、时间线导出都写审计（分类 `DATA`），导出记录条件与行数、文件哈希。
- 查询接口增加 `trace_id`、`request_id`、`subject`、`turn_id`、`run_id` 条件；分页改为按 `(ts, id)` 的游标。

### 7. 保留分级与归档（取代物理删除）

| 保留类别 | 在线 | 归档 | 适用 |
| --- | --- | --- | --- |
| `SEC` | 400 天 | 3 年 | 动作目录分类 `SECURITY`、`ADMIN` |
| `CFG` | 400 天 | 随配置对象生命周期 + 1 年 | `CONFIG` |
| `BIZ` | 随业务主体（如合同）的保留期，缺省 3 年 | 随主体 | 带 `subject` 的 `EXECUTION`、`DATA` 与外部交互 |
| `OPS` | 180 天 | 不归档 | 聚合计数类、高频读 |

- 下限遵守《网络安全法》第二十一条的不少于六个月；以上为缺省值，部署可调大，不能调到 180 天以下。
- `audit_event` 按月分区。**到期处理是「封印 → 导出归档 → 校验 → 删分区」**：先确认该月已封印（GOV-09），把整月数据与封印记录导出为压缩文件写入对象存储（可选 WORM 桶），回读校验哈希后才删除在线分区；删除本身写一条 `audit.partition.archived`。不再按行删除。
- **法律保全**：可对 `subject`、用户、空间或时间段设置保全，保全期间相关记录与分区不归档删除。
- **个人信息删除**：审计里只存用户 ID、不存姓名与联系方式等原文；收到删除请求时，在身份映射表里把该用户 ID 假名化（显示为「已删除用户 #n」），审计行不动，哈希链不受影响。

### 8. 防篡改（GOV-09 修订）

- GOV-09 提前到 M4 前段，优先级 P1。
- 链的范围：`audit_event`、`ext_interaction`（ADR-074）、`run_manifest`（ADR-075）各一条链（无租户维度，第 60 条）。
- 封印任务每分钟一批，计算方式沿用 ADR-025；末尾哈希写入 `gov_audit_seal`，同时外送 SIEM，并每小时写一份到对象存储（外部锚点）。
- 数据库层：应用账号对上述表只有 INSERT 与 SELECT（`forwarded` 标记移到单独的转发进度表），归档用专门的运维账号。
- 校验：提供按时间段重算的接口与每日自动校验，不一致时告警并写 `audit.chain.mismatch`；从备份恢复后自动校验一次。

### 9. 转发与定时任务

- 转发进度用「已转发到的最大 ID」记在进度表，不再逐行更新审计表。
- 所有必须单实例执行的定时任务（审计转发、封印、归档、配额落库与对账、索引重建等）统一用数据库锁互斥（JDBC 锁表，锁超时大于任务最长耗时）；在 ENG 基线登记「必须互斥的任务」清单，新增定时任务评审时必须声明。

## 后果

- 调用方写审计更简单，上下文不会漏；动作名可治理，SIEM 侧能建规则。
- 审计表变为只增，保留由分区归档完成，与第 13 条和 GOV-09 一致。
- 代价：M1 收口 4.5 人日、M2 动作目录与互斥 1.5 人日、M3 审计的审计 1 人日、M4 保留归档 3 人日与 GOV-09 4 人日（见 34 号计划 OBS 节）。

## 未采用的备选

- 审计全部集中到 Foundation：ADR-053 已否决（热路径）。
- 审计写入改为只经 outbox 异步：会失去「业务成功则审计必有」的同事务保证。
- 按行删除到期记录：与只增和哈希链冲突。

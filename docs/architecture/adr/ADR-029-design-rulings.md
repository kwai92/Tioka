# ADR-029 — 设计定案

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：全局
- 阅读版：[Tioka 推进规划与详细设计](https://claude.ai/artifact/V3wtabWCVSwAC3ewNerFNi)
- 约束：本 ADR 优先于 ADR-015〜028；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

审查设计 A–E 后，发现 1 处矛盾和 14 处留白。留白的地方不同开发者会做出不同选择，所以在这里统一定案；实现时以本表为准，改动需走下一节的变更流程。第 1 处矛盾已在设计 B 的表结构里改正。

| # | 涉及 | 问题 | 定案 |
| --- | --- | --- | --- |
| 1 | B、E、ORC | **矛盾**：`runtime_approval` 原先 `turn_id` 必填，但 Flow 人工节点和编排 Attention 没有 Turn | 改为通用审批表：`subject_type` + `subject_id`（turn / flow\_node\_run / orch\_run），`kind` 区分工具审批与卡片。审批提交后发 `approval.decided` 事件，由各 subject 的处理器负责续跑 |
| 2 | B、C | 流式已经输出一半时主模型故障，故障接力会不会产生重复文字 | 首个 delta 发出前可以直接切备用；已发出 delta 后，本步 item 标记作废并发 `item.discarded`，整步用备用模型重跑。前端收到 discarded 即移除该 item |
| 3 | B | 断线续传时 delta 不入库，怎么补 | 断点 item 已完成：从库补完整 item。仍在流式：从 Redis Stream 补该 item 的全部 delta。Stream 保留到 Turn 结束后 10 分钟、上限 1 万条，过期则全部从库补。客户端按 item id 幂等渲染 |
| 4 | B | 会话锁续期失败时 worker 怎么做 | 立即停止该 Turn 的一切写入并放弃，由回收器接管；不尝试重新拿锁继续。所有 Turn 与 item 写入在同一事务内校验 `lease_version` |
| 5 | B | 同一会话正在运行时又来一条消息 | 默认排队（FIFO，最多 5 条，超出返回 429）。想插话用 steer，插话内容在下一次模型调用前作为用户 item 注入 |
| 6 | A、B | 异步任务运行中用户权限被收回 | `run_as` 只存身份，不存权限集。每个 Turn 开始和每次工具调用前重新加载权限（缓存 60 秒）；用户被禁用则进行中的 Turn 在下一步取消 |
| 7 | C | 配额预检要不要估算本次 token | 不估算。预检只看「已用 < 上限」；单次调用允许超出，照常记账，下一次被拒。并发配额以 Turn 计，`WAITING_APPROVAL` 不占并发 |
| 8 | C | 业务回滚时审计会不会一起丢 | 成功操作的审计与业务同事务；DENIED 和 ERROR 审计用独立事务（`REQUIRES_NEW`），不随业务回滚；工具调用审计在调用返回后单独写 |
| 9 | 全局 | ID 格式与列宽不一致（32 与 64 混用） | 新表主键统一 ULID（26 字符，列宽 `VARCHAR(32)`）；引用其他表的外键列统一 `VARCHAR(64)` 以兼容旧 ID；禁止截断 UUID |
| 10 | 全局 | 时间与时区 | 时间列一律 `TIMESTAMPTZ`，按 UTC 存；API 用带时区的 ISO-8601 |
| 11 | E | Flow 的 Agent 节点用哪个 Runtime 会话 | 每个节点运行新建一个 thread（Loop 的每轮也各自新建），thread 元数据记录 runId 与 nodeId；不复用 Chat 会话 |
| 12 | D | 文档切换版本时，引用了旧版本的回答怎么办 | 旧版本分块在切换后 5 分钟再删；之后引用解析失败时显示「来源已更新」，不报错 |
| 13 | 全局 | 删除语义 | 业务实体软删（沿用 `deleted` 列）；`runtime_item`、`model_usage`、`audit_event` 只增不改不删，按保留策略归档 |
| 14 | 全局 | 错误码与 HTTP 状态 | 沿用 `ApiResponse` 与 `ErrorCode`；新增错误码统一在 framework 登记：`UNAUTHENTICATED`→401、`FORBIDDEN`→403、`STALE_REVISION`→409、`QUOTA_EXCEEDED`→429、`LEASE_LOST` 仅内部使用 |
| 15 | 全局 | 事件命名与版本 | Runtime 的 SSE 事件用小写点分（`item.completed`）；跨域 MQ 事件用 `tioka.{domain}.{event}.v1`，payload 带 `schemaVersion`，不兼容变更必须升版本号 |

## 入库时的补充定案（2026-11-05）

ADR 入库与 M0 契约编写时又统一了以下五处，编号续接上表。

| # | 涉及 | 问题 | 定案 |
| --- | --- | --- | --- |
| 16 | ADR-016、ADR-027 | 子 Agent 与后台 Job 等待期间 Turn 处于什么状态 | Turn 状态机增加 `WAITING_CHILD`，与 `WAITING_APPROVAL` 一样释放会话锁和 worker、不占并发配额；子 Turn 或 Job 结束时由事件唤醒 |
| 17 | ADR-015〜019 | 早期设计 SQL 中的 `TIMESTAMP` 与第 10 条冲突 | 一律按 `TIMESTAMPTZ` 实现；契约目录 `docs/contracts/db/` 中的迁移脚本已按此改写，以契约脚本为准 |
| 18 | 第 14 条 | 错误码具体取值 | 不新增 `UNAUTHENTICATED`，沿用已有 `UNAUTHORIZED(40100)` 表示 401。新增 `STALE_REVISION(40901)`→409、`QUOTA_EXCEEDED(42901)`→429、`NOT_IMPLEMENTED(50100)`→501（M0 下线的假实现 API 使用）、`LEASE_LOST(50001)` 仅内部使用、不对外返回。HTTP 状态登记在 `com.tioka.framework.error.ErrorCode` 的 `httpStatus` 字段，异常处理器不再自行映射。现有配额超限改用 `QUOTA_EXCEEDED`，`RATE_LIMITED` 只用于限流 |
| 19 | ADR-009、ADR-015 | ADR-009 第 3 条「不引入 tenantId」与 ADR-015 冲突 | ADR-015 取代 ADR-009 第 3 条：业务表加 `tenant_id`。多租户是否为硬需求尚待确认，确认前单租户部署固定 `tenant_id = 'default'`，租户拦截器照常生效 |
| 20 | ADR-009、ADR-015〜019 | 行级审计列名：设计里写的 `created_at` 与 BaseDO 的 `create_at` 不一致 | 沿用 ADR-009 的 BaseDO 列名 `create_at / update_at / create_by / update_by / deleted`；设计文中的 `created_at`、`updated_at` 一律读作 BaseDO 列；新表这些列用 `TIMESTAMPTZ` |

## 待确认事项答复后的定案（2026-11-05）

以下各条来自用户对推进规划「需要你确认」清单的答复，第 22 条取代第 19 条中「尚待确认」的部分。

| # | 涉及 | 问题 | 定案 |
| --- | --- | --- | --- |
| 21 | ADR-016、ADR-017 | 不升级 Java 21，长连接和阻塞式模型调用怎么扛 100 并发会话 | 保持 Java 17。SSE 用异步 `SseEmitter`，不占 Servlet 线程；每个实例只开一个 Redis `XREAD` 多路复用线程，把各会话的事件分发给本机订阅者，不按连接开线程。模型流式调用用 JDK `HttpClient.sendAsync` 加流式 `BodySubscriber`，回调里写 Redis Stream；Turn worker 线程池有界，大小等于本实例的并发 Turn 上限。ArchUnit 禁止在请求线程里同步调模型 |
| 22 | ADR-015 | 多租户是否为硬需求 | 不是。每家企业独立部署，一套部署只有一个租户 `default`。保留 `tenant_id` 列与租户拦截器（成本很低，日后可开），但不做租户开通、租户管理界面和跨租户隔离测试；内置角色 `tenant_admin` 在界面上显示为「系统管理员」 |
| 23 | ADR-018 | 知识库统一向量维度；新增数据源与文档级权限 | 全部知识库统一一种向量维度，可接受。数据源不再指定 Confluence、GitLab 两家，而是做通用适配：`DataSourceConnector` SPI 之上提供通用连接器 SDK 与三类通用连接器（HTTP/REST 拉取、S3/MinIO/NAS 文件、JDBC 表），企业自有系统按 SDK 写插件接入（ADR-026 插件宿主）。`ChangeEvent` 增加可选 `aclPrincipals`，连接器能拿到源权限就带上，检索时与知识库级权限取交集；拿不到则退回知识库级权限 |
| 24 | 推进计划 | 权威计划与人力 | 34 号文档取代 33 号文档的 R0–R6 计划，成为权威计划。工期按 2–3 名后端 + 1 名前端，M0–M4 约 42 周 |
| 25 | ADR-027、ADR-031 | 经验库条目放在上下文的什么位置 | 组装顺序修订为：平台安全指令 → Agent 指令 → always Skill → auto Skill 索引 → 用户记忆 → 经验 → 知识自动检索结果 → 压缩摘要 → 历史 item → 本轮输入。经验最多 3 条、合计不超过 800 token |

## 施工设计深化时的定案（2026-11-05）

以下各条来自 M0〜M2 施工设计（`docs/architecture/construction/`）的编写，更正或补全了 ADR-015〜028 中的留白。

| # | 涉及 | 问题 | 定案 |
| --- | --- | --- | --- |
| 26 | ADR-028 | ADR-028 写「保留现有 React」，实际前端是 Vue 3 + Element Plus + Pinia + vue-flow | 保留 Vue 3 技术栈；服务端数据用 `@tanstack/vue-query`，流状态用每个 thread 一个 Pinia store |
| 27 | ADR-028、34 号计划 | 两处 HCI 编号不一致 | 工作项编号以 34 号计划为准；ADR-028 的工作台表只作功能划分 |
| 28 | ADR-015 | 依赖中有未使用的 sa-token | 认证授权只用 Spring Security 6，删除 sa-token 依赖 |
| 29 | ADR-008、ADR-009 | 本地 profile 用 H2，新迁移脚本用到部分索引、CHECK、`TIMESTAMPTZ`，H2 不兼容 | 删除 H2 与 `schema.sql`，数据库结构只由 Flyway 管理；本地开发用 `deploy/compose.yml`，测试用 Testcontainers（PostgreSQL 16 + pgvector、Redis 7）；新增工作项 ENG-11 |
| 30 | ADR-016、34 号计划 | M0 要求双实例流式与跨实例取消，但异步派发 RT-06 在 M1 | M0 的 Turn 在接收实例的有界线程池执行，事件、取消、插话全部经 Redis，因此双实例订阅与取消在 M0 成立；M1 RT-06 补 `rt:turns` 跨实例派发与崩溃续跑。M0 的回收器只把过期 Turn 标为 `FAILED(LEASE_LOST)` |
| 31 | ADR-015、第 9 条 | `auth_user.id` 是 BIGINT，与字符串 ID 约定不一致 | `auth_user.id` 保持 BIGINT，新用户取序列 `auth_user_id_seq`；`Principal.userId` 及所有引用列存十进制字符串；不使用 `usr_` 前缀 |
| 32 | ADR-015、ADR-028 | ADR-028 的前端 PKCE 与 ADR-015 的后端令牌两套做法 | OIDC 授权码 + PKCE 由后端完成，登录后签发 Tioka 自己的不透明令牌（与本地登录同一套，访问 8 小时、刷新 7 天）；访问令牌只在前端内存，刷新令牌放 `HttpOnly; SameSite=Strict; Secure` Cookie；URL 中不出现令牌；SSE 用 fetch 读流并以请求头传令牌，服务端不接受查询参数令牌。另提供 JWT 资源服务器路径供持有 IdP 令牌的系统调用 |
| 33 | ADR-016、V101 | V101 直接删除 `parts_json`，历史会话会丢 | V101 不删列；`V104__BackfillRuntimeItems`（Java 迁移）把旧 parts 转为 item，`V105` 再删 `parts_json`、`cancelled`、`steer_text` 与 `runtime_idem`。V101 尚未在任何环境执行，允许修改 |
| 34 | ADR-016、第 3 条 | 事件 ID 规则不完整 | `item.created` 为 `{seq}.0`，`item.delta` 为 `{seq}.{i}`（i 从 1 起），`item.completed`、`item.discarded` 为 `{seq}`；比较时先比 seq，同 seq 带小数的在前；`turn.*` 事件不带 ID，重连时服务端先补发当前 Turn 状态。Redis 键统一为 `rt:events:{threadId}`、`rt:turns`、`rt:lock:{threadId}`、`rt:signal:{turnId}:cancel|steer` |
| 35 | ADR-019、ADR-020 | 重建 `orch_run`、`flow_run` 等表时旧数据如何处理 | 仓库无生产数据：`orch_run`、`orch_attention`、`orch_why`、`orch_idem`、`flow_run` 直接删除重建，不做数据迁移；`orch_rule` 保留为规则头 |

### 修订（2026-11-05，M0 编码前复核）

M0 编码线程开工前复核施工设计，发现以下问题。按 ADR-030 直接修订原定案，不新增编号。

- **第 33 条扩展为「删除旧表和旧列的时机」**：契约迁移只要包含删除表或列、或改写现有数据，就必须和替代旧实现的工作项在同一个 PR 合入，不得提前。V100 中的 `DROP auth_token` 移到 V106（随 FND-02）；V102 中删除 `model_session_freeze`、`cost_event`、`sys_quota` 移到 V120、V121、V122（分别随 MH-01、MH-05、GOV-03）；V130 中删除 `im_outbox` 移到 V142（随 CON-02）；V104、V105 随 M0「旧接口适配与下线」（W4）。V101 放开 `runtime_session.parts_json` 的非空约束，使过渡期新旧内核可以并存。因合入顺序可能与版本号不同，Flyway 开启 `out-of-order`。完整对照表见施工设计 M0-01 ENG-11「迁移随工作项落地」。
- **第 10、20 条补充 DO 层约定**：DO 字段的时间类型沿用现有基类的 `LocalDateTime`，语义为 UTC（JVM `-Duser.timezone=UTC`，JDBC 会话 `TimeZone=UTC`），领域对象与 API 用 `Instant`；DO 中禁止 `OffsetDateTime`。新增两个约定基类：`MutableRecordDO`（四个审计列、无逻辑删除，用于 `runtime_turn` 等有状态机的表）与 `TechnicalRecordDO`（无审计列，只用于列出的技术表），`PersistenceArchTest` 同步放行。表与基类的对照见施工设计 M0-01 ENG-11「DO 基类与时间类型」。

## 可信放权定案（2026-11-05）

2026-11-05 选定差异化方向「可信放权」（D0〜D3），新增 ADR-032〜035 与工作项 TRU-01〜15。以下各条统一这几篇与已有设计的接缝，编号续接上表。

| # | 涉及 | 问题 | 定案 |
| --- | --- | --- | --- |
| 36 | ADR-020、ADR-032 | 「授权」一词已被编排的 `orch_mandate` 占用 | Agent 级的岗位授权书叫 Charter，存于 AgentSpec 的 `charter` 段；`orch_mandate` 仍是工单级授权记录，增加 `agent_version_id`，派工复验时同时检查授权书状态为 ACTIVE |
| 37 | M1-02 RT-07、ADR-033 | 已有 `ToolRisk`，可撤销性放在哪里 | 保留 `ToolRisk`（错了有多严重），新增正交的 `Reversibility`（NONE / REVERSIBLE / COMPENSABLE / IRREVERSIBLE）。未声明的写工具按 IRREVERSIBLE；MCP 工具仅 `readOnlyHint=true` 时为 NONE |
| 38 | M1-02 RT-07、ADR-034 | 默认审批策略 | M3 起默认审批策略由 `agent-config` 改为 `autonomy`（按自治等级判定）。Agent 版本的 `toolPolicies` 继续有效但只能收紧：`approve`、`deny` 照办，`auto` 不能越过等级 |
| 39 | ADR-025、34 号计划 | 授权书要编译为 Cedar，而 GOV-07 在 M4 | GOV-07 提前到 M3，升为 P0。M3 交付求值器、DENY / ESCALATE 两组策略、授权书编译和 DRYRUN / ENFORCE 切换；空间级策略编辑界面仍在 M4 |
| 40 | ADR-033、ADR-034 | 影子运行如何保证不产生副作用 | `origin = shadow` 的 Turn 在 ToolGateway 只允许 `prepare` 和 READ 工具，`commit` 一律拒绝；不向 IM 外发；用量走评测预算。由 ArchUnit 规则与 `ShadowNeverCommitsIT` 守护，不提供配置开关 |
| 41 | ADR-035 | 价值数字的口径 | 报表只从 `trust_ledger` 汇总；只有绑定任务类型的执行记账；撤销生成冲销；失败与转人工同样计成本和人工；基线必须标注来源（MEASURED / SURVEY / ESTIMATE），修改不回溯 |
| 42 | 34 号计划 | 新增约 95 人日从哪里来 | CON-06（钉钉、企微）、CON-07（OpenAPI 转工具）、GOV-11（多环境）、HCI-08 的工作台与产物中心延后到 M4 之后；M3、M4 施工设计编写时核算净影响，仍不足则 M4 顺延，不压缩 TRU 的 P0 项 |

## 多智能体与运行时权限定案（2026-11-05）

2026-11-05 新增 ADR-038（多智能体团队）、ADR-039（委派授予与权限收窄）、ADR-040（数据集策略与数据标签）与工作项 MAS-01〜07、DAT-01〜06。ADR-036、ADR-037 留给商业租赁合同场景（业务本体、结构化产物）。以下各条统一这几篇与已有设计的接缝，编号续接上表。

| # | 涉及 | 问题 | 定案 |
| --- | --- | --- | --- |
| 43 | ADR-027、34 号计划 | 子 Agent（RT-12）在 M4，而多 Agent 场景与委派权限都依赖它 | RT-12 提前到 M3、升为 P0，并与 MAS-01〜03 同期交付：**委派与权限收窄必须同时上线**，不允许出现「能委派但子 Agent 权限等于父 Agent」的版本。ADR-027 中「深度上限 2、并行 3」改为缺省值，由 TeamSpec 配置，平台上限深度 3、并行 8 |
| 44 | ADR-015、ADR-020、ADR-032 | 编排以服务账号运行时，可能检索到经办人无权看的数据 | `run_as_json` 增加 `onBehalfOf`。SERVICE 模式下根权限 = 服务账号角色 ∩ 经办人权限；名义在委派链上不可改变，确需换名义只能经编排 `emit` 开新工单 |
| 45 | ADR-032 | 主管自己只读、把写操作派给专家时，授权书交集会把写操作全部挡掉 | `Charter` 增加 `delegable`（可下放的动作类别，缺省为空）。链上祖先只检查禁区与 `delegable`，执行者检查完整授权书。`CharterCompiler` 编译禁区用 `context.chain.contains(X)`，额度与范围类策略仍用 `context.agentVersion == X` |
| 46 | ADR-033、M1-04 | 授予在工具调用管线哪一步检查 | `prepare` 第 2 步（白名单）之后插入第 2.5 步「授予」；`listTools` 同样按授予过滤。第 9 步 Cedar context 增加 `chain`、`depth`、`onBehalfOf` |
| 47 | ADR-019 | Flow 作为 Agent 工具时只带 Principal，Flow 内部调用会绕过委派收窄 | Agent 工具形态的 Flow Run 携带调用方 `grant_id`，内部每次模型与工具调用都用它；发布时计算 `requiredCapabilities`，超出授予时启动即拒。API 形态仍以 API Key 的服务账号签发根授予 |
| 48 | ADR-025、ADR-040 | 行列级权限用 Cedar 还是 CEL | 行谓词与列掩码用 CEL 子集，在连接器数据平面执行；Cedar 只管能否访问数据集。cedar-java 部分求值稳定后再评估统一 |
| 49 | ADR-024、ADR-025、ADR-031、34 号计划 | 数据标签与模型路由、内容安全、经验库的关系 | 标签四档 PUBLIC / INTERNAL / CONFIDENTIAL / SECRET，可映射到客户自有分级。去向许可由空间配置：CONFIDENTIAL 能否进云端模型是客户合规决定，平台默认不放行。经验库只收 INTERNAL 及以下。DAT-02 依赖内容安全钩子，GOV-10 的内置正则 PII 脱敏因此提前到 M3（P1），客户审核服务适配器仍在 M4 |

## 业务本体与合同场景定案（2026-11-05）

2026-11-05 以商业租赁合同风险审查为第一个业务场景，新增 ADR-036（业务本体与领域包）、ADR-037（结构化输出与 data item）与工作项 DOC、SCH、ONT、LSE、BIZ、FLW-08、NTF、CON-07a。业务方规格为《租赁合同模型》v2.3。以下各条统一这两篇与已有设计的接缝，编号续接上表。

| # | 涉及 | 问题 | 定案 |
| --- | --- | --- | --- |
| 50 | ADR-036、ADR-018 | 合同模型实例存在哪里、以谁为准 | 已确认版本以业务系统为准；草稿由 Tioka 产生并保存；Tioka 按 `contract.model.confirmed` 拉取完整模型，内容哈希（RFC 8785 + SHA-256）一致才写只读投影，不一致拒收进死信，不做合并。业务系统不向 Tioka 提供修改已确认版本的接口；租约状态是派生数据，由 Tioka 折叠后写回业务系统，可随时重算 |
| 51 | ADR-036、ADR-018 | 合同原文用 Knowledge 切块还是另建文档层 | 另建文档层（`doc_*`）。合同证据只按证据单元 ID 定位，不做向量召回；Knowledge 只放参考资料（修订案例、正反例、制度）。两者不共用表，避免检索结果混入别的合同原文 |
| 52 | 34 号计划、ADR-018 | KNW-09（OCR）在 M4，而合同有扫描件与图片 | KNW-09 的 OCR 部分拆成 DOC-02 提前到 M3（P0），OCR 作为 Model Hub 能力 `ocr`，受配额、审计与数据标签路由约束；图片理解与 Excel 解析仍在 M4 |
| 53 | ADR-036、ADR-040 第 48 条 | 模型上的规则用什么语言、取值未知怎么判 | 与行谓词共用 CEL 运行时。三值语义：适用条件为假 → 不适用；依赖取值未知 → 缺失；空白 → 规则声明的 `onBlank`（缺省不满足）；求值中访问到未知值 → 缺失。求值错误与超时（50 ms）记缺失并告警，不让整轮审查失败 |
| 54 | ADR-028、ADR-037 | 结构化产出用 card 还是新 item 类型 | 新增 `data` item（本条即 ADR-028 要求的契约变更）。负载必须引用 Schema 登记表中已发布的 `schemaKey@version`；校验失败修复用尽仍写 item（`valid=false`），Turn 以 `COMPLETED(reason=output_invalid)` 结束，不新增 Turn 状态 |
| 55 | 第 42 条、ADR-021 | CON-07 已延后到 M4 之后，合同场景需要业务系统工具 | 拆出 CON-07a 在 M3 做：随连接器插件携带 OpenAPI 快照与操作白名单，每个操作必须声明可逆性与动作类别；入站支持 HTTP（HMAC）与 RocketMQ 两路，处理相同。OpenAPI 上传界面与任意系统接入仍按第 42 条延后 |
| 56 | ADR-021、ADR-026 | 通知与 IM 渠道适配器是不是一回事 | 不是。`NotificationChannel` 是 ADR-026「通知」类 SPI，只管出站通知（发送、更新）；`ImChannelAdapter` 管双向会话。飞书通知实现内部复用飞书 IM 适配器。第一期只做飞书与邮件；钉钉、企微、短信照接口在需要时增加，不改调用方 |
| 57 | ADR-021、ADR-015 | 卡片与通知里的深链如何鉴权 | 深链只带业务对象 ID，不带任何令牌或签名票据；业务系统与 Tioka 共用 Keycloak realm，未登录走单点登录后回到原地址，权限由业务系统自己判断 |
| 58 | ADR-032〜034、第 40 条 | 审查 Agent 能写什么、如何上岗 | Agent 只写草稿结论（`submitReviewDraft`，REVERSIBLE），最终结论以法务处置为准；子 Agent 没有任何业务写工具与通知工具。M3 影子期用业务系统的 `visibility = SHADOW` 实现（不发通知、不向法务展示），因为 TRU-11 在 M4；达标后 `lease.review.draft` 设为 L2。M4 起新版本改用平台影子模式 |
| 59 | 34 号计划 | 合同场景新增约 130 人日后端、30 人日前端（含新建业务系统），M3 放不下 | 2026-11-05 负责人选定方案 A：范围不变，M3 延长约 10 周（W19–W40），M4 顺延为 W41–W52，总工期约 12 个月。合同场景全部工作项（含合同链折叠）在 M3 完成，不后移其他 M3 工作项。周次已被第 61 条整体顺延 1 周 |

## 实施前权威冲突裁定（2026-11-05）

M0 编码开工时（分支 `feat/G0-multistep-stream-concurrency`）裁定。该条在分支上原编号为第 36 条，与上方可信放权的第 36 条（Charter）撞号，合并回主干时改为第 60 条；代码与迁移注释中的「ADR-029 第 36 条（租户）」均指本条。

| # | 涉及 | 问题 | 定案 |
| --- | --- | --- | --- |
| 60 | 第 19、22 条；ADR-009；persistence-guardrails；Cursor 架构规则 | 第 19/22 条要求业务表加 `tenant_id` 并保留租户拦截器，与护栏「禁止 tenantId」及单企业独立部署现实冲突 | **废止第 19、22 条中「加 `tenant_id` / 保留租户拦截器」的要求。** 禁止在 DO、迁移、API 引入 `tenant_id` / `sys_tenant`；隔离边界为空间（`workspace_id`）+ 私有资源的 `owner_id`。持久层只用 workspace 行级过滤。角色 `tenant_admin` 保留历史角色码，界面显示「系统管理员」，`sys_role.scope` 取值 `SYSTEM` / `WORKSPACE`。`Principal` 不再含 `tenantId`。未落地的契约脚本（V100 起）按本条改写；M1/M2 契约同步去掉 `tenant_id` |

## M1 排期定案（2026-11-06）

| # | 涉及 | 问题 | 定案 |
| --- | --- | --- | --- |
| 61 | 34 号计划、施工总纲、M1-00 | M1 核查后补入 AST-01、AST-02、G1 闸门基座与前端缺项，后端 118 人天，3 人 8 周排满无缓冲 | 2026-11-06 负责人选定方案 B：M1 延长 1 周为 W5–W13（W13 只做 G1 修复与回归），CON-04、ENG-07 顺延到 M2 前两周；后续整体顺延 1 周：M2 W14–W23，M3 W20–W41，M4 W42–W53，总工期约 12.25 个月。M1 排期以 construction/M1-00 为准 |

## 管理面版本化与可观测定案（2026-11-06）

| # | 涉及 | 问题 | 定案 |
| --- | --- | --- | --- |
| 62 | ADR-017、ADR-023、ADR-024、ADR-025、34 号计划 | 负责人提出供应商、模型、别名路由、供应商配额、模型配额、Prompt 都属于管理面，需要版本化管理以及监控、统计；现有设计只有 Agent 和路由计划有版本，配额只有需求侧，Prompt 分散在五处 | 新增 [ADR-041](ADR-041-config-versioning-observability.md)：发布型与即时型两类对象共用一套版本框架（GOV-12）；执行侧只读当前版本投影，每次调用记录版本号，统计按版本切片；配置归各自领域，不建独立管理模块，前端统一为「模型中枢」管理区。模型侧引用跟随当前版本，Prompt 引用钉版本。供给侧配额命中时跳过候选，不直接拒绝。M1 不增加工作量，只加约束 F11〜F14。新增工作项后端 41、前端 18 人天，建议放 M3 前段（MH-07 并入 MH-11）；2026-11-06 负责人选定放 M3 前段（W20–W27，与 MH-06 一起做），M3 延长约 3 周为 W20–W44，M4 顺延为 W45–W56，总工期约 13 个月；人员不变 |
| 63 | ADR-014、ADR-024、ADR-026、ADR-041 | 负责人追问供应商、模型、API Key 的管理与详细自定义参数、Key 有效期。现有设计一个供应商只有一个 Key，没有有效期、轮换、按 Key 限额，也没有模型参数与覆盖规则；现有 `LocalSecretStore` 用 ECB 加密 | 新增 [ADR-042](ADR-042-provider-credentials-model-params.md)：凭证从供应商拆出，可以有多个，带状态、有效期、提醒、按 Key 限额，支持主备或加权轮询；无中断轮换与紧急吊销；密钥只写不读，权限点 `model:credential:admin` 与 `model:admin` 分开。模型参数按「binding 默认 → 路由组 → Agent 或 Flow 节点 → 单次调用」覆盖，binding 声明可覆盖范围与锁定项。凭证加密统一到 FND-08 的 `CredentialCipher`（M2，+1 人天）。M3 再净增约 17 人天（后端 13、前端 4）。2026-11-06 负责人选定放 M3 前段，M3 再延长约 1 周为 W20–W45，M4 顺延为 W46–W57，总工期约 13.25 个月；人员不变 |

## Flow 工作流编排细化定案（2026-11-06）

| # | 涉及 | 问题 | 定案 |
| --- | --- | --- | --- |
| 64 | ADR-019、ADR-020、ADR-038〜042、M3-03、34 号计划 | 负责人问工作流编排有没有更详细的设计。ADR-019 只有骨架：缺 DSL 与端口类型、表达式语言、完整节点目录、超时取消与副作用崩溃规则、版本、调试、观测；Flow 引擎重写没有工作项与人日，而 FLW-08、ONT-06 依赖它 | 新增 [ADR-043](ADR-043-flow-orchestration-detail.md)：Flow 只做一次 Run 内的确定性流程，触发与定时归编排；DSL 为唯一真相，CEL 表达式与端口类型系统；引擎重写立为 FLW-09（P0，M3 前段）；Flow 接入 ADR-041 版本框架，行为类引用钉版本、模型与知识库跟随；发布形态增加 INTERNAL，含人工或延时节点的 Flow 在 M3 不能发布为工具；运行身份由入口决定；调试中写工具只预演。新增 FLW-09〜FLW-15、FE-12〜FE-14，净增后端 41、前端 16 人日。**排期：2026-11-06 负责人选定方案 A**，全部新增项在 M3 完成，M3 延长约 3 周为 W20–W48，M4 顺延为 W49–W60，总工期约 14 个月 |

## M2 实施计划定案（2026-11-06）

| # | 涉及 | 问题 | 定案 |
| --- | --- | --- | --- |
| 65 | 34 号计划、施工总纲、M2-01〜M2-05、ADR-031、ADR-043 | M2 施工设计与总纲排期不一致（前端约一半人天没有对应工作，G2 缺重启与端到端用例和闸门基座）；RT-09 把「Flow 发布为工具转异步」当作 M2 的第一个使用者，与 ADR-043（FLW-18，M4 之后）相反；ORC-03 的 `flow` 动作建在 M3 要重写的旧引擎上；EVO-01 定在 M2 却没有设计与人天 | 新增 [construction/M2-00](../construction/M2-00-M2实施计划.md) 作为 M2 排期权威，M2 周次 W14–W23 不变。① RT-09 照做 `WAITING_CHILD` 与异步工具结果机制（供 M3 RT-12 使用），M2 只用闸门桩工具验证，Flow 发布为工具保持同步；② M2 编排规则动作只开放 `agent` 与 `admission`，`flow` 动作在 M3 FLW-06 接通 INTERNAL 发布后开放；③ EVO-01 移到 M3，与 EVO-02、EVO-03 一起做；④ 新增 ENG-04c、G2-E2E、ID-BR、IM-ADM、FE-15〜FE-20 与 V145（旧 ECB 密文重加密）；⑤ CON-02、CON-03、HCI-06 后端改由 BE-A 负责，TRU-05、TRU-06 后端改由 BE-B 负责。M2 合计后端 123、前端 37 人天，3 名后端干满 W14–W23，M3 后端实际从 W24 起步，M3 周次在写 M3-00 时按人力核算 |

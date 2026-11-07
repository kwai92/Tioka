<!-- 自动生成，勿改。由 scripts/gen-progress.mjs 写入。CODEOWNERS：CI 机器人 + 架构负责人。 -->

# 实施完成度（自动生成）

> 生成时间：2026-11-07T12:32:46.503Z
>
> 数据来源：仓库内 Surefire 报告（`**/surefire-reports/*.xml`）与 34 号计划工作项表。
> 禁止手工编辑本文件；本地可运行 `node scripts/gen-progress.mjs` 刷新。

## 测试汇总

| 指标 | 值 |
| --- | --- |
| Surefire 报告文件 | 7 |
| 用例总数 | 19 |
| 失败 | 0 |
| 错误 | 0 |
| 跳过 | 0 |
| Gate 套件通过 | 0 |
| Gate 套件失败 | 0 |

## 34 号计划工作项（登记）

共解析 **292** 个工作项 ID（完成状态以闸门/CI 为准，本表仅作索引）。

| ID | 标题 |
| --- | --- |
| ENG-01 | 修复 `OrchAttentionTest`；集成测试每用例独立数据（事务回滚或独立 schema） |
| ENG-02 | CI 加前端构建、类型检查、lint |
| ENG-03 | 下线 6 个假实现 API（GraphRAG、代码解释器、自动化任务、fork、SubAgent、难度路由）或返回 501 |
| ENG-04 | 非 mock 验收流水线：Testcontainers（PG、Redis、Keycloak、RocketMQ）+ 双实例 + 录制回放的 LLM 桩 |
| ENG-05 | 统一 `ObjectMapper` Bean；ID 改 ULID；解析失败不再吞异常 |
| ENG-06 | 拆掉 agent-api 与 mcp-api 循环；tioka-server 只依赖 `-api`；ArchUnit 覆盖 server |
| ENG-07 | 文档只保留 `docs/architecture` 一份，飞书版由脚本生成；完成度由 CI 结果生成 |
| FND-01 | 统一认证过滤器 + 请求级 `Principal`；白名单仅登录、健康检查、IM 回调 |
| FND-02 | 密码改 bcrypt；token 有过期与刷新；删除默认 admin/admin123，首次启动生成随机密码 |
| FND-03 | 核心实体加 `tenant_id`、`workspace_id`、`owner_id`；持久层自动过滤 |
| FND-04 | 角色 + 权限点 + 资源级 ACL；`@RequirePermission` 注解 |
| FND-05 | OIDC 登录（Keycloak 验收），JWT 资源服务器 |
| FND-06 | 服务账号与 API Key（供 Agents API、Webhook、编排使用） |
| FND-07 | EgressGuard 改为 URL 解析 + 主机白名单（修复 `?localhost` 绕过） |
| FND-08 | 代用户 OAuth 令牌保管（飞书等外部系统）；2026-11-06 起同时统一供应商、MCP、数据源凭证的加密（ADR-042，+1 人天） |
| FND-09 | SCIM 2.0 用户与组同步 + 组织架构（M4 照做；F 阶段由 BAS-11〜13 接管，第 69 条） |
| FND-10 | SAML、LDAP 登录（M4 照做，用 Keycloak 配置实现；F 阶段由 BAS-15 接管，第 69 条） |
| FND-11 | Helm chart、空气隔离安装包、企业适配 SPI 实现 |
| GOV-01 | 租户 → 空间 → 成员（角色）模型；空间切换 |
| GOV-02 | 审计管道：所有写操作和工具调用统一写 `AuditEvent`，DB + 文件 sink |
| GOV-03 | 配额生效：Turn 入口占并发、ModelRouter 计 token；Redis 计数 + 周期落库 |
| GOV-04 | 核心多步写入加事务（Agent 发布、知识 ingest、Chat 发送） |
| GOV-05 | 成本归集：按会话、Agent、空间汇总 |
| GOV-06 | 资产目录 + 发布审批（Agent、MCP、Skill、Flow、模型） |
| GOV-07 | 工具调用策略引擎（Cedar），在工具网关强制；支持 ESCALATE 与授权书编译（ADR-032） |
| GOV-08 | OpenTelemetry 全链路 trace（替代自建 `trace_span`） |
| GOV-09 | 审计防篡改（哈希链）+ SIEM 推送；链覆盖审计、外部交互台账与运行清单，应用账号只有 INSERT 与 SELECT（ADR-072，第 75 条：提前到 M4 前段、P1，重估后端 4 人日） |
| GOV-10 | 内容安全钩子真实现（挂点并入 GRD-01，识别器与假名化由 GRD-04 实现，第 73 条；PII 脱敏；内置正则部分提前到 M3、P1，ADR-029 第 49 条） |
| GOV-11 | 多环境（开发/生产）与发布绑定 |
| GOV-12 | 统一配置版本框架：草稿、版本、变更记录、校验、影响分析、发布、回滚、导出导入（ADR-041） |
| GOV-13 | 告警规则与通知（ADR-041） |
| RT-01 | 按会话加锁（Redis 锁 + fencing token）替代全局 `synchronized` |
| RT-02 | `runtime_turn`、`runtime_item` 表替代 `parts_json` 整块存储 |
| RT-03 | 多步工具循环：最大步数、并行工具、终止条件 |
| RT-04 | 事件投递：Item 先入库再发 Redis Stream；任意实例可订阅 SSE，支持 `Last-Event-ID` 续传 |
| RT-05 | cancel 与 steer 经 Redis 信号跨实例生效 |
| RT-06 | 异步 Turn：`append` 立即返回 turnId；worker 执行；崩溃后按租约回收 |
| RT-07 | 审批挂起：工具命中审批策略时 Turn 进入 `WAITING_APPROVAL`，提交后续跑 |
| RT-08 | 对外 Runtime 协议（thread / turn / item / approval），供 Chat、Flow、Orch、IM 共用 |
| RT-09 | 长任务（小时级）与心跳 |
| RT-10 | 上下文压缩（超阈值自动摘要；组装顺序与削减规则以 ADR-048 为准，另见 CTX-01，第 68 条） |
| RT-11 | 容器沙箱：代码解释器、Skill 脚本 |
| RT-12 | 子 Agent 执行（子 Turn、深度与预算限制；ADR-029 第 43 条提前） |
| RT-13 | 工具检索：超阈值时延迟加载 + `tool.search`，范围限于授予（ADR-016 修订，第 67 条；后端 3 人日） |
| MH-01 | 轮次级冻结：冻结键改为 (sessionId, turnId) |
| MH-02 | 自动故障接力：Runtime 异常路径调用 failover，有界次数，时间线记录 |
| MH-03 | 重试、退避、冷却、TPM/RPM 限流（Redis） |
| MH-04 | 模型调用收口：Flow 的 LLM/Agent/Embedding 节点、Knowledge 嵌入都走 ModelRouter；ArchUnit 守护 |
| MH-05 | 用量事件统一上报（含 embedding）；`lastRoutes` 入库 |
| MH-06 | 四种路由模式 + 决策模型 SPI（替换关键词难度） |
| MH-07 | 成本与路由看板（2026-11-06 并入 MH-11，ADR-041） |
| MH-08 | Model Hub 对象版本化（供应商、binding、别名、路由计划）；系统别名；调用记录带版本（ADR-041） |
| MH-09 | 供给侧配额与预算：供应商、binding、别名；月度成本；预警事件（ADR-041） |
| MH-10 | 内置运行状态、健康判定、主动探测、Prometheus 指标（ADR-041） |
| MH-11 | 统计：分组汇总、趋势、版本对比、导出（取代 MH-07，ADR-041） |
| MH-12 | 供应商凭证：多 Key、状态、选用策略、按 Key 限额、有效期提醒、轮换、紧急吊销（ADR-042） |
| MH-13 | 供应商连接详细配置、模型发现、连通性测试（ADR-042） |
| MH-14 | 模型参数：默认值、约束与锁定、扩展参数、分层覆盖、接力参数翻译、阶梯价（ADR-042） |
| ORC-01 | claim 改数据库行锁（`FOR UPDATE SKIP LOCKED`），去掉 `synchronized` |
| ORC-02 | RocketMQ 接入 + 事务 outbox + 消费幂等 |
| ORC-03 | 派工器：Run → Runtime Turn，结果回写 Run |
| ORC-04 | Attention 挂起 → 审批 → 续跑，与 RT-07 共用一套审批模型 |
| ORC-05 | 规则引擎：CEL 条件 + 动作列表 + 版本 + 灰度 |
| ORC-06 | 定时与延时任务（数据库调度表） |
| ORC-07 | 跳数熔断、死信、重放 |
| CON-01 | 工具网关统一鉴权、限流、审计；MCP Grant 基于 Principal 校验 |
| CON-02 | 飞书事件订阅入站：验签解密、@机器人、消息映射到会话 |
| CON-03 | 飞书卡片回调 → 审批提交 |
| CON-04 | Connector 模块测试补齐 |
| CON-05 | MCP 服务端 OAuth；客户端支持 Streamable HTTP；按 MCP 2026-07-28 实现并兼容 2025-11-25（ADR-021 修订、第 67 条，净增后端 2 人日） |
| CON-06 | IM 渠道 SPI 抽象，接入钉钉、企微 |
| CON-07 | 业务系统 REST/OpenAPI 转 MCP 工具 |
| CHT-01 | 会话归属用户与空间，去掉写死的 `ws_default` |
| CHT-02 | 发送改异步：调 RT-08，返回 turnId + 流地址 |
| CHT-03 | 消息只存 Runtime Item，Chat 只存索引（去双份） |
| CHT-04 | 编辑、重试、真分叉（Runtime 层 fork） |
| CHT-05 | 长期记忆注入上下文（由 MEM-01 取代，见 ADR-050、第 68 条） |
| CHT-06 | 分享链接、项目、导入导出、提示词库 |
| CHT-07 | 对外 Agents API（OpenAI 兼容） |
| FLW-01 | 修复 Branch 后汇合节点不执行：未选分支标记 SKIPPED 并传播 |
| FLW-02 | LLM、Embedding 节点改走 ModelRouter（属 MH-04） |
| FLW-03 | HumanCard 节点挂起下游，复用审批模型 |
| FLW-04 | Agent 节点走 Runtime 子 Turn |
| FLW-05 | 自定义节点插件（Java SPI；脚本节点进沙箱） |
| FLW-06 | 发布为 API、Agent 工具、MCP 工具，支持运行时参数覆盖 |
| FLW-07 | 草稿并发编辑检查 |
| FLW-09 | 引擎重写：数据库驱动调度器、派发与租约、唤醒与回收、超时、取消、重试、错误路由、UNCERTAIN、大载荷、Run 事件流 |
| FLW-10 | DSL v1：端口类型、CEL 表达式、发布校验清单、YAML 导入导出 |
| FLW-11a | 新节点：StructuredExtract、Tool、Transform、Merge、Switch、SubFlow、EmitEvent |
| FLW-11b | Delay、Notify 节点 |
| FLW-12 | Flow 版本接入统一版本框架、语义差异、钉版本、影响分析 |
| FLW-13a | 调试：草稿试运行、单节点运行、固定输出、写操作保护 |
| FLW-13b | 调试增强：从此节点重跑、断点、生产 Run 回放 |
| FLW-14a | 观测：节点 span、Run 列表与详情、成本归到节点 |
| FLW-14b | 观测增强：按版本统计、节点热点、告警 |
| FLW-15 | 权限：运行身份矩阵、发布时引用检查、数据标签传播、出网检查、审计 |
| FE-12 | Studio 画布重做（Schema 驱动面板与表单、类型检查、表达式编辑器） |
| FE-12b | 表达式高级补全、模板库界面 |
| KNW-01 | 默认使用真实 embedding（经 Model Hub），HashEmbedding 仅用于测试 |
| KNW-02 | 知识库按空间授权，检索时按 ACL 过滤 |
| KNW-03 | 结构感知切块 + 父子块（补标题路径前缀、表格、页码与位置框，ADR-055，第 70 条） |
| KNW-04 | Rerank（经 Model Hub；补阈值规则，ADR-056） |
| KNW-05 | ingest 异步化（MQ）+ 事务 |
| KNW-06 | 数据源：飞书增量 + 另外两类（待定，如 Confluence、GitLab） |
| KNW-07 | 评测集 + 召回率基线进 CI |
| KNW-08 | citation 只在回答采用时写入 |
| KNW-10 | GraphRAG 真实现（移到 M4 之后，按 ADR-056 第 6 节的评测触发条件再做，第 70 条） |
| HCI-01 | 登录页、空间切换、401 跳转 |
| HCI-02 | SSE 断线续传（`Last-Event-ID`） |
| HCI-03 | 管理后台：用户、角色、配额、审计查询 |
| HCI-04 | Part 渲染快照测试 |
| HCI-05 | 审批台 + 卡片提交唤醒 Run |
| HCI-06 | Part 协议对齐 AG-UI 事件模型 |
| HCI-07 | 轨迹视图（Turn / Step / 工具） |
| HCI-08 | 知识台（工作台、产物中心延后到 M4 之后，ADR-029 第 42 条） |
| TRU-01 | AgentSpec 授权书段、发布校验、档案页（ADR-032） |
| TRU-02 | 有效权限交集、授权书编译为 Cedar、当日累计、四眼（ADR-032） |
| TRU-03 | 负责人失效自动暂停与交接（ADR-032） |
| TRU-04 | 授权书复核提醒与逾期暂停（ADR-032） |
| TRU-05 | 可逆性声明、`TransactionalTool` SDK、`prepare`/`commit`、change_set item、漂移检测、FakeErp（ADR-033） |
| TRU-06 | 变更单卡片：飞书审批卡片、审批台、轨迹视图（ADR-033） |
| TRU-07 | 撤销：补偿消费者、撤销接口与按钮、无法撤销转人工（ADR-033） |
| TRU-08 | 单 Run 爆炸半径与撤销熔断（ADR-033） |
| TRU-09 | 自治等级 L1〜L4、判定表、`autonomy` 审批策略、观察期、事后抽检（ADR-034） |
| TRU-10 | 自动降级与通知（ADR-034） |
| TRU-11 | 影子上岗：`origin=shadow` 只预演、比对、上岗报告（ADR-034） |
| TRU-12 | 指标汇总、Wilson 区间、转正申请（ADR-034） |
| TRU-13 | 任务类型、基线、费率、记账与冲销（ADR-035） |
| TRU-14 | 月度汇总、经营看板、月报推送、导出（ADR-035） |
| TRU-15 | 影子期实测基线（ADR-035） |
| MAS-01 | DelegationGrant、`GrantService`、ToolGateway 授予校验、`on_behalf_of`、审计链字段（ADR-039） |
| MAS-02 | 授权书 `delegable`、Cedar context 加链、祖先禁区生效（ADR-039） |
| MAS-03 | 子任务回收：授予失效、预算退回、沙箱释放（ADR-038） |
| DAT-01 | 数据集登记、DatasetPolicy、连接器 SDK 行列执行（JDBC 与通用 REST）（ADR-040） |
| DAT-02 | 数据标签传播、黑板与对外去向许可（ADR-040） |
| DAT-03 | 标签 → 可用路由计划（ADR-040） |
| DAT-04 | Flow 透传授予与 `requiredCapabilities`（ADR-039） |
| MAS-04 | TeamSpec、`team.plan`、TeamDispatcher、重规划（ADR-038） |
| MAS-05 | 黑板、冲突仲裁、计划审批与仲裁卡片（ADR-038） |
| MAS-06 | 临时子 Agent 模板（ADR-038） |
| MAS-07 | 轨迹视图按任务图展开、why 时间线（前端）（ADR-038） |
| DAT-05 | 权限回答页（ADR-039） |
| DAT-06 | Token Exchange 缩权令牌（`act` 声明）（ADR-039） |
| DOC-01 | 文档层：Word / PDF 解析、证据单元、条款树、按哈希缓存 |
| DOC-02 | OCR 能力与文字层合并（KNW-09 的 OCR 部分，第 52 条） |
| DOC-03 | `@doc.unit` 与引文归一化校验 |
| SCH-01 | Schema 登记表与 `x-tioka-*` 扩展 |
| SCH-02 | AgentSpec `output`、三种约束生成方式 |
| SCH-03 | 校验、证据绑定、修复循环、`data` item |
| SCH-04 | 前端 `data` 渲染器注册表 |
| SCH-05 | 字段级评测 `FieldMatchEvaluator` |
| ONT-01 | 模型定义资产、路径目录、Schema 派生 |
| ONT-02 | 草稿、确认投影、版本与差异 |
| ONT-03 | `@entity.read`、`@model.query`、`@model.coverage` |
| ONT-04 | `rule.evaluate` 与三值语义 |
| ONT-05 | 领域包 SPI 与加载 |
| ONT-06 | @model.build Flow 模板展开 |
| ONT-07 | 折叠调度与 `ont_state` |
| ONT-08 | 确认界面 Web Component |
| ONT-09 | 模型层评测 |
| FLW-08 | Flow 并行 map 节点 |
| NTF-01 | `NotificationChannel` SPI、投递表、去重与重试（第 56 条） |
| NTF-02 | 飞书与邮件两个实现 |
| CON-07a | 最小业务连接器：OpenAPI 快照转工具、HTTP 与 MQ 入站（第 55 条） |
| LSE-01 | 租赁模型定义与 Prompt |
| LSE-02 | 16 条校验器 |
| LSE-03 | 折叠引擎（五阶段、R-1〜R-5） |
| LSE-04 | CEL 函数与 D、H 类要点表达式（需法务配合） |
| LSE-05 | 审查 Agent、授权书、编排规则 |
| LSE-06 | Finding Schema、轮次与局部重审 |
| LSE-07 | 修订案例与正反例同步到知识库 |
| LSE-08 | 金标准、影子期对比与评测 |
| ENG-08 | 设计 A–N 与定案入库为 ADR-015〜030 |
| ENG-09 | M0 契约 PR：`-api` 接口与 record、迁移脚本、事件 Schema、错误码、CODEOWNERS |
| ENG-10 | ArchUnit 守护规则（ADR-030 第 3 条） |
| ENG-11 | 删除 H2，Flyway 管理全部迁移；测试改用 Testcontainers（PostgreSQL 16 + pgvector、Redis 7）（ADR-029 第 29 条） |
| AST-07 | Prompt 模板资产：变量、渲染、版本、试运行、内置模板（ADR-041） |
| AST-08 | Prompt 接入 AgentSpec、Flow 节点、内置提示词、领域包、CHT-06（ADR-041） |
| AST-09 | Prompt 发布评测（ADR-041，与 EVO-06 同机制） |
| FND-12 | 插件宿主：签名、SPI 版本、类加载器隔离、权限声明、适配器登记（ADR-026；ONT-05、FLW-05 的前置，ADR-029 第 66 条，见 M3-06） |
| EXE-01 | AgentSpec `execution` 段预留（只允许 REACT + NATIVE），`plan`、`reflection` item 登记（ADR-046） |
| RTE-01 | ROUTER 型 Agent、候选过滤、分层级联、澄清与兜底、`route_decision`（ADR-044） |
| RTE-02 | 向量匹配决策器（与 MH-06 共用）（ADR-044、ADR-024） |
| RTE-03 | 受控交接与粘滞、Chat 与飞书接入、路由评测（ADR-044） |
| FE-28 | 路由 Agent 配置页与会话当前目标显示（ADR-044） |
| EXE-02 | 执行策略 SPI、`PLAN_EXECUTE`、`REFLECT`（ADR-046） |
| FE-29 | 轨迹视图展示计划与反思，Studio 选择策略（ADR-046） |
| MAS-08 | 任务简报必填结构、共享决定日志、投入分级、团队上线基线（ADR-038、ADR-031 修订） |
| EXE-03 | `CODE_ACT` 与工具桥（ADR-046） |
| EXE-04 | `GOAL_LOOP`（ADR-046） |
| EXR-01 | 外部执行器 SPI、登记表、ACP 执行器（ADR-047） |
| EXR-02 | 模型直通接口（ADR-047） |
| EXR-03 | `COMMAND` 与 Codex app-server 执行器（ADR-047） |
| AST-10 | 通用助手模板（ADR-023 修订） |
| CTX-01 | 四区组装与本轮尾部按 Turn 冻结；结构化预览；工具结果清理；结构化摘要；`CONTEXT_OVERFLOW`；分叉时摘要采用规则（ADR-048） |
| CCH-01 | 缓存友好规则 C1〜C5：确定性序列化、日期下沉、thread 内工具列表稳定、换模型边界；用量明细加缓存写入 token（ADR-049） |
| AST-11 | AgentSpec 预留 `context`、`memory` 段，固定为默认值（ADR-023 修订） |
| CTX-02 | 上下文清单落库与查询；`context` 段开放；子 Agent 隔离与回传上限；交接叙述化；计划复述（ADR-048） |
| CCH-02 | binding 缓存能力与写读单价；断点提示落地；成本计算；命中率指标与告警；断点诊断（ADR-049） |
| MEM-01 | 记忆核心：`mem_item` / `mem_event`、个人记忆、`memory.save`、后台抽取、快照与本轮检索、可移植与标签、保留与删除、权限点、`memory` 段（ADR-050，取代 CHT-05） |
| … | 另有 92 项省略 |

## Gate 相关套件

_本次未发现名称含 GateIT 的 Surefire 套件（可能尚未执行 -Pgate）。_

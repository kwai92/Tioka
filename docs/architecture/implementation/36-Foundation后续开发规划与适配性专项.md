# 36 — Foundation 后续开发规划：面向多业务项目的底座适配性专项

- 状态：**规划建议（2026-11-15），部分已实施**。本文是 F 阶段之后的**增量规划**，不改 [F-00](../construction/F-00-F阶段实施计划.md) 已定的 W80–W89 范围、人天与分工，不改 [S15](../systems/S15-Foundation基础平台系统.md) §19.3 的 F2 增强批次（W90+）定案；新增批次命名 **F3（适配性专项）**，需单独立项。**F3 第一批（ADP-01/02/04/05 与 ADP-12 文档部分）见第 11 节、第二批（ADP-03/05 余下/08 前半）见第 12 节、第三批（ADP-06/07/12 余下）见第 13 节、第四批（ADP-08 余下前半与 GA 装配裁剪闸门）见第 14 节、第五批（ADP-09/11 与部署死键核实）见第 15 节，均已于 2026-11-15 落地**；待决策项（第 9 节）仍需负责人拍板。第 0 节的四处缺口是**规划基线时的状态**，已落地部分以 §11〜§15 为准。**2026-11-16 完成版本分轨与 git 收口（不合并 F3、不污染主干、不丢代码），见第 16 节；§15.5 第 2 条的「103 个改动未提交」风险已解除。**
- 上位与依据：[S15](../systems/S15-Foundation基础平台系统.md)（定位与设计）、[ADR-051](../adr/ADR-051-foundation-platform.md)〜054、[ADR-076](../adr/ADR-076-app-to-app-authorization.md)〜078、[F-00](../construction/F-00-F阶段实施计划.md)（排期权威）、[F-01](../construction/F-01-并行开发任务包.md)（仓库边界与号段）、[F-02](../construction/F-02-工程底座与骨架.md)（双模式）、[F-07](../construction/F-07-后端接入starter.md)（接入点）、[F-11](../construction/F-11-部署与Tioka接入与GF闸门.md)（接入与回退）、[F-12](../construction/F-12-应用间授权.md)〜[F-14](../construction/F-14-多源同步适配器.md)（F2 施工设计）、[34 号计划](34-M0至M4推进规划与工作项.md) 第 8 节（增强批次）。
- 本文回答：**Foundation 从「Tioka + 合同业务系统共用的中台」走向「整个项目与后续所有业务项目的底座」，还差什么、按什么顺序补、做到什么算完、哪些边界刻意不适配**。
- 写法：只用文字与表格，不附代码、SQL 或配置片段。
- 基线：`tioka-foundation` 独立仓库 `main`（截至 2026-11-15 `904ca45`，工作树有未提交的 `foundation-app` 控制器装配收口改动）。

---

## 0. 结论

1. **功能实现已远超计划周，但「底座化」尚未完成**。26 个聚合模块（另有 `foundation-sync` 4 个二级子模块）、约 1,200 个 Java 类、66 个 Flyway 迁移、9 类 starter、SCIM/飞书/LDAP 适配器、Compose/Helm/离线清单均已落地；但 **Tioka 侧至今零接入**（`BAS-20/26/28` 未落地）、**`foundation-ui` 仍是空目录**、**F-00 §6.1 定义的 12 条 `Gf*GateIT` 目前只有 `GfSmokeIT` 有实现**（全仓库仅 9 个 `*IT.java`）。当前是一个「功能齐备但还没被人接过」的中台，不是已被验证的底座。
2. **适配性缺口集中在四处**：① 没有版本化契约与契约测试（接入方升级无防护）；② 能力不可裁剪（8 个 starter 共用一个总开关、14 个业务模块无条件全装配）；③ 事件只有 RocketMQ 单通道、Webhook 未实现（非 Java / 无 MQ 的业务系统接不进来）；④ 没有「不接 Foundation 也能跑」的运行时降级路径。
3. **建议把 F3 适配性专项插在 F2 之前或与其前段并行**，因为契约版本、能力开关、事件 schema 是 F2 三块能力（应用间授权、卡片注册、多源同步）的地基；先做 F2 再补适配性，等于把三块能力各写死一遍再返工。
4. **适配要有边界**。不建议把 Foundation 服务端改造成兼容 MySQL / Kafka / 任意 IdP 的「万能中台」——成本失控且违背 S15 §4「技术栈与 Tioka 一致、不引入新中间件」。正确的切法是：**服务端锁定一套栈，把适配层放在接入侧**（SDK、契约、开关、命名空间），让形态不同的业务项目以不同深度接入，而不是让底座迁就每一种栈。这是本文最重要的判断。
5. **新增工作量估算约 47 人天**（后端 36、前端 11），分 12 个 `ADP-*` 工作项；另有 3 项 F 阶段欠账需先清零。排期与人力由负责人在 GF 通过后核算，本文不排周。

---

## 1. 现状盘点（2026-11-15 代码基线）

### 1.1 已交付

| 层 | 内容 | 证据 |
| --- | --- | --- |
| 工程化底座 | `foundation-dependencies`（BOM）、`foundation-parent`（`${revision}` + flatten）、9 类 starter、`foundation-test`（Testcontainers + ArchUnit 规则集）、`foundation-template` 模板工程、`foundation-it-example` | 根 `pom.xml`、README §1〜4 |
| 中台服务 | `foundation-common/app/org/account/authz/auth/audit/notify/file/calendar/sequence/sync` 共 13 个能力域，均为 `-api` / `-biz` 分层 | 各模块目录 |
| 数据 | Foundation 库 66 个 Flyway 迁移（V1 起，独立历史表） | `foundation-server/src/main/resources/db/migration` |
| 扩展点 | `SyncSourceAdapter`（同步）、`ObjectStorageAdapter`（文件）、`NotificationChannel`（通知）、`AuditArchiveStore`、`DataScopeFilterProvider`、`IdempotencyStore`、`ConsumedStore` | 各模块 `*-api` |
| 对外协议 | SCIM 2.0 `/scim/v2/Users`、`/scim/v2/Groups`、服务发现与令牌接口 | `foundation-sync-biz` |
| 部署 | 自包含 Compose、Foundation/UI 镜像、Helm chart、离线清单、realm 初始化 | `deploy/` |
| 装配条件 | 54 处 `@ConditionalOnMissingBean`、11 处 `@ConditionalOnClass`、14 处 `@ConditionalOnProperty`；`starter-cache` 支持 `local` / `redisson` 双后端；`starter-mq` 的 RocketMQ 与 MyBatis-Plus 为 `optional` + 条件装配 | 各 `AutoConfiguration` |

### 1.2 进行中

- 最新提交 `904ca45`、`8db2199`、`374ac5f` 正在做**控制器装配范式收敛**（`@ResponseBody` + `AutoConfiguration` 显式 `@Bean`），工作树尚有未提交改动。
- `6cb918d` 补齐 F-07 的请求边界身份注入 `PrincipalHolder` 生产写入方；`249d52d` 补齐 BAS-05/06/07 的权限中心管理面 REST。**管理面刚成形，尚未做端到端联调**。

### 1.3 欠账与缺口（按对适配性的影响排序）

| # | 缺口 | 类型 | 影响 |
| --- | --- | --- | --- |
| D1 | **Tioka 侧零接入**：`tioka.foundation.mode=local\|foundation` 双模式只在 F-02/F-11 设计里，主仓库配置与 Java 代码中均无 `com.foundation` 依赖 | F 阶段欠账 | 底座没有被第一个真实接入方验证；BAS-20/26/28 与 GF 12 条用例未落地 |
| D2 | **`foundation-ui` 只有 `.gitkeep`**，`tioka-ui` 中也没有 Foundation 前端包/门户基座代码；S15 §6.3、§12.3 与 F-00「BAS-FE-01/02/10/11 已在 M4 完成」的记录与仓库不符 | 承诺未兑现 | 前端接入方无物可用；微前端与统一入口落空 |
| D3 | **无版本化契约文件与契约测试**：无 `openapi`/`contracts` 目录，OpenAPI 仅 springdoc 运行时生成；模板 CI 引用的 `docs/openapi.json` 基线不存在 | S15 §14.1、§5.3 承诺未兑现 | 所有接入方升级无 breaking-change 防护；非 Java 应用只能靠读代码 |
| D4 | **事件单通道**：outbox 只投 RocketMQ `fdn-events`，Webhook（HMAC）未实现；`DomainEvent` 无 `schemaVersion`，`payload` 为自由 Map | S15 §12.4/§14.2 承诺未兑现 + 文档未覆盖 | 非 Java / 无 RocketMQ 的业务系统订阅不到组织、授权、字典变更 |
| D5 | **能力不可裁剪**：8 个 starter 共用 `foundation.enabled` 单一总开关；14 个业务 `*AutoConfiguration` 无一处 `@ConditionalOnProperty`；`foundation-server` 无条件引入全部 `-biz` 与 PG/Redis/Flyway | S15 §5.2/§6.1 承诺未兑现 | 只想用字典与参数的小服务也必须拉起整套中台与中间件 |
| D6 | **技术栈强绑定**：SQL 全是 PG 方言（`TIMESTAMPTZ`/`JSONB`/分区/`pgcrypto`/`CAST AS jsonb`）；BOM 锁 Spring Boot 3.2.12；测试基座钉死 PG16 / Redis 7 / RocketMQ 5.3.1 / Keycloak 24；realm 名 `enterprise`、topic、Redis 前缀、bucket 前缀硬编码 | 定位带来的新需求 | 不同栈的项目无法复用基座；多环境共部署命名冲突 |
| D7 | **降级路径缺失**：`SnapshotCache` 缓存过期且回源失败即 `FORBIDDEN`（符合 S15 §11.3 的「拒绝」，但不可配置）；客户端熔断直接抛异常；若干 `Http*Client` 方法仍是 `return null` 占位；无 `local` / `standalone` 运行时模式 | 文档未覆盖 | 「不接 Foundation 也要能跑」「Foundation 故障时的业务连续性」无开关 |
| D8 | **独立仓库不自包含**：README 与 `deploy/README.md` 的 `../docs/architecture/...` 链接在独立克隆后全部断链；主 CI 只有两条 `mvn` 命令，S15 §5.3 承诺的 ArchUnit / 依赖扫描 / OpenAPI 比对 / 权限清单校验 / 镜像构建均未入主 CI；模板的 `{app}` 占位与缺失基线导致 CI 必失败；`distributionManagement` 依赖未注入的环境变量 | 承诺未兑现 | 客户单独拿 Foundation 时交付不闭环；「从模板起新子系统 1 天过自检」不可达 |
| D9 | F-07 §9 的 `AppTokenClient` / `@RequireAppGrant`、`foundation-client` 的卡片/授权配置项、`EffectiveRoleResolver`、`SelfManagedImporter`、`NoopSubjectMembership` 等仍为接口占位或空实现 | 大部分属 F2（W90+）已排期，不算违约 | 需在 F2 施工时按契约替换，不能带着占位进底座发版 |

---

## 2. 接入方画像（适配性的靶子）

「做好适配性」必须先说清**适配谁**。下表定义四类接入方，后续所有设计以「每一类都能以合理成本接入」为判定标准。

| 画像 | 代表 | 技术栈 | 需要的 Foundation 能力 | 不需要 / 不能要求 |
| --- | --- | --- | --- | --- |
| **A 核心平台** | Tioka（api / worker / ui） | Java 17 · Boot 3.2 · PG · 全量中间件 | 全量：SSO、授权快照、数据范围、组织、审计、消息、文件、日历、编号 | 必须保留 `local` 模式可回退；不能因 Foundation 挂掉导致存量用户登录即失败 |
| **B 新建业务系统** | `lease-biz`、后续「租金结构化」「执行预警」等 | Java 17 · Boot 3.x · 独立部署与独立库 | 中台主数据 + 集中授权 + 部分公共服务，按 starter 按需引用 | 不需要全部 13 个能力域；不接受「必须起 Keycloak + RocketMQ 全套才能用字典」 |
| **C 遗留 / 外购 / 非 Java** | 客户既有 OA、.NET/Node/Python 服务 | 任意 | OIDC 身份、OpenAPI 只读查询、Webhook 事件、自管授权 | 不引 Java starter、不连 RocketMQ、不读 Foundation 库 |
| **D 轻量服务 / 工具** | 定时任务、数据同步工具、边缘服务 | Java 或非 Java，可能无 Redis / 无 MQ | 只取字典 / 参数 / 编号 / 组织查询 | 不接受全套中间件与全套 starter |

**能力矩阵**（`●` 必须 · `○` 可选 · `—` 不涉及）：

| 能力 | A | B | C | D |
| --- | --- | --- | --- | --- |
| OIDC 单点登录与令牌校验 | ● | ● | ● | ○ |
| 授权快照 / 权限注解 / 数据范围 | ● | ● | —（自管） | — |
| 组织与人员只读投影 | ● | ● | ○ | ○ |
| OpenAPI 契约 + 契约测试 | ● | ● | ● | ● |
| 事件（RocketMQ / Webhook 二选一） | ● | ○ | ●（Webhook） | ○ |
| 公共服务（字典/参数/编号/文件/消息） | ● | ○ | ○ | ○ |
| 前端包与微前端基座 | ● | ○ | —（iframe / 外链） | — |
| 本地/独立运行降级 | ● | ○ | ○ | ○ |
| 全量中间件 | ● | ○ | — | — |

---

## 3. 适配性原则（建议写入硬约束）

| # | 原则 | 判定标准（做到什么算数） | 当前差距 |
| --- | --- | --- | --- |
| P1 | **契约先行且版本化** | 每个对外接口有入库的 OpenAPI 基线；每个事件有 `schemaVersion` 与 JSON Schema；接入方可用契约测试自检；破坏性变更只进主版本并有并行期 | D3、D4 |
| P2 | **能力可裁剪** | 每个 starter 与每个能力域有独立开关；`foundation-server` 可按 profile 只装配需要的域；最小接入形态不强制 PG/Redis/MQ | D5 |
| P3 | **依赖可替换，但替换点在 SDK 侧** | 服务端锁定 PG/RocketMQ/Keycloak；接入侧对缓存、事件投递、IdP 校验、存储提供 SPI 与本地实现；明确「不适配清单」 | D6 |
| P4 | **降级可配置** | 有 `local` / `foundation` / `self-managed` 运行模式；fail-closed 为默认但可按能力配置；客户端有缓存兜底而非直接抛异常 | D7 |
| P5 | **配置外部化与命名空间** | realm 名、topic、Redis 前缀、bucket、库 schema 均由部署注入 `deploymentId` 派生，代码内无字面量 | D6 |
| P6 | **演进可控** | 发布兼容矩阵（Boot/JDK/PG/Keycloak/构件版本）；每次发版附升级说明；starter 与服务端接口版本可协商 | D3、D6 |
| P7 | **独立仓库自包含** | 单独克隆即可构建、跑测试、读文档、发版、离线交付；README 无跨仓库断链；CI 覆盖多接入形态 | D8 |

> **P3 的边界声明**：适配性 ≠ 无限兼容。Foundation **服务端**保持 PG 16 + Redis + RocketMQ + Keycloak 一套栈（S15 §4、F-11「不引入新中间件」）；**不为 MySQL / Kafka / 其他 IdP 提供服务端实现**。业务项目若栈不同，通过 C/D 类路径（OIDC + OpenAPI + Webhook）接入，或由其在 SDK 侧提供本地 SPI 实现。这条边界必须由负责人在第 9 节 D2 拍板，否则适配工作会失控。

---

## 4. 分批路线图

| 批次 | 内容 | 前置 | 与现有计划的关系 |
| --- | --- | --- | --- |
| **F1 债务清零** | 补齐 D1/D2：BAS-20/26/28 接线与双模式、BAS-FE-01/02/10/11 前端包与门户基座真正入库、GF 12 条闸门用例转绿、控制器装配收口 | 当前 | **属 F-00 原定范围**，不新增人天；是后续一切的前提 |
| **F3 适配性专项**（本文新增） | `ADP-01`〜`ADP-12`：契约、开关、替换点、降级、多形态 SDK、交付闭环 | F1 的 GF 通过 | 建议**前置于 F2 或与其前段并行**；新增约 47 人天，需立项 |
| **F2 既有增强** | BAS-29〜33、BAS-FE-14〜16（应用间授权 / 卡片工作台 / 多源同步），施工设计 F-12〜F-14，号段 V200+ | GF 通过；**建议追加「F3 契约与开关已交付」** | 范围与人天不变（37 人天），但实现必须建立在 F3 的契约版本与开关之上 |
| **F4 生态化** | 接入自助化（登记应用即生成 SDK 配置与契约测试脚手架）、接入方兼容矩阵例行回归、第二个真实业务系统接入复盘 | F3 | 不排周次，按需立项 |

**为什么 F3 要在 F2 前**：F2 三块能力都要新增对外接口、新增事件类型、新增权限清单/卡片清单结构、新增 starter 注解。若在没有版本化契约与细粒度开关时先写，F2 会产出三套各自硬编码的接入面，之后 F3 必须逐个返工；反过来先建契约与开关，F2 只是「照规范填内容」。

---

## 5. F3 工作项（`ADP-01`〜`ADP-12`）

编号用新前缀 `ADP`（Adaptation），与原 `BAS` / `BAS-FE` 不冲突；人天含编码、测试与评审。所有工作项在 `tioka-foundation` 仓库，除注明外。

### 5.1 A 组：契约与演进（11 人天）

| ID | 工作项 | 人天 | 依赖 | 验收 |
| --- | --- | --- | --- | --- |
| ADP-01 | **OpenAPI 契约基线与契约测试**：把运行时 springdoc 产物固化为入库 `docs/contracts/openapi-v1.yaml`；生成变更 diff；提供接入方可直接引用的契约测试脚手架；主 CI 加「契约比对 + 破坏性变更检测」 | 3 | F1 | 改一个响应字段导致 CI 失败；C 类接入方仅凭契约文件可完成一次查询 |
| ADP-02 | **事件 schema 版本与双通道投递**：`DomainEvent` 增 `schemaVersion`；为 `fdn.*` 全部事件定义 JSON Schema 并入库；outbox 增加 Webhook 投递（HMAC 签名、重试、死信、订阅白名单），与 RocketMQ 并列可配 | 4 | ADP-01 | 一个无 RocketMQ 的 Node 服务收到 `fdn.authz.changed` 并校验签名；事件字段新增不破坏旧订阅方 |
| ADP-03 | **接口版本与兼容策略**：REST 路径或头带 API 版本；starter 客户端与服务端做版本协商；定义「兼容三档」（新增字段 / 新增接口 / 破坏性）与并行期；写进发版规则 | 2 | ADP-01 | 服务端升 v2、旧 starter v1 在并行期内仍可用；不兼容时给出明确错误而非 500 |
| ADP-04 | **清单 schema 与校验入 CI**：权限清单、卡片清单、应用授权清单共用一份 JSON Schema；把 `check-permission-manifest.sh` 从模板提升为主仓库 CI 步骤，删除「缺 python3/node 就静默跳过」的分支 | 2 | ADP-01 | 提交一份缺字段或权限码拼错的清单，CI 拒绝合入 |

### 5.2 B 组：可裁剪与可替换（12 人天）

| ID | 工作项 | 人天 | 依赖 | 验收 |
| --- | --- | --- | --- | --- |
| ADP-05 | **细粒度能力开关**：把 8 个 starter 的 `foundation.enabled` 拆为 `foundation.<capability>.enabled`（保留总开关作快捷方式）；14 个业务域 `AutoConfiguration` 补条件装配；`foundation-server` 提供 `full` / `iam` / `common` / `minimal` 四种装配 profile | 4 | F1 | 只启 `minimal` 时服务能起且只暴露字典/参数/编号；`iam` 不装配文件、日历、消息 |
| ADP-06 | **接入侧替换点**：为缓存、事件订阅、IdP 校验、对象存储、密钥管理补齐 SPI 与「本地实现」，让 B/D 类项目不装 Redis/RocketMQ 也能用 starter；明确不适配清单（服务端不做 MySQL/Kafka） | 5 | ADP-05 | 一个不连 Redis、不连 MQ 的 B 类示例工程通过六项接入自检 |
| ADP-07 | **配置外部化与部署命名空间**：realm 名、topic、Redis 前缀、bucket、库 schema 全部由 `foundation.deployment-id` 派生并可由环境变量覆盖；清理 SQL/代码中的 `enterprise`、`fdn-events`、`fdn:`、`app_foundation`、`region_code DEFAULT 'CN'` 等字面量 | 3 | F1 | 同一套镜像起两套 `deployment-id`，库/键/topic/bucket 互不干扰；离线清单按 id 生成 |

### 5.3 C 组：接入与降级（17 人天，其中前端 11）

| ID | 工作项 | 人天 | 依赖 | 验收 |
| --- | --- | --- | --- | --- |
| ADP-08 | **运行模式与降级策略**：落地 `foundation.client.mode=foundation\|local\|self-managed`；`SnapshotCache` 的 fail-closed 改为默认可配（含审计留痕与告警）；客户端熔断改为「过期缓存兜底 + 明确降级标记」，清除 `Http*Client` 的 `return null` 占位；自管模式的授权导入真正可用 | 4 | ADP-05、F1 | Tioka 切 `local` 后 G1 权限闸门仍全绿；Foundation 停机 10 分钟，A 类已登录用户核心读操作可用、写操作按策略拒绝 |
| ADP-09 | **多形态接入包**：Java（starter）、非 Java（OpenAPI + Webhook + SCIM 的示例与最小客户端）、前端（登录/菜单/按钮/选择器）三份接入说明与自检脚本；把 F-00 §3.1 的「六项自检」升级为按画像分级的自检矩阵 | 5 | ADP-01、02 | C 类（Node）与 D 类（纯 Java 无中间件）各跑通一次自检 |
| ADP-10 | **前端包与微前端基座的适配层**（前端）：在 F1 补齐 `foundation-ui` 的基础上，支持「门户内子应用 / 独立运行 / iframe 第三方 / 外链」四种加载方式、主题与语言下发、通信接口版本化；前端包发布为 npm 包而非源码复制 | 8（FE） | F1（BAS-FE-01/02/10/11）、ADP-01 | 一个 B 类业务系统前端不复制基座代码、只装 npm 包即可挂入门户并独立运行 |

### 5.4 D 组：交付与验证（7 人天）

| ID | 工作项 | 人天 | 依赖 | 验收 |
| --- | --- | --- | --- | --- |
| ADP-11 | **兼容性矩阵 CI**：建立并例行回归矩阵（构件版本 × Spring Boot 3.2/3.3 × JDK 17/21 × PG 16 × Keycloak 24/25，以及 A/B/C/D 四类接入样例）；把 ArchUnit、依赖扫描、契约比对、清单校验、镜像构建补进主 CI | 4 | ADP-01、05、09 | 矩阵中任一格失败即阻断发版；四类接入样例每次发版自动跑一遍 |
| ADP-12 | **独立仓库自包含与可交付**：把 S15/ADR/F-xx 的设计摘要与接入规范收进 `tioka-foundation/docs/`，修复全部跨仓库断链；补齐 `settings.xml` 模板与 CI secret 说明、可导入的离线制品清单、模板 `{app}` 重命名后可跑通；README 增加「接入方画像 → 该读哪几篇」导览 | 3 | ADP-09 | 在一台只克隆了 foundation 的干净机器上，按 README 完成构建、测试、发到本地仓库、起最小部署、跑通一个新子系统自检 |

**合计 47 人天**（A 11 + B 12 + C 17 + D 7；其中后端 36、前端 11）。不含 F1 债务清零（属 F-00 原范围）与 F2（37 人天，原定案）。

---

## 6. 关键设计约定（实现时照此克制推导）

| 主题 | 约定 |
| --- | --- |
| 契约版本 | 对外接口一律带主版本；事件体带 `schemaVersion`，只允许「加字段 / 加事件类型」为兼容变更；删字段、改语义、改枚举为破坏性，只进主版本并要求一个并行发布期；starter 与服务端版本不强制同号，靠协商与矩阵校验 |
| 开关层级 | `foundation.enabled`（总开关，默认 true）→ `foundation.<capability>.enabled`（starter/能力域）→ `foundation.<feature>.*`（子特性）。关闭某能力时，其 Bean、路由、Flyway 迁移分组、健康检查项一并下线，不留半装配状态 |
| 装配 profile | `full`（全量，A 类与中台自身）、`iam`（组织/账号/权限/快照）、`common`（字典/参数/编号/审计）、`minimal`（只字典/参数/编号，D 类）。profile 只影响 `foundation-server`；接入方永远通过按需引 starter 裁剪 |
| 替换点边界 | **允许替换**：缓存实现、事件投递通道、IdP 的令牌校验器、对象存储、密钥源、审计归档。**不替换**：服务端关系库（PG）、服务端消息总线（RocketMQ）、认证协议实现方（Keycloak）、Flyway 迁移方言。边界外的需求走 C/D 类接入路径 |
| 降级语义 | 默认 fail-closed（延续 S15 §11.3）；允许按能力配置为「过期缓存 + 降级标记」，但必须写审计并触发告警；身份与授权类能力**不允许** fail-open。降级标记必须透传到响应头与日志，便于业务方判断数据新鲜度 |
| 命名空间 | 所有共享资源名 = `{prefix}:{deploymentId}:{appCode}:{业务键}`；`deploymentId` 由部署注入，缺省 `default` 并在生产启动日志告警；审计哈希链按 `deploymentId` 独立 |
| 兼容矩阵 | 每次发版更新 `docs/compatibility-matrix.md`：构件版本 × Boot × JDK × PG × Keycloak × 四类接入样例；只保证矩阵内的组合，矩阵外明确「不保证」 |
| 迁移号段 | 沿用 [F-01](../construction/F-01-并行开发任务包.md)：Foundation 库 V1 起、F2 用 V200+、Tioka 侧 V270+。**F3 建议用 V100 起**（与 F 阶段 V1〜V50 一带、F2 V200+、Tioka 段均不冲突），合入时以首个空号为准；ADP 大多不新增表，主要为契约与配置 |
| 硬约束继承 | 单企业独立部署、禁止 `tenant_id` / `sys_tenant`（ADR-029 第 60 条）；主键 ULID；时间列 `TIMESTAMPTZ`；跨模块只依赖 `-api`；配置前缀 `foundation.*`；不引入未经 ADR 许可的新中间件 |

> **关于「多环境隔离」与「禁止多租户」的边界**：`deploymentId` 解决的是**同一客户多套部署/多环境**的资源命名冲突，不是租户隔离；不在数据模型里引入 `tenant_id`，不提供按租户的数据分区。集团多公司仍用组织树表达（S15 §3.2）。

---

## 7. 验收闸门（建议命名 `GA`，与 GF 区分）

| 用例 | 断言 | 覆盖画像 |
| --- | --- | --- |
| `GaContractGateIT` | OpenAPI 基线入库、契约比对可检出破坏性变更；事件 schema 校验与 Webhook 签名投递通过 | A/B/C/D |
| `GaModularityGateIT` | `minimal` / `common` / `iam` / `full` 四种 profile 均能独立启动且只暴露声明的能力；关闭单能力不留半装配 Bean | B/D |
| `GaReplaceSpiGateIT` | 无 Redis、无 RocketMQ 的 B 类工程用本地实现跑通六项接入自检；同一镜像两个 `deploymentId` 资源不串 | B/D |
| `GaDegradeGateIT` | `local` / `foundation` / `self-managed` 三模式切换；Foundation 停机时已登录用户降级行为符合策略且审计留痕；身份类能力不放行 | A/B |
| `GaMatrixGateIT` | 兼容矩阵全部组合与 A/B/C/D 四类接入样例发版前全绿 | 全部 |
| `GaSelfContainedIT` | 干净机器只克隆 foundation 仓库即可构建、测试、发版、起最小部署、从模板起新子系统并 1 天内过自检 | 全部 |

**总完成标准**：① F1 的 `GfFoundationE2EIT` 连续 3 天绿；② 上述 6 条 GA 用例全绿；③ **至少两个真实业务系统**（Tioka + 一个新建 B 类系统）在不改底座主干的前提下完成接入，且接入方团队自行完成、不需要底座团队驻场；④ 一次破坏性变更在并行期内完成平滑升级，旧接入方零改动。

---

## 8. 排期与人力

- **不排周次**。按 [34 号计划](34-M0至M4推进规划与工作项.md) 第 10 节，计划周与实际速度脱钩，本文只给顺序与人天，GF 通过后由负责人按当时人力核算并写入 F3 任务包。
- **建议顺序**：F1 债务清零 → `ADP-01/02/03/04`（契约组，串行关键路径）→ `ADP-05/06/07` 与 `ADP-08/09/10` 并行 → `ADP-11/12` 收口 → 再放行 F2。
- **关键路径**：契约组 → 多形态接入包 → 兼容性矩阵 → 发版。ADP-10（前端）依赖 F1 的前端包入库，是最容易卡住的一环，建议与 F1 同时启动。
- **与 F2 的取舍**：若人力不允许 F3 完整先做，最低限度先交付 **ADP-01、02、04、05** 四项（约 13 人天），再开 F2；其余 ADP 可在 F2 后补，但不能省略 ADP-01（否则 F2 的接入面无版本约束）。

---

## 9. 待负责人决策

| # | 事项 | 建议 | 卡住什么 |
| --- | --- | --- | --- |
| D1 | F3 与 F2 的优先级 | **F3 契约组先行，其余并行**，理由见第 4 节 | F2 三块能力的实现方式与返工量 |
| D2 | 是否要求服务端适配 MySQL / Kafka / 其他 IdP | **否**，只在 SDK 侧提供替换点，明确不适配清单（P3） | 若答「是」，ADP-06 人天需重估且违背 F-11「不引入新中间件」 |
| D3 | Spring Boot 版本策略 | 底座构件以 3.2.12 为基线，兼容矩阵声明验证过的 3.3/3.4 组合；业务项目不强制以 `foundation-parent` 为父，可只 import BOM | `foundation-parent` 的定位与业务项目自由度 |
| D4 | fail-open 允许范围 | 身份与授权类**不允许**；只读字典/参数/组织投影允许「过期缓存 + 降级标记」，需审计与告警 | ADP-08 的策略默认值与等保口径 |
| D5 | 前端包与门户的代码归属 | 统一收进 `tioka-foundation/foundation-ui` 并发布 npm 包；F-00 里「BAS-FE-01/02/10/11 已在 M4 完成」的记录需核实并回写 | 第 1.3 节 D2 欠账清零、ADP-10 |
| D6 | 私有 Maven 仓库与 npm 仓库 | 确认地址、凭据注入方式、离线包是否需含构件 tar | ADP-12 与「客户只要 Foundation」的交付形态 |
| D7 | 是否会出现「一份 Foundation 服务给多个客户/多套环境」 | 若会，`deploymentId` 必须在 F3 落地并升级为部署一等公民；若确定单客户单实例，可降级为配置清理 | ADP-07 的范围 |
| D8 | 第二个真实接入方是谁 | 建议选一个与 Tioka 形态差异最大的 B 类系统作为 GA 验收样例，避免「两个 Java 全量系统」验证不出适配性 | GA 总完成标准第 ③ 条 |

> **编号歧义提示**：本节 D1〜D8 是**待负责人决策项**；第 1.3 节的 D1〜D9 是**欠账与缺口清单**（D1 Tioka 零接入、D2 `foundation-ui` 空壳、D3 无版本化契约、D4 事件单通道、D5 能力不可裁剪、D6 技术栈强绑定、D7 降级路径缺失、D8 独立仓不自包含、D9 接口占位）。两套编号**同名不同义**，引用时必须写明「§1.3 D5」或「§9 D5」，不可只写「D5」。

### 9.1 拍板结论（2026-11-25 负责人确认）

以下结论**均以当前代码实测为依据**（子仓 pin `8558993f`），不复述第 1.3 节的基线描述——该节是 11-15 基线，已被 §11〜§15 多批交付大面积推翻。

| # | 结论 | 依据 / 落点 |
| --- | --- | --- |
| D1 | **已被事实回答，无需再拍**：F3 契约组先行已完成 | §11〜§15 五批已落地 ADP-01〜09、11、12（唯 ADP-10 前端包未做，依赖 F1 前端入库）；F2（37 人天）未开始 |
| D2 | **已按建议落地**：服务端锁栈，适配性放接入侧 | `docs/contracts/compatibility-matrix-v1.json` 的 `server.lockedStack`（PostgreSQL / Redis / RocketMQ / Keycloak / MinIO）与 `notAdapted` 5 条；仍需按 ADR-030 走变更流程确认 |
| D3 | **已按建议冻结**：服务端锁 3.2.12 + JDK 17 | 同上契约文件 `status: frozen-v1`，由 `scripts/verify-compatibility-matrix.mjs` 与 CI 矩阵共同守门（当前 RC=0） |
| D4 | **拍板：身份与授权类不允许 fail-open** | 代码已按此默认（`DegradePolicy` 对 authz 恒 `FAIL_CLOSED`）。落地改点：`FoundationAuthzProperties.isStaleAllowed()` 固定为 `false`（或移除 `stale` 选项）。**在该改点落地前，生产不得启用 `stale`** |
| D5 | **本批未拍，仍待决**（需与 D6 同时定） | 建议仍是「统一收进 `foundation-ui` 发 npm 包」。实测 `foundation-ui` 只有 `.gitkeep` 且**不在根 pom 的 24 个 module 中**；`tioka-ui` 有真实前端（127 文件 / 2985 行）但**零消费** Foundation 的 48 个 Controller |
| D6 | **本批未拍，仍待决**（需负责人给真实地址与凭据注入方式） | 与 D5、ADP-12、N4 发版流水线是同一个结：不授权仓库则「客户只要 Foundation」无可复现构件路径 |
| D7 | **已按「若会」方向落地，无需再拍** | `DeploymentNamespaceEnvironmentPostProcessor` + 其单测 + `deploy/compose.yml` + `KeyPrefixNamespaceTest`（§13 第三批 ADP-07） |
| D8 | **拍板：暂缓** | 直接后果：`GfBizMigrationGateIT` 与 `GfFoundationE2EIT` 两条闸门**按判据「消费方先行」不得开工**——二者验收口径都依赖「合同业务系统」，而该系统全仓不存在（grep 无命中） |

**本批同时拍板的另三项**（原不在 §9 表内，登记于此以免另开真相源）：

| 事项 | 结论 | 说明 |
| --- | --- | --- |
| N9 病毒扫描 | **不做** | `NoopVirusScanHook` 维持默认装配，`scan()` 返回 `SKIPPED` 且不阻断上传下载（`FileService:68-70` 调完 `scan()` 即 `storage.put()`，无阻断分支）。这是**已知且接受的**状态，不再是「待补缺口」 |
| N4 发版流水线 | **先不管** | 与硬边界「不 deploy 到任何 Maven 仓库」直接冲突。现状：`${env.FOUNDATION_REPO_URL}` 全仓无注入点、`.github/workflows/` 下只有 `ci.yml`，故 `mvn deploy` 在 CI 上完全不可达。**遗留文档不一致**：`docs/delivery/README.md:46` 声称该变量用于「发版 job」，而该 job 不存在——若长期不解决，应改文档而非留悬空承诺 |
| `foundation-ui` | **独立处理** | 与 D5/D6 解耦推进；在 D5 拍板前不动，避免归属返工 |
| N8 凭据加密 | **已做**（Foundation sync 侧） | 子仓 PR #10 / 主仓 PR #105：新增 `CredentialResolver` SPI 与 `ParamCredentialResolver`，复用既有 `ParamApi.secretValue` + `AesGcmParamCipher`（不造第三套 cipher）。**遗留边界**：Tioka 自己的 `tioka.feishu.app-secret`（`tioka-server/src/main/resources/application.yml:163`，走明文环境变量）是另一条链路，改它触及硬边界「不动 Tioka 业务行为」，**未动、需单独授权** |

### 9.2 拍板后解锁的工作与仍被堵的工作

**已解锁**（不需再拍板）：

1. **补 GF 闸门**：F-00 §6.1 要求 12 条，实测只有 4 条在清单内，缺 8 条。**截至 2026-11-26 已补 5 条**（`GfSnapshotEventGateIT`、`GfAccountLifecycleGateIT`、`GfSsoGateIT`、`GfOffboardingGateIT`、`GfSyncGateIT`，均 CI gate job 实测通过、核到 `Tests run` 计数，**gate 累计 9 类 46 用例全绿**），剩 3 条：`GfTiokaModeGateIT` 被 BAS-20 堵；`GfBizMigrationGateIT` 与 `GfFoundationE2EIT` 因 D8 暂缓而不得开工（且 `GfFoundationE2EIT` 还需合同业务系统这一外部接入方，全仓无命中）。
   （原记「`GfSsoGateIT` 须先核实自动开通 OIDC 触发点是否存在」——已核实：`AppRegistrar.register` 同事务内 `keycloak.upsert` 开通，接线完整，见 §24。`GfOffboardingGateIT` 与 `GfSyncGateIT` 同为**部分形态**：前者口径里的「两边会话失效」需 Tioka 与合同业务系统为接入方、「记忆清理触发」属 MEM-01；后者「飞书测试租户同步正确」需外部真实租户（字段映射正确性已由 `FeishuFieldMappingTest`/`DiffPreviewTest` 覆盖）。二者的未覆盖点均在 javadoc 如实标注、**未用 `@Disabled` 掩盖**，见 §32/§35。）
   （原记「`GfSsoGateIT` 须先核实自动开通 OIDC 触发点是否存在」——已核实：`AppRegistrar.register` 同事务内 `keycloak.upsert` 开通，接线完整，见 §24。）
   **这是 F2 的硬前置**：§3 表明确写 F2 的前置条件是「GF 通过」。
   **补闸门的价值已实证**：`GfAccountLifecycleGateIT` 在 CI 上暴露了账号域的**真实装配缺陷**（见 §23）——该缺陷此前从未被任何测试发现，因为全仓从无 IT 端到端调过 `/api/accounts`。
2. **D4 落地**：把 `isStaleAllowed()` 固定为 `false`，使「身份授权类不允许 fail-open」从约定变成代码级硬约束（同时消解 `degradation.md §2.1` 记录的「两个决策出口并存」欠账）。
3. **回写 F-00 §1 / §3.2**（§10 已要求的动作）：「BAS-FE-01/02/10/11 已在 M4 完成」的记录与实际仓库不符——实测这些工作落在 **`tioka-ui`** 而非 `foundation-ui`，后者仍只有 `.gitkeep`。

**仍被堵**（需负责人给输入）：

- **BAS-20**：全仓无 Tioka→中台用户供给机制，foundation 模式拿 Tioka `userId` 当 `personId` 查中台快照必全拒 → `GfTiokaModeGateIT` 完整形态无法开工，补齐要改用户体系。
- **D5 + D6**：前端归属与仓库地址未定 → ADP-10（8 FE 人天）与企业级就绪度第 ⑦ 维无法推进。
- **R4-07**：知识库嵌入绑定完整性（建库即拒 / ingest 入口拒）**暂缓**。风险仍在：`kb_base.embedding_binding_id` 可空，无绑定时 `embed(null,…)` 落到三级兜底即「用 chat 模型做 embedding」，现靠维度校验兜住。
- **starter-mq 是否应生产依赖 starter-data**（S15 §5.2）**暂缓**：`OutboxDO` 依赖后者 TIMESTAMPTZ 桥接，现仅 test scope。

> **本节是拍板结论的权威记录**。后续若再有拍板，追加为 §9.3 而**不改写**本节，以便保留决策时点与依据。

---

## 10. 需回写的文档

| 文档 | 动作 |
| --- | --- |
| [F-00](../construction/F-00-F阶段实施计划.md) §1 / §3.2 | 核实「BAS-FE-01/02/10/11 已在 M4 完成」与实际仓库不符；若确未完成，改为 F1 债务并标注 |
| [F-01](../construction/F-01-并行开发任务包.md) §1 第 6 条 | 增列 F3 批次与 V100 号段、F2 对 ADP 契约/开关的前置依赖 |
| [S15](../systems/S15-Foundation基础平台系统.md) §5.3 / §12.4 / §14 | 补「契约版本与 schema」「Webhook 双通道」「能力开关矩阵」「运行模式与降级」「部署命名空间」五节；§19.3 增加 F3 与 F2 的顺序说明 |
| [ADR-029](../adr/ADR-029-design-rulings.md) | 若 D1〜D4 拍板，增一条「底座适配性边界」定案，避免后续 ADR 无限扩张兼容范围 |
| `tioka-foundation/README.md` | 修断链、补接入方画像导览、兼容矩阵入口 |
| `tioka-foundation/docs/` | 新增 `contracts/`、`compatibility-matrix.md`、四类接入指南（ADP-01/09/12 的产物） |

---

## 11. F3 第一批执行记录（2026-11-15）

在 `tioka-foundation` 独立仓库实施，按「契约组 + 开关组」先行，未触碰 Tioka 主仓库业务代码，未新增中间件。

| 工作项 | 状态 | 实际交付 |
| --- | --- | --- |
| ADP-05 能力开关 | 已交付 | `foundation-starter-core/condition/` 新增 `@ConditionalOnFoundationCapability` + `FoundationCapabilityCondition`；9 个 starter 的自动配置改为「总开关 AND 能力开关」，缺省全 true、接入方零改动；6 个组合语义单测；`docs/adaptation/capability-switches.md` 开关矩阵。**server 装配 profile 部分未做**（见下） |
| ADP-01 接口契约基线 | 部分交付 | `ApiInventoryContractTest` 扫描全部 REST 控制器（含 `@ResponseBody` + 显式 `@Bean` 装配的三种形式），冻结 `api-inventory-v1.txt` **181 条接口**，删接口/改路径/改方法即 CI 变红。**交付的是接口清单基线，不是完整 OpenAPI 文档**；springdoc 运行时产物尚未固化为 `openapi-v1.yaml` |
| ADP-02 事件版本与双通道 | 已交付 | `DomainEvent` 加 `schemaVersion`（四参数工厂保持兼容）；`event-schemas-v1.json` 随 jar 发布，登记 20 个事件与兼容规则；`EventSchemas` 加载器；`EventSink` SPI + `WebhookEventSink`（HMAC-SHA256 签名、白名单、超时、非 2xx 触发 outbox 重试）；`OutboxRelay` 支持并列通道；MQ body 新增保留字段 `schemaVersion`（兼容变更） |
| ADP-03 接口版本协商 | 未开始 | 只在 `docs/contracts/README.md` 写下兼容三档规则，运行时 API 版本头与协商未实现 |
| ADP-04 清单 schema 与 CI | 已交付 | `permission-manifest-v1.schema.json`、`permission-manifest-example.json`；校验脚本去掉「缺 python3/node 静默跳过」，改 fail-closed；主 CI 新增契约文件存在性与清单校验步骤 |
| ADP-12 仓库自包含 | 部分交付 | 修复根 README、`deploy/README.md`、`docs/ops/演练记录` 全部跨仓库断链；新增 `docs/README.md`（接入方画像与导览）、`docs/contracts/README.md`。**私有仓库 settings 模板、可导入离线制品、模板 `{app}` 占位 CI 仍待补** |

### 11.1 实施中发现并修复的真实缺陷

- `foundation-starter-data` **漏引 `foundation-starter-core`**（BAS-24 抽取遗留），与 S15 §5.2「所有 starter 依赖 core」不符；补齐后能力开关注解才可用。
- 本机控制器绝大多数**不标 `@RestController`**，而是 `@ResponseBody` + 类级 `@RequestMapping` + AutoConfiguration 显式 `@Bean` 装配；接口清单扫描若只认 `@RestController` 会漏掉几乎全部对外接口，已按三种形式修正。
- 早期构建体检用 `mvn ... | tail` 会吞掉 Maven 退出码，导致「假绿」；本批验证统一改为重定向日志 + 取 `$?`。

### 11.2 验证结果

- JDK 17 · `mvn -o -q test` 全仓库 **719 个单测，0 失败 / 0 错误 / 0 跳过**；新增的 7 个测试类均确认执行。
- 权限清单校验脚本对示例清单通过（exit 0）；仓库内跨仓库断链清零。
- 未跑 Testcontainers 类 IT（本机 Docker 不可用，按既有约定归 CI 判定）；GF 12 条闸门用例仍未补齐。

### 11.3 剩余工作

1. **F1 债务清零仍是最高前置**：Tioka 侧零接入（`BAS-20/26/28` 与双模式）、`foundation-ui` 空壳、GF 12 条闸门用例。本批没有动这些，底座仍未被第一个真实接入方验证。
2. F3 剩余（第一批处理时点）：ADP-03 接口版本协商、ADP-05 的 server 装配 profile、ADP-06 接入侧 SPI 与本地实现、ADP-07 部署命名空间、ADP-08 运行模式与降级、ADP-09 多形态接入包、ADP-10 前端包适配层、ADP-11 兼容矩阵 CI、ADP-12 剩余交付项。**其中 ADP-03、ADP-05 装配 profile、ADP-08 前半已在第二批交付，见第 12 节。**
3. 第 9 节 D1〜D8 待决策项仍待负责人拍板，尤其 D2「适配边界」——本批已按建议（服务端锁栈、SDK 侧替换）落地，需以 ADR-030 变更流程确认。

---

## 12. F3 第二批执行记录（2026-11-15）

延续第一批「不动 Tioka 主仓库业务代码、不新增中间件、不改已冻结的 181 条接口清单基线」的边界，在 `tioka-foundation` 独立仓库实施三项：把能力开关真正用到服务端装配上、补接口版本协商、补运行时降级骨架。

| 工作项 | 状态 | 实际交付 | 未交付部分 |
| --- | --- | --- | --- |
| ADP-05 余下：server 装配 profile | **部分交付** | `FoundationServerProfile`（`full/iam/common/minimal`）+ `FoundationServerProfileEnvironmentPostProcessor`（`spring.factories` 注册、最低优先级属性源）；15 个业务 `AutoConfiguration` 各带 `foundation.server.domains.<域>` 条件；`application.yml` 加 `FOUNDATION_SERVER_PROFILE`；8 个测试含「15 个装配类域名合法且不重复」的一致性断言；`docs/adaptation/server-profiles.md` | `GaModularityGateIT`（四种 profile 各自独立启动并断言只暴露声明能力）未跑，本机无 Docker；profile 不自动裁剪中间件（留给 ADP-06 的本地 SPI） |
| ADP-03 接口版本协商 | **部分交付** | `FoundationApiVersion` 常量（v1）；`ApiVersionFilter`（缺省头视为 v1、不支持 406 + 明确错误码、非法 400、响应回写版本头）；所有 `starter-client` 出站自动带版本头；7 个测试；契约导览 §3 | v2 契约文件、并行发布期路由、旧 starter 连新服务端的端到端回归；**刻意不做路径分版**（会破坏接口清单基线） |
| ADP-08 运行模式与降级 | **部分交付** | `DegradeContext` 标记 + `X-Foundation-Degraded` 响应头 Filter；`DegradePolicy` / `foundation.client.degrade.*` 白名单（身份授权类强制 `FAIL_CLOSED`）；`TtlCache.getWithDegrade` 过期兜底 + `FoundationUnavailableException`；字典/参数/组织/人员四客户端接入；`SnapshotCache` 接线既有 `failMode`（默认 `deny`、可选 `stale` 且审计 WARN + 降级标记）；19 个测试；`docs/adaptation/degradation.md` | `foundation.client.mode=foundation\|local\|self-managed` 三模式与自管授权导入、`GaDegradeGateIT` 停机演练、降级事件写入审计中心、Redis 侧同构兜底 |
| ADP-12 文档 | 补强 | `docs/README.md` 导览更新、新增 `docs/adaptation/server-profiles.md` 与 `degradation.md`、`docs/contracts/README.md` 补版本协商 | 私有仓库 settings 模板、离线制品清单、模板 `{app}` 占位 CI 仍待补 |

### 12.1 实施中确认的三个事实

- 15 个业务 `AutoConfiguration` 改造前**没有任何一处 `@ConditionalOnProperty`**，服务端确实无条件全装配；用 `EnvironmentPostProcessor` + `addLast` 注入默认值，是为了让显式配置总能覆盖、且单独复用某个 `-biz` 构件时因 `matchIfMissing=true` 保持原行为。
- `FoundationAuthzProperties.Snapshot.failMode` **早已定义却从未接线**到 `SnapshotCache`（配置写了不生效）；本批接线并保留默认 `deny`，`SnapshotDegradeTest` 的 6 个既有用例全部回归通过。
- 规划里写的「若干 `Http*Client` 方法仍是 `return null` 占位」经核对多为**业务空响应语义**（如日历无数据、取号返回 204），不是未实现占位；本批未强行改成异常以免破坏既有语义，改为在「Foundation 不可用」路径抛明确的 `FoundationUnavailableException`，让业务方能区分故障与空结果。

### 12.2 验证结果

- JDK 17 · `mvn -o -q test` 全仓库 **751 个单测，0 失败 / 0 错误 / 0 跳过**（第一批 719 + 本批新增 32）；7 个新增测试类全部确认执行，既有 `SnapshotDegradeTest` 等回归通过。
- `FoundationContextTest` 的 Spring Boot 上下文正常启动，确认 `spring.factories` 注入的 profile 默认属性没有破坏全量装配；`api-inventory-v1.txt` 仍为 181 条，逐字比对通过；仓库内跨仓库断链保持为零。
- 未跑 Testcontainers 类 IT（本机 Docker 不可用，按既有约定归 CI 判定）；GF 12 条闸门用例仍未补齐。

### 12.3 边界与下一步

1. **F1 债务清零仍是最高前置**，且本批仍未触碰：Tioka 侧零接入、`foundation-ui` 空壳、GF 12 条闸门用例。底座至今没有被真实接入方验证过。
2. 第 9 节 D1〜D8 仍待拍板，本批新增的一处敏感点是 **D4**：授权快照按「默认 deny、可显式配 `stale` 且必须留痕」实现；若 D4 决定身份类一律禁止 fail-open，只需把 `isStaleAllowed()` 固定为 `false`。生产环境在拍板前不应启用 `stale`。
3. F3 剩余（第二批处理时点）：ADP-06、ADP-07、ADP-08 余下、ADP-09/10、ADP-11、ADP-12 余下，以及四条 `GA*GateIT` 与 F1 的接线。**其中 ADP-06、ADP-07、ADP-12 余下已在第三批交付，见第 13 节。**

---

## 13. F3 第三批执行记录（2026-11-15）

延续前两批边界：只改 `tioka-foundation` 独立仓库，不动 Tioka 主仓库业务代码，不新增中间件，
不改已冻结的 181 条接口清单基线。本批做**部署命名空间、接入侧替换点自检、独立仓库交付闭环**，
其中前两项是 P5 / P3 原则从「文档承诺」变成「代码与自动化」的关键一步。

| 工作项 | 状态 | 实际交付 | 未交付部分 |
| --- | --- | --- | --- |
| ADP-07 配置外部化与部署命名空间 | **部分交付** | `foundation-starter-core/namespace/DeploymentNamespace`（派生规则 + 格式校验 + `defaults()`）；`FoundationProperties.deployment-id`；`CoreAutoConfiguration` 暴露 Bean 并在生产缺省值时 WARN；`DeploymentNamespaceEnvironmentPostProcessor` 以 `addLast` 注入最低优先级派生值；`application.yml` 的 realm / topic / issuer / JWKS / 桶 / 键前缀全部外部化；`foundation-starter-cache` 键前缀按 id 派生；Compose / Helm / `deploy/init/render-deployment.sh`（按 id 生成 realm 导入文件与 9 条 export）；20 个测试；`docs/adaptation/deployment-namespace.md` | 库 schema 与审计哈希链按 id 独立（规划 §6 约定）、Helm `values-*.yaml` 按 id 生成、`GaReplaceSpiGateIT` 两套 id 端到端互不干扰验证（需 Docker） |
| ADP-06 接入侧替换点 | **部分交付** | `docs/adaptation/replacement-points.md`：15 类 SPI / 默认实现 / 可替换实现 / 切换方式矩阵、P3 不适配清单、D 类无中间件最小配置；`foundation-it-example` 加 core/cache/mq 三个 test 依赖与 `StandaloneRuntimeTest`（无 Redis / 无 MQ / 无 PG 的六项自动化自检），随主 CI `verify` 例行回归 | 无中间件的 **B 类完整工程**（含登录与授权）六项自检、Node 最小客户端示例、C 类接入说明（ADP-09）、`GaReplaceSpiGateIT` 端到端 |
| ADP-12 独立仓库自包含 | **部分交付** | `docs/delivery/settings.xml.template` 与 `docs/delivery/README.md`（CI secret 清单、离线制品清单、干净机器 8 步自检）；`deploy/offline/manifest` 补 deployment-id 生成说明；`foundation-template/scripts/verify-rename.sh`（复制 → 改名 → 占位符残留扫描 → 清单校验 → 关键文件检查）；主 CI 增加模板改名与命名空间渲染守门；模板 CI 的 OpenAPI 基线缺失改为「有基线才严格比对」；模板 `foundation.client.*` 死键回写 | 私有仓库地址 / npm 仓库 / 构件 tar 是否入离线包（D6 待拍板）、`GaSelfContainedIT` 干净机器端到端、`foundation-ui` 真实前端产物（F1 债务） |

### 13.1 实施中发现并修复的真实缺陷

1. **部署文档的两个死配置键**：`deploy/README.md` 声明 `foundation.storage.bucket-prefix` 与
   `foundation.redis.prefix`，但仓库内没有任何 `@ConfigurationProperties` 绑定它们；实际生效的是
   `foundation.file.storage.bucket`（`FileProperties`）与 `foundation.cache.key-prefix`
   （`FoundationCacheProperties`）。按代码回写文档，并把完整桶名纳入命名空间派生。
2. **realm 与 issuer 不同源**：`application.yml` 把 `jwk-set-uri` / `issuer` 里的 realm 写成字面量
   `enterprise`，与 `foundation.keycloak.realm` 分离；改 realm 时令牌 `iss` 校验会静默失败。
   现在三者由同一个 `DeploymentNamespace` 派生。
3. **模板配置全是死键**：`foundation-template` 的 `application.yml` 与 README 写
   `foundation.client.base-url` / `app-code` / `service-account`，但 `FoundationProperties` 前缀就是
   `foundation`（仓库里不存在 `foundation.client` 前缀的配置类）。已按代码回写为
   `foundation.base-url` / `app-code` / `service-account.*`，并补 `deployment-id`。
4. **模板 CI 必然失败**：原 OpenAPI 步骤 `diff -u docs/openapi.json ...` 引用不存在的基线，
   且 `spring-boot:run` 在无数据库的 runner 上起不来；改为「有基线才严格比对，否则打印指引跳过」。
5. **文档守门规则 + Windows 路径**：`DeployManifestTest` 禁止部署文档出现租户字段字面量，
   本批新增的 `deploy/README.md` 说明触发了它，已改为不含字面量的表述；`verify-rename.sh`
   最初用 `mktemp -d` 得到 `C:\...` 盘符路径，被受管 shell 的删除保护拦截，改为仓库内相对路径临时目录。

另有一处**待核实**（未改代码）：`deploy/README.md` 与 Compose 声明的
`foundation.secret.master-key-ref`（称来自 `SecretStoreAdapter`）在 Java 代码中找不到绑定类；
参数与人员加密实际读 `foundation.param.master-key`。已在 `replacement-points.md` 标注，
需设计侧确认是补实现还是删文档。

### 13.2 验证结果

- JDK 17 · `mvn -o -q test` 全仓库 **776 个单测，0 失败 / 0 错误 / 0 跳过**
  （第二批 751 + 本批新增 25：`DeploymentNamespaceTest` 9、`DeploymentNamespaceEnvironmentPostProcessorTest` 6、
  `KeyPrefixNamespaceTest` 4、`StandaloneRuntimeTest` 6），4 个新增测试类全部确认执行。
- `FoundationContextTest` 上下文正常启动，说明新增 EPP 与 `application.yml` 的嵌套占位符
  `${FOUNDATION_XXX:${foundation.namespace.xxx}}` 解析正常，未破坏既有装配。
- 本地脚本：`verify-rename.sh` 通过（28 处占位符全替换、无残留、改名后清单校验通过）；
  `render-deployment.sh` 的 default / `prod-a` 派生值正确、非法 id 退出 2、生成 realm 文件首行正确；
  权限清单校验通过；`api-inventory-v1.txt` 仍为 181 条；跨仓库断链保持为零。
- 未跑 Testcontainers 类 IT（本机无 Docker，按既有约定归 CI）；GF 12 条闸门用例仍未补齐。

### 13.3 边界与下一步

1. **F1 债务清零仍是最高前置**，三批均未触碰：Tioka 侧零接入（`BAS-20/26/28` 与双模式）、
   `foundation-ui` 空壳、GF 12 条闸门用例。底座至今没有被真实接入方验证过；F3 的适配性越强，
   没有接入方验证的风险越集中，建议下一批优先回到 F1 接线，而不是继续横向补 ADP。
2. 第 9 节 D1〜D8 仍待拍板。本批强化了 **D7**（多套部署）：`deployment-id` 已按「会存在多套部署」
   落地为可配置一等公民；若确定单客户单实例，可降级为配置清理。**D6**（私有仓库/离线包形态）
   现在有模板与清单，但地址与制品形态仍需决策。
3. F3 剩余（第三批处理时点）：ADP-08 余下（`foundation.client.mode` 三模式与自管授权导入）、
   ADP-09（C/D 类接入包）、ADP-10（前端包适配层，依赖 F1 前端包入库）、ADP-11（兼容矩阵 CI）、
   以及 6 条 `GA*GateIT` 中尚未实现的 4 条。第 8 节「最低放行线」（ADP-01/02/04/05）已超额完成。
   **其中 ADP-08 的三模式与装配分支、`GaModularityGateIT` 中不需要 Docker 的部分已在第四批交付，
   见第 14 节；自管授权导入器经核对属 F2，本阶段不提前实现。**

---

## 14. F3 第四批执行记录（2026-11-15）

### 14.1 为什么这一批没有按 §13.3 的建议去做 F1 接线

§13.3 第 1 条建议「下一批优先回到 F1 接线」。本批开工前的只读核查发现事实已变：主仓库当前分支为
`feat/bas26-foundation-base-switch`，但 `git status` **干净**，BAS-26 第一批接线
（BOM 导入、`tioka-framework` 双模式类、7 个单测、`application.yml` 配置）在工作树中已不存在；
且核查期间主仓库若干文件被外部进程反复重写。结合第三批前用户对「动主仓库」的追问与当时
「第三批仍只改 `tioka-foundation`」的边界确认，本批**维持既定边界**：只改 `tioka-foundation`
独立仓库，外加本文与工作区日志两份文档；不改 Tioka 主仓库任何 pom / Java / yml，不新增中间件，
不改已冻结的 181 条接口清单基线。**F1 接线需在明确授权修改主仓库业务代码后单独成批。**

> **更正（第五批核实，见 §15.1）**：本节的「BAS-26 第一批改动不存在 / 需重新授权」结论**不成立**。并行会话的 git 记录显示第一批 `ad2ef1f2` 与第二批 `804ddb63` 均已提交，且用户已明确授权主仓库改造；当时看到的「干净工作树」是并行会话提交瞬间的快照。本节保留原记录以说明当时的判断依据，后续以 §15.1 为准。第五批不改主仓库的理由是**避免与并行 F1 接线冲突**，不是缺少授权。

### 14.2 交付内容

| 工作项 | 状态 | 实际交付 | 未交付部分 |
| --- | --- | --- | --- |
| ADP-08 余下（前半） | **部分交付** | `starter-core/runtime/FoundationRuntimeMode`（三值宽松解析、非法值拒绝、缺省兼容基线）与 `FoundationRuntimeModeGuard`（启动期契约提示）；`condition/ConditionalOnFoundationRuntimeMode` + 条件实现；`CoreAutoConfiguration` 暴露模式 Bean；`foundation.client.mode` 与 `foundation.client.local.*` 配置；两套客户端自动配置**类级互斥**；`LocalFoundationData` + 四个本地只读实现（dict/param/org/person，含组织树递归展开与敏感参数过滤）；`docs/adaptation/runtime-modes.md`；28 个新增用例 | 自管授权导入器 `SelfManagedImporter`（属 F2，见 D9）、Tioka 侧 `tioka.foundation.mode` 与本键的一致性联动校验（属 F1）、`GaDegradeGateIT` 停机演练 |
| `GaModularityGateIT` 中无需 Docker 的部分 | **部分交付** | `GaModularityGateTest`：四种装配 profile 的能力域冻结基线 `server-profile-domains-v1.txt`（改动即 CI 红）+ 15 个与真实装配类同构的条件探针，验证「profile → EPP 属性 → `@ConditionalOnProperty` → Bean 装配」链路与「关闭单域不留半装配」；主 CI 增加基线存在性守门；4 个用例 | 四种 profile 各自完整启动 `foundation-server` 并断言只暴露声明 REST 能力，需 PG/Redis/Keycloak，归 Testcontainers CI |
| ADP-06 自检补强 | 已交付 | `StandaloneRuntimeTest` 自检 7：无中间件上下文中 core 能按属性暴露运行模式 Bean | — |
| 文档 | 已交付 | 新增 `docs/adaptation/runtime-modes.md`；回写 `degradation.md`、`docs/README.md` 导览（§7 第四批）、主 CI 契约守门 | 兼容矩阵 `docs/compatibility-matrix.md`（ADP-11） |

### 14.3 实施中发现并处理的真实问题

1. **手写基线顺序错误被闸门当场抓到**：`common` profile 按 `ALL_DOMAINS` 顺序应为
   `common,audit,sequence`，初写为 `common,sequence,audit`。`GaModularityGateTest` 首跑即失败，
   修正后通过——这正是冻结基线相对「只写单测断言」的增量价值：域集合的**顺序与内容**都被钉死。
2. **不能只在客户端内部判断模式**：若保留 `FoundationClientAutoConfiguration` 无条件装配、
   仅在 `FoundationHttpClient` 出站时短路，仍会留下 OkHttp 客户端、服务账号令牌与重试/熔断
   Bean，构成「配了 local 却半连中台」。改为两套 `AutoConfiguration` 靠条件注解类级互斥，
   并由 `RuntimeModeAssemblyTest` 断言独立模式下 `FoundationHttpClient` 数量为 0。
3. **配置落点无需新建配置类**：`FoundationClientProperties` 前缀就是 `foundation`，其 `Client`
   内部类对应 `foundation.client.*`，故 `foundation.client.mode` 直接加在 `Client` 上即可；
   与第三批修掉的模板死键 `foundation.client.base-url`（真实键是 `foundation.base-url`）要区分。
4. **守卫刻意不 fail-fast**：`LOCAL` 模式下接入方可能自行注册 `DictClient` 等 Bean，core 层
   无法判断是否真的缺实现；因此只给结构化 WARN 与可断言的 `warnings()`，不拒绝启动。

### 14.4 验证结果

- JDK 17 · `mvn -o -q test` 全仓库 **809 个单测，0 失败 / 0 错误 / 0 跳过**，137 个测试类
  （第三批 776 + 本批新增 33：模式解析 5、守卫 7、条件注解 5、装配互斥 6、本地种子 5、
  装配裁剪闸门 4、无中间件自检 7 新增 1）。所有新增测试类均在 surefire 报告中确认执行。
- `FoundationContextTest` 上下文正常启动，说明 `CoreAutoConfiguration` 新增的
  `FoundationRuntimeMode` Bean 未破坏全量装配；第四批对**未配置模式**的存量行为零改动，
  由 `RuntimeModeAssemblyTest`、`StandaloneRuntimeTest` 双重断言。
- `api-inventory-v1.txt` 仍为 **181 条**；新增 `server-profile-domains-v1.txt` 4 行；
  仓库内跨仓库断链保持为零（新文档全部使用仓库内相对链接）。
- 未跑 Testcontainers 类 IT（本机无 Docker，按既有约定归 CI）；GF 12 条闸门用例仍未补齐。

### 14.5 边界与下一步

1. **F1 债务清零仍是最高前置**：四批 F3 均未触碰 Tioka 主仓库业务代码；本批核查还发现
   主仓库工作树已回到干净状态，接线需重新授权并单独成批，不能视为「顺手续做」。
2. **新增敏感点（F1 接线必读）**：`foundation.client.mode`（starter 侧）与
   `tioka.foundation.mode`（应用侧）**不自动同步**。Tioka 切 `local` 而未同步设置本键时，
   会出现「应用以为单飞、starter 仍连中台」；F1 接线须由部署侧同一环境变量派生两键，
   并补一条启动期一致性校验。
3. F3 剩余（第四批处理时点）：自管授权导入器（F2）、ADP-09 的 C 类 Node 最小客户端与接入说明、
   ADP-10 前端包（依赖 F1 前端包入库）、ADP-11 兼容矩阵 CI、完整 OpenAPI 请求/响应字段基线、
   v2 契约与并行期、其余 `GA*GateIT` 的 Docker 端到端部分、第二个真实接入方验收。
   **其中 ADP-09 与 ADP-11 已在第五批交付，见第 15 节**；其余仍待推进。
   第 8 节最低放行线已超额完成。
4. 第 9 节 D1〜D8 仍待拍板，尤其 **D4**（授权 `stale` 是否允许）、**D6**（私有仓库 / 离线包
   制品形态）、**D8**（第二个真实接入方）。本批未新增需决策项。

---

## 15. F3 第五批执行记录（2026-11-15）

### 15.1 开工核查：对 §14.1 判断的更正，以及本批为何仍不改主仓库

本批开工只读核查发现，§14.1 的结论**不成立**，须更正：

- 主仓库 HEAD 已是 `804ddb63`（`feat/bas26-foundation-base-switch`），BAS-26 第一批 `ad2ef1f2`
  与第二批 PF-00a 切父工程均已提交；并行会话的工作日志还明确记录了用户已授权主仓库改造。
  第四批看到的「工作树干净、接线文件不存在」是并行会话提交瞬间的快照误判。
- 本批开工瞬间主仓库 `pom.xml` 为已修改状态，收尾时 `git status` 只剩本文一项未跟踪文件，
  说明并行会话的 F1 接线正在推进并已落盘。**本批不改主仓库的真实理由是避免与并行 F1 接线冲突**，
  而不是缺少授权；§14.1 的「需重新授权」表述作废。
- `tioka-foundation` 当前停在 `fix/pf00a-parent-publishable` 分支（`38aabe0`），
  **F3 五批共 103 个改动全部未提交且不在 `main` 上**。这是当前最大的交付风险，见 §15.5。

用户本轮只说「继续」，未明确在上一轮给出的两个方向中做选择。按不越界原则，本批继续在
`tioka-foundation` 内收口剩余适配项：**ADP-09（C 类接入包）与 ADP-11（兼容矩阵 CI）**，
不触碰主仓库 pom / Java / yml，不改已冻结的 181 条接口清单基线，不新增中间件。

### 15.2 交付内容

| 工作项 | 状态 | 实际交付 | 未交付部分 |
| --- | --- | --- | --- |
| ADP-09（C 类接入包） | 已交付 | `sdk-examples/node/foundation-client.mjs`（Node >= 18、零第三方依赖、纯 ESM）：REST 版本头出站与 400/406 / 响应版本串路处理、Webhook HMAC-SHA256 **原始字节**验签（定长比较）、envelope 五字段校验、`schemaVersion` 分支、契约头与头体一致性、`eventType + deliveryId` 幂等去重（可注入 TTL/时钟）、降级标记读取；`test/client.test.mjs` 18 例，其中一条**直接读 Java 源码与事件契约 JSON**逐项比对头名 / 签名算法 / 主版本 / envelope 字段；`README.md`；CI 新增 `node-sdk` job | 多实例幂等需接入方换共享存储；OIDC 令牌获取由接入方自理；未做成 npm 包（定位为可复制参考实现） |
| ADP-11（兼容矩阵） | 已交付 | 机器可读契约 `docs/contracts/compatibility-matrix-v1.json`（服务端锁定栈与 5 条不适配声明、3 模式 / 4 profile / 9 能力、6 条 CM 规则、CI 两轴与 6 组合）；`scripts/verify-compatibility-matrix.mjs` 零依赖静态守门（比对 pom 版本、枚举分支、能力注解、compose 镜像、CI 两轴与笛卡尔积）；`CompatibilityMatrixProbeTest` 5 例消费 CI 注入的组合；CI 新增 `compatibility` job（3 模式 × 2 profile）；`docs/compatibility-matrix.md` | JDK 21 仅列 `javaPlannedUnverified`（本机只有 8/11/17，按 CM-06 不进 CI）；四种 profile 完整启动的 Testcontainers 端到端仍归 Docker CI |
| 部署死键核实（第三批遗留） | 已核实并标注 | 确认 `foundation.secret.master-key-ref` 与 `SecretStoreAdapter` 在 Java 代码中**无任何绑定类或接口**，只在 `ParamCipher` / `PersonCipher` 的 Javadoc 设想里；真实生效的是 `foundation.param.master-key`（`ParamProperties`）与 `foundation.org.person.master-key`（`OrgPersonProperties`）。`deploy/README.md` 表格与部署面说明、`deploy/compose.yml` 注释、`replacement-points.md` 已就地标注「配置不生效」，**保留环境变量兼容既有脚本，未改 Java、未删变量** | 是否补 `SecretStoreAdapter` 实现或彻底清理，需设计侧拍板（与 D2 相关）；同类残留 `FOUNDATION_REDIS_PREFIX` / `FOUNDATION_STORAGE_BUCKET_PREFIX` 已一并记录 |
| 文档 | 已交付 | 新增 `docs/compatibility-matrix.md`、`sdk-examples/node/README.md`；回写 `docs/README.md`（导览 §1/§3、新增 §8 第五批、硬约束顺延 §9）、`contracts/README.md`（契约表 + §6 未完成）、`capability-switches.md` §5 过期状态、`runtime-modes.md`、`replacement-points.md` | — |

### 15.3 实施中发现并处理的真实问题

1. **不能发明契约里没有的头**：最初打算在 Node 侧做「时间戳防重放」，核对 `event-schemas-v1.json`
   后发现 v1 只定义 `X-Fdn-Event` / `X-Fdn-Schema-Version` / `X-Fdn-Delivery-Id` 与签名头。
   若自行把 `X-Fdn-Timestamp` 纳入签名串，Java 侧签出的摘要与接入方计算的必然不一致。
   改为按契约做 `eventType + deliveryId` 幂等，并在 README 明确「防重放若确有必要应作为 v2 新增可选头」。
2. **验签必须覆盖原始字节**：Java 侧签的是 `objectMapper.writeValueAsBytes(body)`；Node 侧若先
   `JSON.parse` 再 `stringify` 会改键顺序 / 空白。`verifyWebhook` 直接拒绝传入已解析对象。
3. **矩阵不能空跑**：只写 `strategy.matrix` 而不让测试消费注入值，等于把同一套测试跑 6 遍。
   因此新增 `CompatibilityMatrixProbeTest` 读 `FOUNDATION_COMPAT_MODE` / `FOUNDATION_COMPAT_PROFILE`
   （本机缺省按 `foundation/full`），并在本机把 6 个组合**全部复现通过**后才写进 CI。
4. **profile 轴只取两端**：全量 4 profile × 3 模式 = 12 个 job 收益很低；`iam` / `common` 已由
   `server-profile-domains-v1.txt` 冻结基线与 15 个条件探针逐档守卫，故 CI 只跑 `full` / `minimal`，
   并在契约的 `ciMatrix.note` 写清理由，避免被误读为「漏测」。
5. **本机工具事实决定 CI 范围**：本机只有 JDK 8/11/17，但有 Node 22。故 Node 自检本机可跑并进 CI；
   JDK 21 只写成待验证、不进矩阵（CM-06），避免制造无法复现的假红。
6. **工具与用例层面的两处小坑**：`node --test <目录>` 在 Windows 下把目录当模块解析，改为显式文件；
   Node 首跑 17/18，失败源于测试用例把空对象当成默认 envelope 传入，修参数后 18/18。
7. **文档状态漂移**：`capability-switches.md` §5 仍写着「运行模式未完成、兼容矩阵待做」，是第三批时点的
   过期结论；连同 `docs/README.md` 的「兼容矩阵进行中」一并回写，避免接入方按过期文档判断。

### 15.4 验证结果

- JDK 17 · `mvn -o -q test` 全仓库 **814 个单测 / 138 个测试类，0 失败 / 0 错误 / 0 跳过**
  （第四批 809 + 本批 `CompatibilityMatrixProbeTest` 5 例，surefire 报告确认执行）。
  在改动部署文档之后**又完整复跑一次**，确保读文档的守门测试消费的是最终版本。
- **6 个 CI 组合在本机逐一复现通过**：`foundation/full`（默认）、`foundation/minimal`、
  `local/full`、`local/minimal`、`self-managed/full`、`self-managed/minimal`。
- Node 22 · `node --test sdk-examples/node/test/client.test.mjs` **18/18 通过**；
  `node scripts/verify-compatibility-matrix.mjs` 通过（JDK 17 / Boot 3.2.12 / 锁定栈 5 项 /
  3 模式 · 4 profile · 9 能力 / 6 组合 / 5 条不适配声明）。
- `FoundationContextTest` 正常启动；`api-inventory-v1.txt` 仍 **181 条**、
  `server-profile-domains-v1.txt` 仍 4 行；新增文档跨仓库断链为零。
- 未跑 Testcontainers 类 IT（本机无 Docker，按既有约定归 CI）；GF 12 条闸门用例仍未补齐。

### 15.5 边界与下一步

1. **F1 正由并行会话推进**：BAS-26 第一批 / 第二批已提交到主仓库。本批刻意不与它并行改主仓库。
   待接线收敛后，务必补上 §14.5 第 2 条的**双键一致性校验**：`foundation.client.mode`（starter 侧）
   与 `tioka.foundation.mode`（应用侧）必须由同一环境变量派生，否则「应用以为单飞、starter 仍连中台」。
2. **最高优先级的交付风险不是代码，是 git 状态**：`tioka-foundation` 五批共 **103 个改动未提交**，
   且工作分支是 `fix/pf00a-parent-publishable` 而非 `main`。请尽快决定归属（合并回 `main` 或建 F3
   专用分支提交），并在 F1 接线前重新 `install` —— 本地 m2 的 `foundation-starter-core` 仍是
   不含 F3 改动的旧构建。
3. F3 剩余：自管授权导入器（属 F2）、ADP-10 前端包（依赖 F1 前端包入库）、完整 OpenAPI
   请求/响应字段基线、v2 契约与并行发布期、其余 `GA*GateIT` 的 Docker 端到端部分、
   第二个真实接入方验收、JDK 21 验证。**第 8 节最低放行线（ADP-01/02/04/05）已超额完成**。
4. 第 9 节 D1〜D8 仍待拍板；**D2（适配边界）已被本批的兼容矩阵契约固化**，若将来要解除
   `notAdapted` 中的任一项，必须先改契约并走决策，不能只改代码。

## 16. 版本分轨与 git 收口（2026-11-16）
> **状态：本节为 2026-11-16 上午的历史决策记录，其「不合并、双轨并行」结论已被 §17 取代。**
> 保留原文是为了留存「同一个 GAV 对应两套行为」的取证与方案比较；当前仓库状态以 §17 为准。


### 16.1 问题：同一个坐标对应两套行为

F3 五批完成后，暴露出一个比 git 分叉更隐蔽的风险：`main`（稳定基座）与
`feat/f3-adaptability`（适配性剖面）**共用** `com.foundation:*:0.1.0-SNAPSHOT` 这一坐标，
但行为不同——最典型的 `FoundationMigrationAutoConfiguration`，`main` 版读
`foundation.enabled`，F3 版读 `foundation.data.enabled`。Tioka 主仓库把后者设为 `false`，
因此「下游实际跑哪套语义」完全取决于**最近一次在哪个分支执行了 `mvn install`**，
而 pom 里看不出任何差别。换机器、进 CI、或有人在 `main` 上重装一次，行为就会静默变化。
并行会话已实测：在 `main` 基点 install 后，
`FoundationDataContractTest.capabilitySwitchGatesStarterAutoConfiguration` 直接变红。

### 16.2 决策：方案 C（版本分轨），三条边界同时守住

经代码级取证后，对上一轮提出的 A/B/C 三案选定 **C**：

| 边界 | 做法 | 依据 |
| --- | --- | --- |
| 不污染 | **不把 F3 合进 `main`**；`tioka-foundation` 也不并入 Tioka 主仓库 | 用户明确要求；ADR-051 独立仓库约定 + 主仓库 `.gitignore` 第 21 行。主仓库直接 import 的 7 个 Foundation 类在 `main` 上齐备，F3 新增 134 个文件一个都未被引用 |
| 不丢失 | F3 全部成果提交到专用分支并打 tag、建全分支 bundle；用**独立 worktree** 改 F3，不动共享工作区 HEAD | Foundation 仓库无远程，本地 refs 是唯一副本 |
| 消除歧义 | 两轨使用**不同 Maven 坐标**，下游 pom 显式锁定所需轨 | 行为由坐标决定，与构建顺序无关 |

否决 A（挑类回主干）：能力开关注解会牵出 `CoreAutoConfiguration` → `DeploymentNamespace` →
`FoundationProperties`，越挑越多，等于合并半个 F3。否决 B（F3 成为主干）：直接违背
「不要合并进来污染代码」。`main` 仍接收了纯修复类提交（PF-00a `38aabe0`、发布 pom 回归
`6cca930`），因为下游继承父工程必须依赖它们，且不含任何 F3 适配性代码。

### 16.3 两条版本轨与下游锁定

| 轨道 | 坐标 | 所在分支 | 内容 |
| --- | --- | --- | --- |
| 稳定基座 | `0.1.0-SNAPSHOT` | `main`（`6cca930`） | PF-00a / 发布 pom 回归修复，不含 F3 适配性代码 |
| 适配性剖面 | `0.2.0-adapt-SNAPSHOT` | `feat/f3-adaptability`（版本分轨 `b31a450`，最终 `5124542`） | 稳定基座 + F3 五批全部成果；当时 Tioka 提交锁定此轨 |

Foundation 侧同步 4 处：聚合器、父工程、BOM 的三处 `<revision>`，以及模板工程的 parent 版本；
新增 `tioka-foundation/docs/version-tracks.md` 固化「改版本清单 / 双轨用法 / 远程配套要求」，
`docs/README.md` 与根 `README.md` 加导览入口。Tioka 侧同步 2 处版本源：根 `pom.xml` 的
`<parent>` 与 `tioka-dependencies/pom.xml` 的 `foundation.version`（当前双源，必须同轨）。
Helm 与离线清单里的 `0.1.0` 是镜像 tag、模板自身 `<version>` 是新应用坐标，均未改动。

### 16.4 验证结果

| 项 | 结果 |
| --- | --- |
| Foundation `mvn -o install -DskipTests`（F3 worktree，JDK 17） | exit 0；m2 新增 `0.2.0-adapt-SNAPSHOT` 共 **53 个发布 pom**，版本正确、无 `${revision}` 残留；jar 内含 `FoundationRuntimeMode` / `DeploymentNamespace` / `ConditionalOnFoundation*` / `FoundationApiVersion` |
| 旧坐标保护 | `0.1.0-SNAPSHOT` 仍是原有产物、未被覆盖；两轨在本地 m2 中是不同目录 |
| Tioka 定向测试 `-Dtest='Foundation*Test'` | **18 例全绿**（data 6 + Ids 4 + 双模式 8），0 失败 / 0 错误 |
| Tioka `test-compile -pl tioka-server -am` / `validate` | 均 exit 0；validate 日志无新增告警 |
| 坐标生效实证 | 根 flattened pom 的 parent 为 `0.2.0-adapt-SNAPSHOT`；surefire classpath 实测加载 `foundation-starter-core-0.2.0-adapt-SNAPSHOT.jar` |
| git 状态 | `main` 未混入任何 F3 文件；F3 分支成果完整；两仓库工作树干净、共享工作区 HEAD 未切换 |
| F3 全量回归（行尾修复后） | JDK 17 `mvn -o -q test` **814 例 / 138 测试类，0 失败 / 0 错误 / 0 跳过**；Node 18/18、兼容矩阵守门通过；接口基线仍 181 条、profile 基线 4 行 |
| **本批发现并修复的既有缺陷** | 用独立 worktree 全新检出时，`core.autocrlf=true` 把两份 `.txt` 契约基线转成 CRLF，令 `ApiInventoryContractTest` / `GaModularityGateTest` 在内容完全未变时误报「契约变更」（Linux CI 不暴露，`expected` 与 `but was` 肉眼看相同）。已在 `.gitattributes` 为该目录基线加 `text eol=lf`（`5124542`），blob 与工作树逐字节一致、无内容改动，Windows 全新克隆不再复现 |

**§15.5 第 2 条的「103 个改动未提交」风险已解除**：五批成果已提交在
`feat/f3-adaptability`（`0ac8115` / `ceda814` / `bba1b0f` / `b31a450` / `5124542`），
36 号文档与版本锁定已提交到主仓库，并有 5 个 tag 与
`.workbuddy/backups/foundation-20261116.bundle` 全历史备份（含 `5124542`、
`refs/heads/feat/f3-adaptability` 与 `main`）。

### 16.5 后续顺序（本批未做）

1. **配远程并分别 deploy 两轨**：Foundation 目前无远程，双轨只在本地 m2 有效；这是分轨方案的
   配套项而非可选项，否则 CI / 其他机器无法解析 `0.2.0-adapt-SNAPSHOT`。
2. **双键一致性校验**：`foundation.client.mode`（starter 侧）与 `tioka.foundation.mode`
   （应用侧）由同一环境变量派生并加启动期断言，避免「应用以为单飞、starter 仍连中台」。
3. **F1 债务清零**：`foundation-ui` 空壳、GF 12 条闸门用例缺 11 条、BAS-20 / 28 / 22 收尾；
   该工作由并行会话推进，本批刻意未触碰其业务代码。
4. F3 剩余项与 §15.5 第 3 条一致；第 9 节 D1〜D8 仍待拍板（D2 已由兼容矩阵契约固化）。
5. 将来若决定合并两条版本轨，`revision` 行必然冲突，须先决定保留哪个版本号并同步下游两处声明。

---

> 本文只做规划与适配性约束，不替代 S15 的功能设计与 ADR 的决策地位。任何与 ADR-029 定案冲突的建议，以定案为准；如采纳本文 P3 边界，请走 ADR-030 变更流程落定。

## 17. 单轨收敛与仓库整理（2026-11-16 下午）

用户明确「不建议双轨」，并要求整理代码。只读盘点后按三项决策执行：合并 F3 进 `main`、
统一版本号 `0.2.0-SNAPSHOT`、清理临时产物并收敛分支；同时同步矛盾文档，
把重复概念固化为机器可校验的能力归属清单。

### 17.1 执行结果

| 项 | 结果 |
| --- | --- |
| 合并 | `af5fd6c`：`git merge --no-ff feat/f3-adaptability`，145 文件 / +7889 行；唯一冲突为 `foundation-dependencies/pom.xml` 的 `<revision>` |
| 版本统一 | `585ae33`：Foundation 4 处 + Tioka 2 处坐标统一 `0.2.0-SNAPSHOT`，删除 `-adapt` 后缀 |
| 保护 | tag `pre-single-track-20261116` 与 `.workbuddy/backups/foundation-pre-single-track-20261116.bundle`（完整历史，含 F3 HEAD） |
| 仓库整理 | 顶层 41 个临时日志与生成物归档到 `.workbuddy/archive/logs-20261116/`；冗余分支与 worktree 收敛为一份 `main` 检出 |
| 文档 | Foundation `docs/version-tracks.md` 重写为单轨约定、`runtime-modes.md` §4 改为同源派生；本文 §16 标注历史，`module-dependencies.md` §7.6 / §7.7 回写 |
| 验证 | Foundation 140 类 / 827 例全绿、Node 22/22、兼容矩阵通过；Tioka `validate` / `test-compile` / `Foundation*Test`（22 例）均 exit 0；能力归属守门 70 项通过 |
| 边界 | 不配远程、不 deploy、不改 CI 发布链路；不动 Tioka 业务代码，不变更 ADR-029 第 60 条 Workspace 定案；主仓库 `main` 分叉（并行会话）未触碰 |

### 17.2 与 §16 的关系

§16 的取证（同一 GAV 两套行为、并行会话实测用例变红）仍然成立，是本次收敛的动因；
但「不合并、双轨并行」的结论与 §16.5 第 1、5 条已被本节取代：合并已完成，
远程发布与「将来合并两轨」不再列为待办。

### 17.3 能力归属（重复概念）

「清理重复概念」按零删除落地：产出能力归属清单与机器守门脚本，把两套 `PrincipalHolder` /
`RequirePermission`、SCIM 同步、Workspace 与组织树的重叠显式登记并冻结，防止向派生应用扩散。
直接删除会破坏功能且需变更 ADR-029，本批不做。清单见 `docs/architecture/capability-ownership.md`，
守门脚本见 `scripts/verify-capability-ownership.mjs`。

## 18. 身份权限双实现与权限码映射（2026-11-16 晚）

用户明确「Foundation 是整个 Tioka 及后续业务应用的中台底座」，并延续上一批的边界：
只做本地代码逻辑、不配远程、不 deploy、不碰 Tioka 业务行为、不变更 ADR。
本批把「权限从哪里来」从切面里抽出来，是底座接线的第一处实质改造。

### 18.1 落地内容

| 项 | 内容 |
| --- | --- |
| 判定端口 | `PermissionEvaluator.firstMissing(...)`，返回第一个缺失权限码，沿用既有 403 与 DENIED 审计 |
| 数据源端口 | `AuthorizationSnapshotSource.permissionsFor(personId)`，框架层不绑定任何中台 SDK |
| 本地实现 | `LocalPermissionEvaluator`，逐字等价改造前 `Principal.hasPermission`，`local` 零行为变化 |
| 接入实现 | `FoundationPermissionEvaluator`，先映射后判定，绝不读取本地权限集，数据源不可用一律拒绝 |
| 映射契约 | `permission-code-mapping-v1.json` 显式登记 37 个权限点，与 `Permissions.ALL_CONCRETE` 逐项一致 |
| 装配 | `FoundationModeConfiguration` 按模式互斥装配；接入模式缺数据源或 Spring `ObjectMapper` 时启动期拒绝 |
| 守门 | `scripts/verify-permission-code-mapping.mjs`（36 项）+ 3 组契约单测 + `FoundationModeSwitchTest` 扩展 |

### 18.2 关键逻辑约束

Tioka 的 `*` 是超管、`agent:*` 是通配，而 Foundation 授权快照只做精确匹配。
若把中台返回的 `*` 直接灌进当前主体权限集，普通用户会被静默提升为超管。
因此契约规定：含 `*` 的中台码全部丢弃；未登记码忽略；`superAdminCodes` 默认空，
中台无法直接授予 Tioka 全量权限。接入模式还禁止回落本地权限集，避免本地残留授权绕过中台。

### 18.3 验证结果

| 项 | 结果 |
| --- | --- |
| `tioka-framework` 全量单测 | 69 例全绿（新增映射 11 例、双实现 11 例、模式装配扩展） |
| 全仓 `test-compile -pl tioka-server -am` | exit 0 |
| 全仓架构规则 `*ArchitectureTest` | 27 例全绿（ENG-05 的 `new ObjectMapper()` 违规已改为 Spring 注入） |
| `PermissionAspectUnitTest` | 4 例全绿：放行 / 403 / DENIED 审计 / 公开接口 |
| 两个守门脚本 | 映射 36 项、能力归属 70 项，均 0 失败 |

权限 IT（`PermissionAspectIT` / `G1PermissionGateIT`）依赖 Testcontainers，本机无 Docker 环境，
属既有环境限制；本批用不依赖容器的切面单测覆盖同一段切面逻辑。

### 18.4 仍未完成（显式登记，不冒充已完成）

1. `AuthorizationSnapshotSource` 的**真实中台适配器**已由第 19 节（F4）接线，
   `foundation` 模式不再启动期拒绝；F2 剩余范围（应用间授权 / 卡片注册 / 多源同步）仍未做。
2. 两套同名 `PrincipalHolder` / `RequirePermission` 未合并，`Workspace` 与组织树映射未做，
   模块拆分与 `tioka-ai` 派生仍待立项；本批零删除。
3. 能力归属清单 `adapterRequired` 中「身份上下文适配」「权限码映射」「授权快照接入」已标为 `done`，
   「Workspace 与组织树映射」仍为 `pending`。

## 19. 授权快照接入链路打通（2026-11-16 晚，F4 第一处真实接线）

用户选择「打通接入链路」后，按「只做本地代码实现与逻辑准确、不配远程、不 deploy」推进。
目标不是再补一个接口，而是让 `foundation` 模式从「启动期拒绝」变成「能运行且全程 fail-closed」。

### 19.1 为什么改两个仓库

`foundation-starter-security` 里的 `AuthzSnapshotClient` 属于服务端安全 starter，引入它会装配
`PrincipalAuthenticationFilter` 并把 aop / okhttp / nimbus 传给全部业务模块，与 Tioka 本地认证冲突
（第 18 节已刻意不引）。因此正确做法是由底座提供一个**不依赖安全 starter 的只读客户端**，
派生应用只做端口适配——这也是「第二个应用能照着接」的前提。

### 19.2 落地内容

| 仓库 | 文件 | 作用 |
| --- | --- | --- |
| Foundation | `AuthorizationSnapshotClient` / `HttpAuthorizationSnapshotClient` | 调 `GET /api/fdn/apps/{appCode}/persons/{personId}/snapshot`，只交出原始权限项 |
| Foundation | `FoundationClientAutoConfiguration` | 仅在 FOUNDATION 模式注册客户端；并把 `BearerTokenSupplier` 注入 `FoundationHttpClient`（原先传 null） |
| Tioka | `tioka-server/pom.xml` | 只加 `foundation-starter-client`，不引 security starter |
| Tioka | `FoundationAuthzConfiguration` | 按 `tioka.foundation.mode=foundation` 注册 `AuthorizationSnapshotSource`，缺底座客户端启动期拒绝 |
| Tioka | `FoundationAuthorizationSnapshotSource` | 纯转发适配器，不改权限码、不缓存、不读本地权限集 |
| Tioka | `application.yml` | 补服务账号凭据三个环境变量，并更新「切换前请勿开启」的过期注释 |

`HttpAuthorizationSnapshotClient` 的严格校验：响应必须是对象；`personId` / `appCode` 必须与请求一致；
`permissions` 必须是文本数组；任一不满足返回 null，由 `FoundationPermissionEvaluator` 按 fail-closed 拒绝。
`personId` 做 URL 编码，`app-code` 按 `^[a-z][a-z0-9-]{1,31}$` 校验，避免路径注入。
空数组是「中台明确授予零个权限」，与「取不到数据」严格区分。

### 19.3 验证结果

| 项 | 结果 |
| --- | --- |
| Foundation 全量 `mvn -o test` + `install -DskipTests` | RC=0（新增客户端 10 例、`RuntimeModeAssemblyTest` 7 例） |
| Tioka `FoundationAuthorizationChainTest` | 10 例全绿，本地 HTTP 桩走完整链路 |
| Tioka `FoundationAuthzConfigurationTest` | 4 例全绿：local 不注册、缺客户端拒绝、接入模式不再拒绝启动 |
| 覆盖的失败路径 | 500/404、空响应、结构非法、错人/错应用快照、版本错配不重试、本地超管权限集不参与判定、中台通配码不提升 |

`foundation` 模式现在能启动并完成判定；所有快照异常都走既有 403 与 DENIED 审计路径，不新增放行分支。

### 19.4 边界与仍未完成

- 不缓存授权快照：本切片每次判定回源，避免陈旧授权放行；缓存与版本失效属后续优化。
- 服务账号凭据未配置时中台端点会拒绝请求，链路保持 fail-closed；未把「必须有令牌」做成启动期硬校验，
  以免误伤网关注入令牌的部署形态。
- 不配远程、不 deploy、不改 CI 发布链路；`local` 模式零行为变化。
- 仍未做：Workspace 与组织树映射、两套同名身份类合并、模块拆分与 `tioka-ai` 派生、
  F2 剩余能力（应用间授权 / 卡片注册 / 多源同步）、F1 债务。

## 20. 接入平面产品化（2026-11-16 晚，P0）

用户要求后续设计与设想都从平台、中台角度出发。以 ADR-051 三条定案复核 F4 后确认：
第 3 条「令牌只带身份、应用取快照」已对齐，但权限点未走平台清单登记；
第 4 条「接入靠 SDK 与契约、六项自检」与第 5 条「Foundation 不在请求热路径」尚未落实。
更关键的是**平台资产长在业务应用里**：映射契约随 `tioka-framework` jar 分发、接入样板写在
`tioka-server`、守门脚本在主仓库、判定端口在 Tioka framework。第二个应用要接入就得复制适配类
并反向依赖第一个业务应用——这是分叉，不是平台。

同时核实到一个既有事实：`foundation-starter-security` 已含 `SnapshotCache`（版本化缓存）、
`AuthzChangedListener`（事件失效）与 `PermissionAspect`（注解判定）。因此 P0 不是再造一套，
而是把「标准路径 + 轻量只读路径」变成可自助接入、可自检、契约归平台的产品；缓存与事件失效
的接入验证属 P1，不在本批重复实现。

### 20.1 平台侧：接入护栏与自检包

`foundation-starter-client` 新增 `integration` 包与 `FoundationIntegrationAutoConfiguration`：

- 静态护栏只在 `FOUNDATION` 模式且 `client` 能力开启时激活，只拒绝「显式配置但非法」的
  `foundation.app-code` / `foundation.base-url`；app-code 缺省不拒绝，避免误伤 Foundation 自身
  模块与存量接入方；`local` / `self-managed` 一个 Bean 都不装配。
- `FoundationIntegrationSelfCheck` 提供六项自检：应用编码登记、基座地址、服务账号凭据、
  快照往返、判定链路 fail-closed、快照失效钩子（`SnapshotInvalidationHook` 可选 SPI）。
  自检**按需调用**，启动期不发任何网络请求；`WARN` 不阻塞接入结论（网关注入令牌、P1 事件失效
  待接），只有 `FAIL` 才算接不上。
- 自检器不依赖任何业务应用端口，也不引 `foundation-starter-security`，任意派生应用可直接复用。

### 20.2 契约归位

平台新增 `tioka-foundation/docs/contracts/permission-code-mapping-v1.schema.json`
（`$id` = `urn:foundation:contract:permission-code-mapping:v1`），冻结 `additionalProperties: false`
与三条策略 const；应用实例改用**应用无关**的 `applicationPermissionCodes`（原
`tiokaPermissionCodes` 绑定第一个业务应用名），并新增 `platformContract` 声明遵守平台契约。
Tioka 运行时行为不变：`PermissionCodeMapper` 只换了读取的 JSON 键名，映射规则、37 个权限点、
fail-closed 语义逐字未动。

`tioka-foundation/scripts/verify-app-integration-contract.mjs` 与主仓库
`scripts/verify-permission-code-mapping.mjs` 双向守门：schema 自身、实例字段集合、策略 const、
权限码格式、别名目标、应用侧实现不得改回旧键或放宽策略，以及「平台 schema 不得绑定第一个
应用名称」「第二接入方骨架不得依赖 `com.tioka`」。

### 20.3 第二接入方验收

`foundation-it-example` 作为最小骨架，只加 `foundation-starter-client`（test scope）+ 配置，
不引 security starter、不依赖任何 Tioka 类。`SecondAppOnboardingTest` 用
`ApplicationContextRunner` 证明：自动装配出接入自检器与快照客户端、六项自检通过；
非法 app-code / base-url 启动期拒绝；本地模式零装配；自检按需执行，启动期零 HTTP 调用。
这是第一次给出「第二个应用零改中台代码即可接入」的可复现证据。

### 20.4 验证与边界

| 项 | 结果 |
| --- | --- |
| `FoundationIntegrationSelfCheckTest` | 12 例全绿（含 null 快照、快照异常、判定器放行 null、告警不阻塞等） |
| `SecondAppOnboardingTest` | 6 例全绿（零代码接入、无 Tioka 类、启动期护栏、本地模式零装配、按需自检） |
| 三个守门 + 兼容矩阵 | 权限映射 67 / 能力归属 87 / 平台契约 42，兼容矩阵通过 |
| Tioka 定向 | 映射、判定、模式互斥、切面 4 类 RC=0，字段改名零回归 |

**边界**：不配远程、不 deploy、不改 CI 发布链路；未动 Tioka 业务行为、未删任何类或表、
未变更 ADR-029；`local` 模式零行为变化。P0 刻意不实现快照缓存与事件失效（P1），
不做应用间授权 / 卡片注册 / 能力目录注册表（P2），不拆 `tioka-ai`（P3）。
两项待定决策（Tioka 是否退化为纯派生壳、Workspace 是否整体废止）仍不阻塞 P0/P1，只阻塞 P3。

## 21. 运行平面：中台退出请求热路径（2026-11-16 夜，P1）

P0 让第二个应用能接上，但每次鉴权仍同步回源，ADR-051 第 5 条未落实；单应用时这是延迟，
N 个应用时中台即同步单点。复核确认标准路径（`foundation-starter-security`）早已有
`SnapshotCache` / `AuthzChangedListener`，缺的是轻量只读路径（`foundation-starter-client`）
的同一能力，以及三项单应用债务。本批继续从「平台的接入面 / 控制面 / 运行面是否成立」排期，
不按「Tioka 还缺什么」补功能；Tioka 侧零改动。

### 21.1 版本化缓存与事件失效

`HttpAuthorizationSnapshotClient` 新增 `snapshotFor()`，严格校验 `version` 为非负整数并保留它；
`permissionsFor()` 退化为它的窄视图，既有接入方零改动。平台新增 `AuthorizationSnapshotCache`：
按 `appCode + personId` 分键，命中未过期快照零回源；`ConcurrentHashMap.compute` 按 key 串行回源，
顺带避免缓存击穿；不引入 synchronized / ReentrantLock（48-CS §4）。

三条不可放宽的语义：① 过期且回源失败（null / 异常）一律返回 `null` 并删除条目，**无过期放行分支**；
② 回源版本小于已缓存版本（从库延迟、乱序、旧节点）视为不可用，避免权限回退；
③ 回源结果的 `personId` / `appCode` 必须与请求一致，防网关串号。

`fdn.authz.changed` 经 `AuthorizationSnapshotInvalidationListener` 解析（MQ body 与 Webhook
envelope 两种形态都兼容），按 appCode / personId 精确失效；JSON 非法或缺关键字段**抛异常让通道
重试**，绝不静默吞掉——吞掉失效事件等于让缓存一直用旧授权。`FoundationClientAutoConfiguration`
自动装配缓存化 `AuthorizationSnapshotClient`、把 `SnapshotInvalidationHook` 指向缓存、注册事件入口；
自检第 6 项在真实接入中自动 PASS。`foundation.client.authz.cache-ttl=0s` 可退回每次回源排障。

### 21.2 三项契约缺口

| 缺口 | 修复 |
| --- | --- |
| MQ body 无 `deliveryId`，`IdempotentConsumer` 按 MQ `messageId` 去重，中继重试会重复处理 | `MybatisOutbox` 把 outbox 行主键写入 payload 保留字段（强制覆盖业务同名字段）；`WebhookEventSink` 校验并移除 payload 内重复字段；新增 `EventDeliveryKeys` / `DeliveryAwareIdempotentConsumer` 按 `eventType + deliveryId` 去重，存量消费者默认行为不变 |
| Webhook 有签名与 deliveryId，但无时间窗防重放 | envelope 顶层新增**可选** `sentAt`（epoch millis），位于 HMAC 覆盖的 `raw_body` 内不可篡改；不新增头、不改签名串，v1 接入方忽略即可。Node SDK 新增 `assertReplayWindow`，验签 → 时间窗 → `eventType + deliveryId` 幂等构成完整防重放，老中继无 `sentAt` 时明确返回「未启用」而非抛错 |
| `DegradeContext` 纯 ThreadLocal，异步任务的降级标记主线程读不到 | 新增 `Snapshot` / `capture()` / `restore()` / `wrap()` / `call()`，父子线程共享快照且子线程 `mark()` 回写；`DegradeHeaderFilter` 在入口绑定快照，`clear()` 仍按线程清理 |

事件契约 `event-schemas-v1.json` 主版本仍为 1、envelope 必需字段未增删，属兼容增补：
`mqChannel.reservedFields`、`webhookChannel.envelopeOptionalFields` 与 `replayProtection` 说明。

### 21.3 验证与边界

| 项 | 结果 |
| --- | --- |
| 平台缓存 / 事件 / 客户端单测 | `AuthorizationSnapshotCacheTest` 10 例、`AuthorizationSnapshotInvalidationListenerTest` 6 例、`HttpAuthorizationSnapshotClientTest` 补 2 例 |
| 契约缺口单测 | `DegradeContextTest` 6 例、`MybatisOutboxTest` +1、`WebhookEventSinkTest` +3、`IdempotentConsumerTest` +3 |
| 第二接入方骨架 | `SecondAppOnboardingTest` +2：事件驱动失效、陈旧快照绝不放行；平台自动装配出缓存与钩子 |
| Node SDK | 25 例全绿（含 3 例防重放） |
| 守门 | 能力归属 112 / 权限映射 69 / 平台契约 64，兼容矩阵通过 |

**边界**：不配远程、不 deploy、不改 CI 发布链路；未动 Tioka 业务行为、`local` 模式零装配、未删任何
类或表、未变更 ADR-029。缓存刻意不提供过期放行开关，不与 `foundation.client.degrade.*` 白名单挂钩
（`policyFor("authz")` 恒为 `FAIL_CLOSED`）。P2 再做能力目录注册表、破坏性变更守门、应用间授权与
门户卡片；P3 拆 `tioka-ai` 时应是「配置接入」而不是复制一套权限。

> 注：本节的「不提供过期放行开关」在 §22 已修正——缓存改为消费 `policyFor("authz")` 注入的策略，
> 默认仍是 `FAIL_CLOSED`（行为不变），但代码保留了 D4 拍板后的唯一改点。

## 22. 第一性原理复盘：三出口矛盾与永久红的门禁（2026-11-16 夜）

本批不是新功能，而是对 P0/P1 产出的**自我反查**。触发点是用户问「后续处理是否遵循第一性原理」，
按该原则复核后发现三条自己制造的矛盾，全部有代码证据。

### 22.1 三条被反查出来的矛盾

| # | 事实 | 根因 | 处置 |
| --- | --- | --- | --- |
| ① | `application.yml:55` 与 `:200` 均为 `${TIOKA_FOUNDATION_MODE:local}`，而 `FoundationIntegrationAutoConfiguration` 是 `@ConditionalOnFoundationRuntimeMode(FOUNDATION)` | P0 接入护栏、六项自检与 P1 版本化缓存在 Tioka 默认部署下**一个 Bean 都不装配**，正确性证据只有平台自身单测与骨架，没有真实流量 | 未修（属产品决策）：登记为「Foundation 模式缺真实运行时验证入口」，见 22.4 |
| ② | 同一降级语义有**三个**决策出口：`Degrade.policyFor("authz")`（starter-client，恒 FAIL_CLOSED）、`FoundationAuthzProperties.isStaleAllowed()`（starter-security，可 stale）、P1 硬编码 `return null`（轻量缓存）；而 `degradation.md` §2 声称 `policyFor` 是「唯一判定出口」、§1 规则 2 又说 authz 可配 stale 放行 | 我 P1 新增缓存时**没有复用平台已有的降级出口**，而是自带第三个决策；文档三处自相矛盾 | 已收敛轻量路径（见 22.2）；标准路径归 D4 管辖，刻意不动 |
| ③ | `CedarEvaluatorTest:25` 的 `static final PolicyEvaluator EVALUATOR = new PolicyEvaluator()` 触发 cedar-java JNI 绑定 → 测试类 `<clinit>` 失败 → 5 例全 error 并连带污染 `CharterCompilerTest` 2 例 → `mvn -fae test` **永久 RC=1**，`tioka-server` 被上游失败 SKIPPED | 把「本机缺原生库」这一环境限制表达成了代码失败；一个永久红的门禁等于没有门禁，我连续多轮用口头标准「失败集合未扩大」替代确定信号 | 已修（见 22.3） |

### 22.2 ②的修复：降级决策收敛为单一真相源

轻量路径 `AuthorizationSnapshotCache` 不再自带决策，改为**消费注入的 `DegradePolicy`**，
装配层传入 `Degrade.policyFor("authz")`：

- 默认行为**逐字不变**：`authz` 不在降级白名单，`policyFor` 恒返回 `FAIL_CLOSED`，过期即拒绝，
  接入方无任何配置可放宽。
- 代码保留 `STALE_CACHE` 分支作为 D4 拍板后的**唯一改点**：若允许授权降级，只需把 `authz`
  加进 `foundation.client.degrade.capabilities`，本类零改动即生效。
- 两条与降级策略**解耦**的硬拒绝：版本回退、身份串号——数据错误不等于「中台不可用」，
  任何策略下都拒绝，与标准路径对 `BizException` 不降级的处理一致。

**刻意不动标准路径**：`foundation-starter-security` 的 `fail-mode=stale` 是**待决策 D4**
（授权是否允许 fail-open）的管辖对象，D4 在 36 号规划第 9 节仍属「待负责人决策」，
代其拍板属越权决策。已在 `degradation.md` §2.1 用表格如实记录「两个决策出口并存」为已知欠账，
并写明两种拍板结果下各自的改动点。

### 22.3 ③的修复：让回归信号恢复可判定

新增测试专用 `CedarNativeSupport`：用**字符串反射**（而非 import）触发
`com.cedarpolicy.BasicAuthorizationEngine` 的 `<clinit>` 探测原生库，避免给测试源码引入编译期
Cedar 依赖（ArchUnit `cedarOnlyInGovernance` 要求只有 `PolicyEvaluator` 依赖 cedar-java）。

- `CedarEvaluatorTest`：eager 字段改为 `@BeforeAll` + `assumeTrue` 惰性构造 → 整类按 assumption 跳过。
- `CharterCompilerTest`：**不用类级 `@BeforeAll`**，只让「编译 → 求值联动」2 条用例受原生库影响，
  其余 10 条纯编译产物断言在任何机器上都必须继续执行。
- 未用 `@Disabled`：装好原生库后用例自动恢复，不是长期屏蔽。

结果：`CedarEvaluatorTest` 跳过、`CharterCompilerTest` 12 例中 2 skipped / 10 通过，
**BUILD SUCCESS，RC=0**。IT（`PolicyModeIT` / `ChainForbiddenIT`）被 surefire `**/*IT.java` 排除，
不受影响，改动保持最小。

### 22.4 后续每批的三条硬判据（本批起生效）

1. **消费方先行**：开工前必须回答「这个能力当前谁在消费」。若答案是「未来的第二个应用」，
   则该应用的骨架或 IT 必须先存在，否则不写生产代码。
2. **语义不得并存**：新增配置键/降级策略前，先查平台是否已有同类语义；有则统一，不许两套并行。
3. **回归信号必须可自动判定**：RC≠0 不允许用「失败集合未扩大」收尾——要么修根因，
   要么让门禁自动区分已知环境限制（Assumptions / 条件跳过）。

①的遗留：Foundation 模式至今没有一条真实运行时验证入口（Tioka 默认 `local`），
平台能力的证据仍停在单测与骨架层。这是「消费方先行」判据下最该补的一块，建议列为 P2 前置。

### 22.5 修好 ③ 之后立刻暴露的两个既有缺陷（判据③的价值实证）

③ 修复让 `tioka-server` 不再被上游失败 SKIPPED，该模块**多轮以来第一次真正跑完**，
随即暴露 1 Failure + 9 Errors。两者都**不是本批引入**，而是长期被 SKIPPED 掩盖的既有缺陷——
这正好证明「永久红的门禁 = 没有门禁」：上游一红，下游所有真实失败都被静默吞掉。
按判据③，不允许用「既有问题、失败集合未扩大」收尾，两者均修根因：

| 现象 | 根因 | 修法 |
| --- | --- | --- |
| 9 Errors：`RuntimeApiContractTest` 全部 `ExceptionInInitializerError` ← `Could not find a valid Docker environment` | **命名违反本仓分层契约**：该类经 `IntegrationTestSupport` 启动 Testcontainers（PG + Redis），本质是集成测试，却命名为 `*Test`。本仓契约是 `*Test` = 离线单测（surefire）、`*IT` = 需容器（surefire `**/*IT.java` 排除、failsafe 执行），**99 个同类都叫 `*IT.java`，只有它例外**，于是被 surefire 抓进 `mvn test`，在无 Docker 的机器上必然失败并把整个模块判红 | `git mv` 为 `RuntimeApiContractIT.java`（类名同步、补 javadoc 说明命名理由）。CI 的 `mvn -B verify` 由 failsafe 执行本类，**覆盖未减少**。刻意**不**给 surefire 加 `excludedGroups=it`：那会让「文件名」与「标签」两套机制决定同一件事，属判据②禁止的语义并存 |
| 1 Failure：`PersistenceArchTest.applicationAndKernelMustNotDependOnMappers` 3 处违规 | 真实架构违规：`StructuredOutputStatsService`（`runtime.application.metrics`）直持 `RuntimeItemStatsMapper`，违反「application/kernel 只能依赖 Repository，禁止直持 Mapper」（SCH-10 引入时未被发现，因该模块测试当时未真正执行） | 按本仓既有惯例（`GovPolicyRepository`、`ItemRepository`、`TrustActionRepository` 同构）新增 `infrastructure/RuntimeItemStatsRepository`（`@Repository`，纯转发，聚合语义仍留在 `mapper/*.xml` 不落两处），service 改注入 Repository。**不改 ArchUnit 规则、不加豁免** |

新增守门把分层命名契约固化为自动检查（`verify-capability-ownership.mjs`，117 → **118** 项）：
扫描 `tioka-server/src/test` 与 `tioka-modules`，凡含 `@Test` 且引用容器基建
（`IntegrationTestSupport` / `TestContainers.register` / `@IntegrationTest`）的类，
文件名必须以 `IT.java` 或 `E2E.java` 结尾；排除 `support/` 测试基建自身。

**守门可证伪验证（含一次自我纠错）**：首次实现时把「注入违规后守门应 FAIL」的反向验证
当作走过场，结果抓到的 3 条 FAIL 全是 Workspace 渗透度计数（临时副本含 Workspace 字样），
命名守门**并未命中**——排查发现 Windows 上 `walk()` 返回的路径分隔符是 `\`，
`p.includes('/src/test/')` 恒为 false，使该断言退化成**空集合恒真通过**（即 P0 批次出现过的
「假守门」）。改为先 `rel(p)` 归一化再比较，重做反向验证：注入 `RuntimeApiContractTest.java`
后报 `114 passed / 4 failed` 且明确点名该文件，删除后恢复 `118 passed / 0 failed`。
教训：**守门必须先证明它会失败，才算存在**；这条已写入项目级 `MEMORY.md`。

### 22.6 守门与验证

守门脚本新增断言把三条判据钉死，并做了**反向验证**（临时注入违规代码后守门确实 FAIL，
还原后恢复全绿），证明守门可证伪而非假通过：

| 守门 | 项数 | 新增断言 |
| --- | --- | --- |
| `verify-app-integration-contract.mjs` | 64 → **70** | 缓存消费注入策略而非自行决定、不解析任何降级配置键、缺省构造器一律 FAIL_CLOSED、陈旧兜底仅在 STALE_CACHE 可达、版本回退与身份串号与策略解耦、装配层注入 `policyFor("authz")` |
| `verify-capability-ownership.mjs` | 112 → **118** | 应用未自行放宽降级白名单、平台白名单不含 authz/security、Cedar 测试不在字段初始化处构造求值器、`CedarNativeSupport` + `assumeTrue` 存在、未用 `@Disabled` 长期屏蔽、**依赖容器的测试类必须命名为 `*IT.java`** |

反向验证均实际执行：注入 eager 构造 `PolicyEvaluator` → 守门报 `116 passed / 1 failed`；
注入 `RuntimeApiContractTest.java`（违规命名副本）→ 报 `114 passed / 4 failed` 并点名该文件；
还原后两者均恢复 `118 passed / 0 failed`。其中命名守门首版因 Windows 路径分隔符
（`\` vs `/`）退化为空集合恒真，经反向验证暴露并修复，详见 §22.5。


---

## 23. 账号域装配顺序缺陷（2026-11-25，由 GF 闸门暴露并修复）

补 `GfAccountLifecycleGateIT`（子仓 PR #12）时，CI 首轮报
`UnsatisfiedDependencyException: No qualifying bean of type 'com.foundation.account.api.AccountApi'`。
排查确认这是**真实的生产装配缺陷**，不是测试写错。

### 23.1 根因

`AccountAutoConfiguration` 的 `@ConditionalOnBean` 要求 5 个 bean，其中
**`StringRedisTemplate` 由 Spring Boot 的 `RedisAutoConfiguration` 提供**（Foundation 自己不提供），
但它的 `@AutoConfigureAfter` 只列了 `FoundationDataAutoConfiguration` 与 `CommonAutoConfiguration`，
**没有列 `RedisAutoConfiguration`**。而 `@ConditionalOnBean` 是**顺序敏感**的：

> 若账号域先于 `RedisAutoConfiguration` 被评估 → `StringRedisTemplate` 尚未注册 → 条件不满足 →
> **整个账号域静默不装配**（开通 / 停用 / 每日对账 / 离职联动 / 应急启用全都没有 bean），
> 且失败形态是「No qualifying bean of type AccountApi」，**完全指不到真因**。

**唯一性核实**（避免误判为普遍问题）：全仓只有 `AccountAutoConfiguration` 在 `@ConditionalOnBean`
里要求 Spring Boot 提供的 bean。对比 `NotifyAutoConfiguration` 要的是 `PersonApi` / `GrantApi`
（Foundation 自己的 bean，已被其 `@AutoConfigureAfter` 覆盖），故**只有账号域有此暴露**。

### 23.2 为何此前从未被发现（这条比缺陷本身更值得记住）

- gate job 里**只有本闸门**把账号域拉进 Spring 上下文并注入 `AccountApi`；
- `GfSmokeIT` 只注入组件扫描的 `KeycloakAdminClient`，不依赖账号域装配；
- 全仓**从未有 IT 端到端调过 `/api/accounts`**（实测 `api/accounts` 只在 `AccountCreateCmd`
  与 `AccountApplicationService` 自身命中，无测试引用）；
- 账号域的单元测试全用内存 stub，不启动 Spring 上下文，故装配顺序问题对它们完全不可见。

→ **账号域的装配从未被任何测试验证过**。这是「闸门覆盖」与「单测覆盖」的本质差别：
单测验算法，闸门验装配与真实中间件往返。缺陷能潜伏至今，正因为缺一层覆盖，而非覆盖不足。

### 23.3 修法与边界

`@AutoConfigureAfter` 补 `"org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"`
（Spring Boot 3.2.12 类名，与兼容矩阵锁定基线一致）。

- **最小改动 +1 行注解**，不改行为语义；
- **不动 `@ConditionalOnBean` 门槛**——该门槛本身是正确的：账号域确实需要 Redis 做
  对账单实例锁（`AccountReconcileLock` 基于 `SET NX EX`）与应急启用存储（`EmergencyEnableStore`）；
- 不改对外契约与接口签名，故**不涉及 ADR-030 变更流程**；
- 影响面：让账号域在 Redis 已装配的正常部署下**稳定装配**（此前依赖自动配置评估顺序，属未定义行为）。

### 23.4 顺带确认的一项：`USERNAME_MISMATCH` 是防御分支，不是缺陷

闸门初版试图用 `keycloak.setUsername(...)` 人为制造登录名漂移，实测必然失败：
`HTTP 400 error-user-attribute-read-only`。按「测试里会出问题 ≠ 生产里是缺陷」核实后确认**不可达**：

- **Keycloak 侧**：测试 realm（`deploy/init/foundation-realm.json`）没有 `editUsernameAllowed`，
  缺省 `false`（username 只读）→ 任何人都改不了 username；
- **Foundation 侧**：`fdn_account.login_name` 的 DDL 注释是「全局唯一，**不回收**」，
  `Account` domain 只有 `withStatus` 与 `withKeycloakUserId`、**没有 `withLoginName`**，
  `AccountApplicationService` 也无改名入口。

两侧都不可变 ⇒ 该漂移无法产生。故 `AccountReconcileJob.applyFix` 的 `USERNAME_MISMATCH` 分支是
为「万一 realm 开了 `editUsernameAllowed`」准备的**防御分支**。用「人为制造真实环境不可能出现的
状态」去测它，只会得到仅在测试里成立的断言，故闸门改为只造两类**真实可达**的漂移
（`ENABLED_MISMATCH`、`REQUIRED_ACTIONS_MISMATCH`），该分支的算法仍由
`ReconcileDiffTest.detectsEnabledAndUsernameAndRequiredActionsDrift` 覆盖。

> **若将来生产 realm 开启 `editUsernameAllowed`**，`setUsername` 才会真正被调用，届时应补一条闸门覆盖
> 该分支的端到端行为。当前不开启是合理的安全默认。

### 23.5 验证

子仓 PR #12 合并（`e6e2c080`），CI **9 个 check 全绿**。gate job 日志实测（不只看 job 绿）：

| 闸门 | Tests run |
| --- | --- |
| `GfSmokeIT` | 3 |
| `GfOrgImportGateIT` | 3 |
| `GfPermissionGateIT` | 12 |
| `GfDataScopeGateIT` | 5 |
| `GfSnapshotEventGateIT` | 7 |
| **`GfAccountLifecycleGateIT`** | **5（本批新增）** |

`BUILD SUCCESS`，6 条闸门合计 35 个用例全绿。四个守门全绿
（能力归属 122 / 权限码映射 69 / 平台接入契约 70 passed 0 failed，兼容矩阵 RC=0）。

### 23.6 本轮沉淀的两条可复用判据

1. **在测试里用 `Ids.newId` 派生唯一业务标识时，必须取完整 ULID 或其后段随机位，绝不能取前缀子串**——
   单调 ULID 的前 10 字符是毫秒时间戳，紧密循环内会重复（本批实证：`substring(4,14)` 使同一毫秒内
   两个登录名完全相同，撞 `uk_account_login_name`，且报错栈落在 `createPersonal`、
   消息含「Keycloak 调用失败」，**形态上像容器不可达，实为造数不唯一**）。
2. **`@ConditionalOnBean` 引用第三方（Spring Boot）提供的 bean 时，必须同步声明
   `@AutoConfigureAfter` 该自动配置类**，否则装配结果取决于评估顺序（未定义行为），
   且失败形态是「bean 不存在」而非「顺序错了」，极难定位。

---

## 35. 同步域两个缺陷与一次「假绿」（2026-11-26，由 GfSyncGateIT 暴露）

补 `GfSyncGateIT`（子仓 PR #15）过程中经三轮 CI 迭代暴露 **1 个真实生产缺陷 + 1 次测试假绿**。
两者都属「单测永远测不到、只有真库+真 MyBatis 映射才暴露」的类型。

### 35.1 真实生产缺陷：`Map.copyOf` 拒绝 null 值 → 删除保护路径永久 FAILED

`LandedRecord` 构造器原用 `Map.copyOf(fields)`，而 **`Map.copyOf` 对 null 值抛 NPE**。
`MybatisMasterDataPort.personFields` 会把 `fdn_person` 的可空列
（`mobile_cipher`/`email_cipher`/`mobile_hash`/`email_hash`）原样放入 fields——
**「人员没有手机号或邮箱」是完全正常的业务态**。于是同步引擎读取本地人员
（`existingPersons` 与 `DiffPreviewService.preview` 都调 `listBySource`）时抛 NPE，
且该异常被 `SyncEngine.execute` 的 catch 吞成任务 `FAILED`（`failed()` **不打异常栈**、
errorCode 只记 `INTERNAL`），后果：

> **生产中任何含空手机/邮箱的在职人员被同步时，删除保护暂停路径永远 FAILED、删除保护形同虚设。**

**为何单测测不到**：sync 域单测用 `InMemoryMasterDataPort`，测试数据的字段都非空，从不走
「可空 DB 列 → `personFields` → `Map.copyOf`」这条真实链路。

**修法**：改用 `Collections.unmodifiableMap(new LinkedHashMap<>(fields))`——仍不可变、仍防御性复制，
但容忍 null 值且**保持键集完整**（不跳过 null 键），使 `DiffPreviewService.changedFields` 的
「本地无该键 vs 本地该键为 null」语义不变。javadoc 写明「刻意不用 `Map.copyOf`」及原因防回退。
另补离线单测 `LandedRecordTest`（3 用例）使回归在**无 Docker 机器上也能自动判定**（判据③），
并已**反向验证**：临时改回 `Map.copyOf` → 测试确实报 NPE 失败，证明非恒绿弱断言。

> **可复用判据**：`Map.copyOf` / `List.copyOf` / `Set.copyOf` **拒绝 null 元素**，是 JDK 静默陷阱。
> 凡 record 构造器对入参集合用 `*.copyOf` 而数据源含可空 DB 列，都是潜在 NPE 点。
> 排查「异常被吞成 FAILED/INTERNAL 且无栈」时，优先怀疑 `*.copyOf(null)` 与 `requireNonNull`。
> **同源隐患已登记未改**：`ExternalRecord.fields` 同样用 `Map.copyOf`，其数据源是外部适配器
> （feishu/ldap）拉取的字段，是否含 null 取决于适配器实现——未核实前不动（判据⑤）。

### 35.2 一次假绿：断言无法区分「所测机制」与「无关机制」

`IdempotentUpserter.land` 开头有越界守卫：`!source.authority() && objectType != EXTERNAL_IDENTITY`
→ **跳过主数据落地**并记 WARN「非权威来源越界」（javadoc：非权威来源只写 `fdn_external_identity`）。
闸门初版把来源设为 `authority=false`，于是停用项被静默跳过、person 永不 DISABLED、**且不抛异常**
（任务照样 SUCCEEDED）。这导致：

- 「confirm 后应补落停用」必然失败（实际停用数 0）；
- 更严重的是**「暂停时人员仍 ACTIVE」也成立**——但成立的原因是越界守卫跳过了落地，
  **不是删除保护扣住了停用**。闸门会给出「删除保护生效」的**错误结论**。

删除保护本就是为**权威来源**（有权改主数据者）设计的防线，故本闸门必须用权威来源才测到真语义。
修法两处：① `seedSource` 改 `authority=true`，**DB 与 record 两处都要改**
（`SyncEngine` 读的是传入 record 的 `source.authority()`，不回查数据库）；
② 加日志证据断言把两种机制区分开——必须有含「停用差异超阈值」的 WARN（证明是删除保护），
且必须**没有**含「非权威来源越界」的 WARN（排除守卫路径）。

> **可复用判据（本批新增，比 §22 三条更细）**：**一条断言若能被「与所测语义无关的机制」满足，它就是假绿。**
> 写「X 没发生」类断言时，必须同时钉住「是因为所测机制才没发生」的**正向证据**
> （日志、状态字段、中间产物），否则任何上游短路都会让它通过。
> 这与 §22.4 推论「设计意图 ≠ 实现意图」同源：**断言通过 ≠ 所测语义成立**。

### 35.3 排查手法：分层前置自证把「笼统失败」变成「精确失败」

`SyncEngine.execute` 对 `land` 抛的任何 `RuntimeException` 都 catch 并返回
`SyncOutcome(failed_task, empty_diff, false)`，**且不打异常栈**——首轮 CI 只报出笼统的
「`pause` 应为 true 却是 false」，无从定位。加**四层前置自证**后一轮即定位：
① 假适配器是否被 `AdapterRegistry` 装载 → ② person 是否以正确 source 落库 →
③ 任务是否 FAILED（并顺带查出 `fdn.sync.failed` 事件里的 errorCode）→ ④ diff 是否产生 DISABLE。
这四层已固化为永久回归防护（非临时脚手架），并在 javadoc 写明分工。

> 另注：③ 之所以有效，是因为 `execute` 虽吞栈但**会把 errorCode 落 outbox**
> （`fdn.sync.failed`），故失败信息里带上该 payload 即可让「被吞的异常」仍可定位。
> **凡生产代码吞异常，测试就该去找它留下的旁证（outbox / 状态字段 / 日志表），而不是要求它打栈。**

### 35.4 验证

子仓 PR #15 合并（`7327359b`），CI **9 个 check 全绿**。gate job 日志实测（不只看 job 绿）：

| 闸门 | Tests run |
| --- | --- |
| `GfSmokeIT` | 3 |
| `GfOrgImportGateIT` | 3 |
| `GfPermissionGateIT` | 12 |
| `GfDataScopeGateIT` | 5 |
| `GfSnapshotEventGateIT` | 7 |
| `GfAccountLifecycleGateIT` | 5 |
| `GfSsoGateIT` | 3 |
| `GfOffboardingGateIT` | 4 |
| **`GfSyncGateIT`** | **4（本批新增）** |

`BUILD SUCCESS`，**9 类闸门合计 46 用例全绿**。四个守门全绿
（能力归属 122 / 权限码映射 69 / 平台接入契约 70 passed 0 failed，兼容矩阵 RC=0）。
sync 域离线单测 35 个全绿（含新增 `LandedRecordTest` 3 个）。

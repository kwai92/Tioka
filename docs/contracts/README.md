# Tioka 契约目录

本目录与各模块 `-api` 一起构成「契约」：实现 PR 只能实现，不能改契约。修改契约必须同时修改对应 ADR，并由 CODEOWNERS 审批（[ADR-030](../architecture/adr/ADR-030-anti-drift-process.md)）。

## M0 契约清单（ENG-09）

| 契约 | 位置 | ADR |
| --- | --- | --- |
| Principal、PrincipalHolder、ResourceGuard、AccessLevel、`@RequirePermission`、`@PublicApi` | `tioka-framework/.../framework/security/` | ADR-015 |
| 错误码与 HTTP 状态映射 | `tioka-framework/.../framework/error/ErrorCode.java` | ADR-029 第 14、18 条 |
| RuntimeApi、TurnStatus 状态转换表、ItemType、RuntimeEventType、审批枚举 | `tioka-module-runtime-api/.../runtime/api/kernel/` | ADR-016、ADR-027、ADR-029 |
| Runtime SSE 事件 Schema | `tioka-module-runtime-api/src/main/resources/schema/runtime-event-v1.json` | ADR-016、ADR-028 |
| ModelGateway、ModelCallContext、ModelPurpose | `tioka-module-model-api/.../model/api/gateway/` | ADR-017 |
| AuditEvent、AuditApi、`@Audited` | `tioka-module-system-api/.../system/api/audit/` | ADR-017、ADR-029 第 8 条 |
| 跨域 MQ 事件信封 | [events/mq-envelope-v1.json](events/mq-envelope-v1.json) | ADR-020、ADR-029 第 15 条 |
| 数据库迁移 | [db/](db/) `V100`〜`V105` | ADR-015〜017、ADR-029 第 31、33 条 |

## M1、M2 契约清单（施工设计 M1-xx、M2-xx）

| 契约 | 位置 | 施工设计 |
| --- | --- | --- |
| 权限点目录 `Permissions` | `tioka-framework/.../framework/security/Permissions.java` | M1-01 FND-04 |
| 新错误码 `WORKSPACE_FORBIDDEN`、`EGRESS_DENIED`、`LAST_ADMIN`、`MODEL_ERROR`、`MODEL_UNAVAILABLE` | `ErrorCode.java` | M0-02、M1-03 |
| 审批策略 SPI、工具风险、审批视图、续跑处理器、审批台查询 | `tioka-module-runtime-api/.../runtime/api/approval/` | M1-02 RT-07、M2-04 HCI-05 |
| `TurnAwaiter`、`TrajectoryApi` | `tioka-module-runtime-api/.../runtime/api/kernel/` | M1-02 RT-08、M2-04 HCI-07 |
| 跨域事件发布 `EventPublisher`、`DomainEvent` | `tioka-framework/.../framework/messaging/` | M2-01 ORC-02 |
| 编排入口 `OrchApi`、`Receipt` | `tioka-module-orchestration-api/.../orchestration/api/engine/` | M2-02 |
| 回执工具参数 Schema | [events/orch-receipt-v1.json](events/orch-receipt-v1.json) | M2-02 |
| 代用户令牌保管 `UserTokenVault` | `tioka-module-connector-api/.../connector/api/oauth/` | M2-03 FND-08 |
| 数据库迁移 | [db/](db/) `V110`〜`V119`（M1）、`V130`〜`V143`（M2） | 各文件头注释 |
| 闸门用例骨架 G0〜G2 | `tioka-server/src/test/java/com/tioka/server/gate/`（`@Tag("gate")`，`@Disabled`） | 各阶段完成标准 |

`V104__BackfillRuntimeItems` 是 Java 迁移（M0-03 RT-02），不在本目录；`V119` 使用 Flyway 占位符 `${embeddingDim}`。

**迁移不是一次性全部生效**：删除表或列、改写现有数据的迁移（V103〜V106、V110、V115、V119〜V122、V132、V142、V143 等）随替代旧实现的工作项 PR 一起移入 `db/migration`，对照表见施工设计 M0-01 ENG-11「迁移随工作项落地」（ADR-029 第 33 条修订）。

## Foundation（独立仓库）

Foundation 是独立仓库与独立部署（[ADR-051](../architecture/adr/ADR-051-foundation-platform.md)、[S15](../architecture/systems/S15-Foundation基础平台系统.md)），其 OpenAPI 与事件契约**不在本目录**，在 Foundation 仓库内维护，自 F 阶段（W80–W89）落地。与 Tioka 有关的接口与事件（如授权快照查询、`fdn.authz.changed`、`fdn.person.*`）在 S15 第 14 节列出，Tioka 侧按其接入。F2 增强批次（ADR-029 第 81 条）新增 `fdn.app.grant.changed`（[ADR-076](../architecture/adr/ADR-076-app-to-app-authorization.md)）与 `fdn.widget.changed`（[ADR-077](../architecture/adr/ADR-077-dashboard-widget-registry.md)），同样在 Foundation 仓库内定义。

## 迁移脚本

本目录 `db/` 保留为**历史契约快照（非执行源）**：其 45 份 SQL 副本与 `tioka-server/src/main/resources/db/migration/` 下的 41 份 SQL 迁移、以及 `tioka-server/src/main/java/.../db/migration/` 下的 4 份 Java 迁移（`BaseJavaMigration`，V104 / V124 / V126 / V145）形成「同号不同形态」的演进记录；本目录不双跑、不作为迁移执行面。

**现状（X7 文档新鲜度漂移已于 2026-11-09 回写）**：仓库已引入 Flyway 作为数据库迁移的唯一执行面——`spring.flyway.enabled: true` 已在 `application.yml` 与 `application-local.yml` 生效，原 `schema-pg.sql` 基线已随 M0-01 ENG-11 下线，`db/migration` 为唯一迁移源（见 ADR-029 第 29 条、ADR-026）。如需改动迁移须改 `db/migration` 并经对应 ADR 与 CODEOWNERS 审批（ADR-030）；本目录 `db/` 仅作契约留痕，不再承担执行职责。

## 契约测试

- `TurnStatusContractTest`：Turn 状态转换表。
- `RuntimeEventSchemaContractTest`：SSE Schema 与 Java 枚举一致、关键事件形状。

M1 起按 ADR-030 第 4 条补充租约失效、审批、Flow 调度的表驱动契约测试。

## Foundation 中台化：权限码映射契约（2026-11-16）

**规则归平台、实例归应用**：`tioka-foundation/docs/contracts/permission-code-mapping-v1.schema.json`
是平台契约（`$id` = `urn:foundation:contract:permission-code-mapping:v1`），冻结字段名、
`additionalProperties: false` 与三条策略 const；`tioka-framework/src/main/resources/contracts/permission-code-mapping-v1.json`
是 Tioka 的**应用实例**，通过 `platformContract` 声明遵守平台契约，权限码目录使用应用无关的
`applicationPermissionCodes`。实例随 framework jar 分发只是运行时需要，规则不得由应用自行放宽。

新增权限点必须同时改 `Permissions.ALL_CONCRETE` 与实例，并由
`scripts/verify-permission-code-mapping.mjs`（主仓库）与
`tioka-foundation/scripts/verify-app-integration-contract.mjs`（平台）双向守门：
契约策略（通配不提升、未知码忽略、超管需显式授权）不得放宽、字段不得改回绑定第一个应用的旧名、
接入模式的判定实现不得回落应用本地权限集。

- `PermissionCodeMapperTest` / `PermissionEvaluatorTest`：映射、通配、别名、超管、fail-closed 与双模式等价性。
- `PermissionAspectUnitTest`：切面委托后的放行、403、DENIED 审计与公开接口语义（不依赖容器）。
- `FoundationModeSwitchTest`：`local` / `foundation` 判定器互斥装配，缺中台数据源时启动期拒绝。

## Foundation 中台化：授权快照接入契约（2026-11-16 晚）

`foundation-starter-client` 的 `AuthorizationSnapshotClient` 是派生应用读取中台授权的**唯一只读入口**，
调用 `GET /api/fdn/apps/{appCode}/persons/{personId}/snapshot`，只交出中台原始权限项：
空集合表示「成功查询且确实无权限」，`null` 或异常表示不可用，调用方一律 fail-closed。
响应必须满足 `personId` / `appCode` 与请求一致且 `permissions` 为文本数组，否则整份快照作废；
语义转换仍由接入方的映射契约负责，底座不解释权限码。

- `HttpAuthorizationSnapshotClientTest`：路径编码、版本头、服务账号令牌、身份一致性、结构非法、版本错配不重试。
- `FoundationAuthorizationChainTest` / `FoundationAuthzConfigurationTest`：端到端链路与双模式装配。
- `scripts/verify-permission-code-mapping.mjs` / `verify-capability-ownership.mjs`：静态守门两仓库接线不被拆散。

## Foundation 中台化：平台接入契约与自检（2026-11-16 晚，P0）

派生应用接入不再靠复制 `tioka-server` 里的适配样板，而是由平台提供自动装配护栏与可复用自检包：

- `foundation-starter-client` 的 `FoundationIntegrationAutoConfiguration`：只在 `FOUNDATION` 模式
  激活，静态校验 `foundation.app-code` 登记规则与 `foundation.base-url`，本地 / 自管模式零装配。
- `FoundationIntegrationSelfCheck` + `IntegrationSelfCheckReport`：六项自检（应用编码、基座地址、
  服务账号、快照往返、fail-closed、快照失效钩子），按需调用、启动期不发网络请求；
  `WARN` 不阻塞接入结论，只有 `FAIL` 才算接不上。
- `SnapshotInvalidationHook`：平台 SPI，供 P1 的事件失效链路注册，不绑定任何缓存实现。
- `foundation-it-example` 的 `SecondAppOnboardingTest`：第二接入方骨架，只加
  `foundation-starter-client` 依赖与配置，不依赖任何 Tioka 类，验证「零改中台代码即可接入」。

平台侧守门 `tioka-foundation/scripts/verify-app-integration-contract.mjs` 校验 schema、
应用实例与应用侧实现三者一致；详见 36 号规划 §20。

## Foundation 中台化：运行平面契约（2026-11-16 夜，P1）

轻量只读路径接上版本化缓存，中台退出请求热路径；接入方仍只依赖 `AuthorizationSnapshotClient`
端口，Tioka 适配层一行未改。

- **`AuthorizationSnapshot` / `AuthorizationSnapshotCache`**：`HttpAuthorizationSnapshotClient`
  严格校验并保留 `version`（非负整数）；缓存按 `appCode + personId` 分键，命中未过期快照零回源。
  `fdn.authz.changed` 经 `AuthorizationSnapshotInvalidationListener` 精确失效；TTL
  （`foundation.client.authz.cache-ttl`，默认 5m）只是事件丢失兜底，**不是降级许可**：
  过期且回源失败一律返回 `null` 并删除条目，版本回退或身份串号同样拒绝。
- **事件通道契约**（`event-schemas-v1.json`，主版本仍为 1）：MQ body 新增平台保留字段
  `deliveryId`（与 outbox 行主键同源、业务不可伪造），MQ 与 Webhook 共用同一幂等键；
  Webhook envelope 新增**可选**字段 `sentAt`（epoch millis），因位于 HMAC 覆盖的 `raw_body`
  内而不可篡改，不新增头、不改签名串，v1 接入方忽略即可。验签 → 时间窗 → `eventType +
  deliveryId` 幂等，构成完整防重放。
- **降级标记异步传递**：`DegradeContext.capture()` / `wrap()` / `call()` 让父子线程共享快照，
  子线程 `mark()` 回写后外层过滤器仍能写 `X-Foundation-Degraded`；`clear()` 按线程清理，不串请求。

守门：三个脚本新增运行平面断言（112 / 69 / 64 项）与 Node SDK 25 例自检；详见 36 号规划 §21。

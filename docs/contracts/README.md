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

## 迁移脚本

`db/` 下的脚本以 `tioka-server/src/main/resources/schema-pg.sql` 为基线，按版本号顺序执行，已在 PostgreSQL 16 + pgvector 上验证可依次执行成功（含旧数据回填语句）。仓库目前用 `spring.sql.init` 加载整份 schema，尚未引入 Flyway；ENG-11（M0-01）引入 Flyway 时把 `schema-pg.sql` 作为 `V1__baseline.sql`，本目录脚本原样移入 `tioka-server/src/main/resources/db/migration`，届时删除本目录副本。

## 契约测试

- `TurnStatusContractTest`：Turn 状态转换表。
- `RuntimeEventSchemaContractTest`：SSE Schema 与 Java 枚举一致、关键事件形状。

M1 起按 ADR-030 第 4 条补充租约失效、审批、Flow 调度的表驱动契约测试。

# P1 / L6 验收覆盖：审计字段语义与 traceId 贯通

> 范围：L6 链路（审计字段语义与 traceId 贯通）验收准则与代码现状的逐条映射。
> 性质：**只读验收文档**，本轮 P1 不改任何生产代码、不新增迁移。
> 配套离线验收测试：`tioka-server/src/test/java/com/tioka/server/gov/P1L6AuditFieldContractTest.java`
> 缺口台账：`docs/architecture/design-notes/P1-L6-gap-ledger.md`
>
> 四态判定口径：**一致** / **漂移** / **缺失** / **设计自身矛盾**。

## 1. 审计字段语义

### 1.1 领域模型（AuditEvent record）

代码落点：`tioka-modules/tioka-module-system/tioka-module-system-api/src/main/java/com/tioka/system/api/audit/AuditEvent.java:16-20`

组件全集：`id, ts, workspaceId, actorType, actorId, action, resourceType, resourceId, outcome, reason, traceId, requestId, clientIp, details`（builder 构造，F6）。

| # | 验收准则 | 判定 | 说明 |
|---|---------|------|------|
| 1 | 隔离字段为 workspaceId | 一致 | 组件含 `workspaceId`；契约测试 `auditEventCarriesWorkspaceAndTrace` 固化 |
| 2 | 无 tenantId（ADR-029 第 60 条 2026-11-07 修订；47-GL） | 一致 | 类 Javadoc :11 明示；契约测试 `auditEventHasNoTenantId` 固化 |
| 3 | 携带 traceId / requestId | 一致 | 组件含两者（OBS-01 / ADR-072）；V127 加列对应 |
| 4 | 含 spanId / service 组件（观测语义完整） | **缺失** | record 无 `spanId`、无 `service`；契约测试 `auditEventSpanServiceAreKnownGaps` 将缺口固化为可检索断言（P2 落地后需反转） |

### 1.2 持久化表（audit_event）

代码落点：

- 建表 `tioka-server/src/main/resources/db/migration/V102__m0_model_quota_audit.sql:58-76`：`workspace_id, actor_type, actor_id, action, resource_type, resource_id, outcome, reason, trace_id, client_ip, details_json, prev_hash, hash`（只增不改，哈希链列预留 M4/GOV-09）
- V112（`V112__m1_audit_forward.sql:2-4`）：`forwarded` 转发水位列
- V127（`V127__m1_audit_request_id.sql:1-3`）：`request_id` 列 + 索引
- V133（`V133__m2_correlation_context.sql:21-22`）：`subject` 列 + 索引

| # | 验收准则 | 判定 | 说明 |
|---|---------|------|------|
| 5 | 表列与领域模型对齐（workspace_id/trace_id/request_id/subject） | 一致 | 四列齐备，无 `tenant_id` |
| 6 | 含 span_id / service 列 | **缺失** | 四次迁移均未引入；需 Flyway 迁移，P1 冻结 |
| 7 | 保留策略：retention-days=365 | 一致（按设计暂缓执行） | `tioka-modules/tioka-module-system/tioka-module-system-biz/src/main/java/com/tioka/system/config/AuditProperties.java:11`（默认 365）；`AuditForwarder.purgeExpired`（`.../application/audit/AuditForwarder.java:59-71`）在 `physicalPurgeEnabled=false`（默认，AuditProperties.java:23）时直接返回（no-op）——OBS-01/GOV-09 前禁止物理删除，属有意暂缓，非漂移 |
| 8 | 转发（GOV-02）：未转发批次写 sink 后置 forwarded=true | 一致 | `AuditForwarder.java:20,40` |

## 2. traceId 贯通

现状：**三套 trace 机制并存且互不互通**。

| 机制 | 代码落点 | 语义 |
|------|---------|------|
| ① 审计 MDC traceId | `.../system/application/audit/AuditRequestContext.java:10,15-22`（MDC key `"traceId"`，缺则生成 `trc_` 前缀 ID） | 写入 `audit_event.trace_id` 的实际来源 |
| ② 框架 TraceContext | `tioka-framework/src/main/java/com/tioka/framework/trace/TraceContext.java:4-19`（ThreadLocal + ULID） | 仅被 `RequestContextHolder`（tioka-framework/src/main/java/com/tioka/framework/context/RequestContextHolder.java:16-18,49-66）桥接；主线（HTTP 入口/业务模块）无调用点 |
| ③ OTel Span | `GlobalOpenTelemetry.getTracer("tioka")` 调用点：`IdempotentDeliveryService.java:126`、`OrchEngineService.java:25`、`DefaultToolGateway.java:113`、`TurnRunner.java:124`、`OutboxEventPublisher.java:59` 等 | **无 SDK 初始化**（全仓库无 `OpenTelemetrySdk`/`SdkTracerProvider`），`deploy/compose.yml` 未挂 OTel javaagent → 全部 no-op Span |

### 断点清单

| # | 断点 | 证据 | 判定 |
|---|------|------|------|
| 9 | HTTP 入口不抽 W3C traceparent | `.../application/audit/AuditRequestContextFilter.java:27-61` 只处理 `X-Tioka-Request-Id` / `X-Request-Id`，traceId 由 `AuditRequestContext.currentTraceId()` 自生成（`trc_`） | **缺失** |
| 10 | MQ 消费侧不回写 MDC traceId | `tioka-modules/tioka-module-infra/tioka-module-infra-biz/src/main/java/com/tioka/infra/messaging/IdempotentDeliveryService.java:125-131` 从 envelope 提取 traceparent 后只建 OTel Span，不写 MDC → 消费线程上的 `audit_event.trace_id` 与消息携带的 trace 不同源 | **缺失** |
| 11 | 跨模块 HTTP 不传播 traceparent | 全仓库 grep `traceparent` 无 HTTP 客户端侧注入点（仅 MQ envelope 字段） | **缺失** |
| 12 | model_usage.trace_id 与 audit_event.trace_id 同源 | `UsageRecorder.java:71-74` 取 OTel Span（当前 no-op → NULL），审计取 MDC `trc_` | **漂移**：两条 trace 不同源；L5/L6 交叉缺口 |

## 3. P2 缺口（需迁移 + 部署 OTel agent）

1. `audit_event` 增 `span_id` / `service` 列 + `AuditEvent` 组件（Flyway 迁移）。
2. HTTP 入口抽 W3C traceparent（或 `X-Tioka-Trace-Id`）并注入 MDC 与 OTel Context。
3. MQ 消费侧将 envelope.traceparent 回写 MDC traceId。
4. 跨模块 HTTP 客户端传播 traceparent。
5. 部署侧挂载 OTel javaagent（或代码内初始化 SdkTracerProvider），使 `UsageRecorder`/`TurnRunner` 等 Span 产出真实 trace_id。
6. 三套机制收敛：以 OTel trace 为权威，`AuditRequestContext`/`TraceContext` 降级为兼容层。

## 4. 结论

L6 的「审计字段语义」部分（workspace 隔离、无 tenant、request_id/subject 关联、保留策略暂缓设计）与代码**一致**；「traceId 贯通」部分为**缺失**（三断点 + OTel 未挂载），且导致 L5 的 `model_usage.trace_id` 实际为 NULL（漂移）。全部贯通类缺口归 P2，需同时放宽迁移约束与部署变更。未发现「设计自身矛盾」态条目——三套 trace 并存属演进遗留，目标态（OTel 权威）在 P2 方案中收敛。

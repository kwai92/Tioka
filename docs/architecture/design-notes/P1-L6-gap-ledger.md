# P1 / L6 缺口台账（审计字段语义与 traceId 贯通）

> 登记日期：2026-11-09（P1 一致性审计批次）
> 前置：`P1-L6-acceptance-coverage.md`（准则映射与四态判定）
> 处置原则：P1 批次硬约束禁止新增 Flyway 迁移、禁止修改生产代码与 deploy/compose.yml，以下缺口全部归 **P2**。

## 缺口清单

| # | 缺口 | 态 | 阻塞约束 | P2 落地要点 |
|---|------|----|---------|------------|
| GAP-L6-1 | 三套 trace 机制不互通（审计 MDC `trc_` / 框架 TraceContext ULID / OTel Span） | 缺失 | 需生产代码 + 部署变更 | 以 OTel trace 为权威收敛：`AuditRequestContext`（MDC key "traceId"，AuditRequestContext.java:10）与 `TraceContext.java`（框架，主线无调用点）降级为兼容层；统一 traceId 语义 |
| GAP-L6-2 | HTTP 入口不抽 W3C traceparent | 缺失 | 需生产代码 | `AuditRequestContextFilter`（AuditRequestContextFilter.java:27-61）增加 traceparent 解析 → MDC + OTel Context；无 header 时回退自生成 |
| GAP-L6-3 | MQ 消费侧不回写 MDC traceId | 缺失 | 需生产代码 | `IdempotentDeliveryService`（IdempotentDeliveryService.java:125-131）建 Span 后将 traceId 写入 MDC，使消费线程审计事件与消息链路同源 |
| GAP-L6-4 | 跨模块 HTTP 不传播 traceparent | 缺失 | 需生产代码 | HTTP 客户端（RestTemplate/HttpClient interceptor）注入 traceparent header |
| GAP-L6-5 | OTel Span 生产未挂载（全仓库无 SDK 初始化；deploy/compose.yml 未挂 javaagent） | 缺失 | 需部署变更（compose.yml 禁改） | 挂载 OTel javaagent 或代码内初始化 `SdkTracerProvider`；否则 `UsageRecorder`（UsageRecorder.java:71-74）、`TurnRunner` 等调用点全部 no-op，`model_usage.trace_id` 恒为 NULL |
| GAP-L6-6 | `audit_event` 缺 span_id / service 列（AuditEvent record 亦无对应组件） | 缺失 | 需 Flyway 迁移 | ① 迁移加列；② `AuditEvent` 增组件；③ P2 落地后反转契约测试 `P1L6AuditFieldContractTest#auditEventSpanServiceAreKnownGaps` 的断言并更新本台账 |

## 连带影响（记入 L5 台账交叉引用）

- GAP-L6-5 未解除前，L5 准则「model_usage 携带 trace_id」的贯通性不成立（列存在但取值为 NULL）。

## 非缺口说明（有意设计，勿误登）

- retention-days=365 + `physicalPurgeEnabled=false`（AuditProperties.java:11,23；AuditForwarder.purgeExpired no-op）：OBS-01/GOV-09 前禁止物理删除，属设计暂缓，非缺口。

## 验收证据

- 离线契约测试：`tioka-server/src/test/java/com/tioka/server/gov/P1L6AuditFieldContractTest.java`（纯 JUnit5 + 反射，无 Spring/Redis/PG）
  - `auditEventCarriesWorkspaceAndTrace` / `auditEventHasNoTenantId` / `auditEventSpanServiceAreKnownGaps` / `auditMdcTraceKeyIsTraceId`

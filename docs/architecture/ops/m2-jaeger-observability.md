# M2 Jaeger / OTel 可观测性现状

> 状态：2026-11-10（M2 残余收口批次 B-3）。如实登记现状；**GAP-L6-5 未解除**（OTel javaagent 未挂载）。
> 事实来源：`deploy/compose.yml`（GOV-08 注释段）、`application.yml` tioka.observability 段、
> `OutboxEventPublisher` / `IdempotentDeliveryService` / `TraceService` / `ObservabilityProperties`、
> `docs/architecture/design-notes/P1-L6-gap-ledger.md`。

## 1. compose 内的 Jaeger 服务

```yaml
# GOV-08：本地链路后端（UI 16686 / OTLP gRPC 4317）
jaeger:
  image: jaegertracing/all-in-one:1.57
  environment:
    COLLECTOR_OTLP_ENABLED: "true"
  ports:
    - "16686:16686"   # Jaeger UI
    - "4317:4317"     # OTLP gRPC collector
```

- all-in-one 单进程，数据存内存（重启即失），仅面向本地 / 演示链路；
- 生产 Jaeger（或其它 OTLP 后端）的部署不在本仓库 compose 范围内。

## 2. GAP-L6-5：OTel javaagent 未挂载（未解除）

P1-L6 gap ledger（`P1-L6-gap-ledger.md:15`）原话：

> OTel Span 生产未挂载（全仓库无 SDK 初始化；deploy/compose.yml 未挂 javaagent）—— 缺失，
> 需部署变更（compose.yml 禁改）。

后果（当前真实行为）：

- 应用通过 `io.opentelemetry.api.GlobalOpenTelemetry` 取 tracer，但**无 SDK 初始化、无 agent**，
  全部 Span 走 no-op 实现；
- `UsageRecorder`（UsageRecorder.java:71-74）、`TurnRunner` 等调用点的 span 属性写入为 no-op，
  `model_usage.trace_id` 恒为 NULL；
- L5 准则「model_usage 携带 trace_id」的贯通性**不成立**（列存在但取值为 NULL）。

**解除路径（部署变更，不改 compose）**：以启动参数挂载 OTel javaagent，例如：

```
JAVA_TOOL_OPTIONS=-javaagent:/opt/otel/opentelemetry-javaagent.jar
OTEL_SERVICE_NAME=tioka
OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4317
OTEL_TRACES_SAMPLER=parentbased_traceidratio
OTEL_TRACES_SAMPLER_ARG=1.0
```

（application.yml 第 155〜159 行已预留上述 OTEL_* 环境变量占位说明。）
挂载 agent 后 GlobalOpenTelemetry 自动桥接 agent 的 SDK，应用代码零改动。

## 3. 应用侧配置（tioka.observability.*）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `tioka.observability.enabled` | `${TIOKA_OTEL_ENABLED:false}` | Observability 能力开关 |
| `tioka.observability.service-name` | `${OTEL_SERVICE_NAME:tioka}` | 资源服务名 |
| `tioka.observability.trace-url-template` | `${TIOKA_TRACE_URL_TEMPLATE:http://localhost:16686/trace/{traceId}}` | 轨迹 / Observability 页拼 Jaeger 链接，占位符 `{traceId}` |

装配类：`com.tioka.runtime.config.ObservabilityProperties`（GOV-08）；
`TrajectoryQueryService` 用 `buildTraceUrl(traceparent)` 生成前端跳转链接——
traceId 取自 envelope 的 `traceparent` 第 2 段，agent 未挂载时该链接指向的数据不存在于 Jaeger。

## 4. 代码内 OTel API 使用点（现状即事实）

| 位置 | 用法 |
| --- | --- |
| `tioka-module-infra-biz .../messaging/OutboxEventPublisher.java:58-65` | outbox 落库时取 `Span.current().getSpanContext()`：有效则写 `traceId` + `traceparent`（W3C，sampled→01）；无效回退 MDC traceId，`traceparent` 保持 null 不伪造 |
| `tioka-module-infra-biz .../messaging/IdempotentDeliveryService.java:134-143` | 消费侧 `GlobalOpenTelemetry.getTracer("tioka").spanBuilder("messaging.consume")`，从 envelope `traceparent` 用 W3C propagator 抽 parent；Span 无效时回退 envelope.traceId 写 MDC（key=`traceId`/`spanId`，与审计同源，P2-L6） |
| `tioka-module-runtime-biz .../model/GatewayToolPort.java:76-80` | `Context.current()` / `Scope` 上下文传递 |
| `tioka-module-infra-biz .../application/TraceService.java` | GOV-08：自建 `trace_span` 表**停写**（`record()` 为 no-op），查询保留至 M4 删表 |

## 5. 运维验收步骤（agent 挂载后）

1. 应用启动日志确认 agent 加载（`[opentelemetry.autotrace]` 前缀）。
2. 造一次 Turn 请求，Jaeger UI（`http://localhost:16686`）按 service `tioka` 查询，
   应看到 HTTP 入口 span 与 `messaging.consume` span 同 trace（跨 outbox 中继贯通）。
3. 抽查 `model_usage.trace_id` 非空（GAP-L6-5 解除的落库证据）。
4. 轨迹页（TrajectoryQueryService）生成的 Jaeger 链接可打开对应 trace。

## 6. 未落地项登记

- [ ] GAP-L6-5：javaagent / OTLP 环境变量注入到部署环境（本批次仅登记，部署变更不在代码仓内）。
- [ ] 生产 Jaeger（或等价 OTLP 后端）持久化方案与容量口径未定。
- [ ] `tioka.observability.enabled=true` 后的行为清单（门控点逐项列出）未系统化登记。

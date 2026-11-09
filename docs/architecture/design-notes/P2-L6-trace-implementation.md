# P2-L6 traceId 全链贯通实现记录

> 归属：P2 缺口池 / L6。承接 `P1-L6-acceptance-coverage.md` 与 `P1-L6-gap-ledger.md`（GAP-L6-1~5）。
> 约束口径：迁移约束按用户 P2 裁决放宽，新迁移仅落 `tioka-server/src/main/resources/db/migration/`（X7 单源口径，`docs/contracts/db/` 为历史契约快照不加副本）；`deploy/compose.yml`、OTel javaagent、MQ 信封契约 schema 均未动；无新增运行时依赖。

## 1. 改动清单（分支 feature/p2-l6-trace）

| 文件 | 改动 |
|---|---|
| `tioka-server/src/main/resources/db/migration/V217__p2_audit_span_service.sql`（新增） | `audit_event ADD COLUMN IF NOT EXISTS span_id VARCHAR(64) / service VARCHAR(64)`；不加索引、可空 |
| `tioka-module-system-biz/.../application/audit/TraceparentParser.java`（新增） | W3C traceparent 手写解析（`00-{32hex}-{16hex}-{2hex}`，全零 id 拒绝），零依赖 |
| `tioka-module-system-api/.../api/audit/AuditEvent.java` | record 加 `spanId`、`service` 组件 |
| `tioka-module-system-biz/.../application/audit/AuditRequestContext.java` | MDC 新增 `spanId` key；`currentSpanId()`；`clear()` 清理 |
| `tioka-module-system-biz/.../application/audit/AuditRequestContextFilter.java` | HTTP 入口读 `traceparent` 头：合法→MDC `traceId`=trace-id、`spanId`=parent-span-id；非法/缺失→维持自生成 `trc_`、spanId 空 |
| `tioka-module-system-biz/.../application/audit/AuditService.java` | 注入 `tioka.observability.service-name`（默认 `tioka`）；`enrich()` 补 spanId（MDC）与 service；`write()` 落列 |
| `tioka-module-system-biz/.../application/audit/AuditedAspect.java` | 同步 spanId/service 填充 |
| `tioka-module-system-biz/.../infrastructure/persistence/AuditEventWriteRow.java` | 加 `spanId`/`service` 字段 |
| `tioka-module-system-biz/.../resources/mapper/AuditEventMapper.xml` | insert/SELECT 增加 `span_id, service` 两列 |
| `tioka-module-system-biz/.../api/AuditController.java` | CSV 导出列头与行加 spanId/service |
| `tioka-module-infra-biz/.../messaging/OutboxEventPublisher.java` | Span 无效（未挂 agent）时 envelope.traceId 回退 MDC traceId；traceparent 不伪造（保持 null） |
| `tioka-module-infra-biz/.../messaging/IdempotentDeliveryService.java` | 派发 handler 前回写 MDC：优先 extractParent 的 SpanContext（traceId+spanId）；无效回退 envelope.traceId；finally 清理 |
| 构造点同步（5 处） | `UserAdminService`/`WorkspaceMemberService`/`DefaultResourceGuard`/`OidcFederationService`/runtime `ApprovalDecideService` —— AuditEvent builder 增加 spanId/service 传参（其余取 null，由 enrich 补齐） |
| `tioka-server/src/test/.../gov/P1L6AuditFieldContractTest.java` | **反转**缺口固化断言：断言 AuditEvent 现含 spanId/service（GAP-L6-4/5 收口） |
| `tioka-module-system-biz/src/test/.../audit/TraceparentParserTest.java`（新增） | 解析器单测：合法/非法/全零/缺失 |
| `tioka-server/src/test/.../gov/P2L6TraceContinuityContractTest.java`（新增） | 源码级贯通守护：Filter 引用 traceparent、消费侧 MDC 回写、生产侧 MDC 回退（nio 读源文件） |
- ⏸ 端到端 IT（带 traceparent 头请求→审计 trace_id=W3C；MQ 两侧审计 trace_id 一致）**待补**：需 PostgreSQL/Redis/Testcontainers 环境，本机无法验证编译与运行，为避免提交不可验证代码暂不落盘，待 CI/有 Docker 环境时补写（规格见上节 P2L6TraceContinuityContractTest 的源码守护范围）。

## 2. 贯通链路（落地后）

```
HTTP 入口（带合法 traceparent）            HTTP 入口（无 traceparent）
  MDC traceId = W3C trace-id                MDC traceId = trc_（自生成）
  MDC spanId  = parent-span-id                    │
        │                                         │
        ▼                                         ▼
   审计 trace_id/span_id/service ◄──────────────┘
        │
        ▼
   outbox envelope.traceId（Span 无效→回退 MDC traceId；traceparent 不伪造）
        │
        ▼
   MQ 消费侧：extractParent 有效→Span+MDC(traceId,spanId)；无效→MDC(traceId)=envelope.traceId
        │
        ▼
   消费侧审计 trace_id 与生产侧同源（三断点收口：HTTP 入口断点✅ MQ 消费断点✅）
```

## 3. 断点收口与遗留

- ✅ GAP-L6-1/2/3（三套 trace 不连的审计侧断点）：HTTP 入口、MQ 消费、生产回退全部接通——**W3C traceId 语义贯通**，不依赖 OTel agent。
- ✅ GAP-L6-4/5（span_id/service 列）：V217 落列 + 全链路填充。
- ⏸ OTel Span 层贯通（`OutboxEventPublisher` 优先走 `Span.current()`）：仍需部署面挂载 OTel javaagent / 开启 `TIOKA_OTEL_ENABLED`——归 **L10（全量 OTel trace）**，本轮部署面不动。
- ⏸ 跨模块 HTTP 出站传播 traceparent：全仓无 Feign/RestTemplate 出站客户端注入器，属新增能力，归后续批次评估。
- ⏸ 审计哈希链：归 L10。

## 4. 验证（本机实测）

- 全仓 `mvn -q -DskipTests compile`：通过（Exit 0）。
- `TraceparentParserTest` 3/3、`P1L6AuditFieldContractTest` 4/4（含反转断言）、`P2L6TraceContinuityContractTest` 5/5。
- system-biz 模块全量单测 7 类 28/28 无回归。
- 端到端 IT 为待补项（见第 3 节）；存量审计 IT（AuditContextIT 等）需基础设施，待 CI/有 Docker 环境执行。

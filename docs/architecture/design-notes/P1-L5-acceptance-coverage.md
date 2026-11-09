# P1 / L5 验收覆盖：模型网关配额与成本归集

> 范围：L5 链路（模型网关配额与成本归集）验收准则与代码现状的逐条映射。
> 性质：**只读验收文档**，本轮 P1 不改任何生产代码。
> 配套离线验收测试：`tioka-server/src/test/java/com/tioka/server/model/P1L5CostAttributionContractTest.java`
> 缺口台账：`docs/architecture/design-notes/P1-L5-gap-ledger.md`
>
> 四态判定口径：**一致**（准则有代码落点且语义相符）/ **漂移**（有落点但语义偏离准则）/ **缺失**（无落点）/ **设计自身矛盾**（准则与更高层决策冲突）。

## 1. 验收准则 → 代码落点映射

### 1.1 需求侧配额（tokens_month / concurrent_turns / tool_rpm）

| # | 验收准则 | 代码落点 | 判定 |
|---|---------|---------|------|
| 1 | 配额卡点 SPI：ModelGateway 预检与记账、TurnRunner 并发占位 | `tioka-modules/tioka-module-system/tioka-module-system-api/src/main/java/com/tioka/system/api/spi/QuotaGuard.java:8-32`（`precheckTokens` / `consumeTokens` / `tryAcquireConcurrent` / `renewConcurrent` / `releaseConcurrent`） | 一致 |
| 2 | 需求侧实现：Redis 计数 + gov_quota 上限 | `tioka-modules/tioka-module-system/tioka-module-system-biz/src/main/java/com/tioka/system/application/quota/DefaultQuotaGuard.java:25`（`precheckTokens`:63、`consumeTokens`:70、`tryAcquireConcurrent`:81，Lua 原子占位脚本 :35-40） | 一致 |
| 3 | 指标名与作用域固定 | `tioka-modules/tioka-module-system/tioka-module-system-biz/src/main/java/com/tioka/system/application/quota/QuotaMetrics.java:7-17`（`tokens_month` / `concurrent_turns` / `tool_rpm`；scope: workspace/user/agent/tool） | 一致 |
| 4 | 配额持久化表 | `tioka-server/src/main/resources/db/migration/V102__m0_model_quota_audit.sql:42-54`（`gov_quota`，主键 (scope_type, scope_id, metric)，metric CHECK 与 QuotaMetrics 对齐） | 一致 |
| 5 | 计数定期回写 DB | `tioka-modules/tioka-module-system/tioka-module-system-biz/src/main/java/com/tioka/system/application/quota/QuotaFlusher.java:20,33-34`（@Scheduled 60s flush） | 一致 |
| 6 | Redis 与 DB 对账冲正 | `tioka-modules/tioka-module-system/tioka-module-system-biz/src/main/java/com/tioka/system/application/quota/QuotaReconciler.java:22,40-41`（@Scheduled cron `0 15 * * * *`） | 一致 |
| 7 | 预检不估算本次用量（ADR-029 第 7 条） | `QuotaGuard.java:12-14` Javadoc 明示；`DefaultQuotaGuard.precheckTokens` 仅比较已用与上限 | 一致 |
| 8 | 并发租约过期回收 | `DefaultQuotaGuard.java:81`（ZSet score=expiresAt，Lua 脚本内清理过期成员） | 一致 |

### 1.2 限流（RPM / TPM）

| # | 验收准则 | 代码落点 | 判定 |
|---|---------|---------|------|
| 9 | binding 级 RPM/TPM 令牌桶（Redis Lua，MH-03） | `tioka-modules/tioka-module-model/tioka-module-model-biz/src/main/java/com/tioka/model/application/gateway/ModelRateLimiter.java:20-50`（Hash 存 tokens/ts，超限按等待毫秒睡眠重试，总等待超 `rate-limit-max-wait` 抛 `RATE_LIMITED`） | 一致 |

### 1.3 网关编排（选模 → 配额 → 限流 → 调用 → 记账）

| # | 验收准则 | 代码落点 | 判定 |
|---|---------|---------|------|
| 10 | 接口签名（chat/embed/rerank）未漂移 | `tioka-modules/tioka-module-model/tioka-module-model-api/src/main/java/com/tioka/model/api/gateway/ModelGateway.java:16-22`（契约测试 `gatewayInterfaceStable` 固化） | 一致 |
| 11 | 编排顺序含配额预检 | `tioka-modules/tioka-module-model/tioka-module-model-biz/src/main/java/com/tioka/model/application/gateway/DefaultModelGateway.java:31`（`chat`:68，配额预检调用 :305） | 一致 |
| 12 | rerank 可用 | `DefaultModelGateway.java:385-386`：`rerank` 抛 `NotImplemented.of("ModelGateway.rerank","MH-04")` | **缺失**（接口契约存在，实现为已知缺口 MH-04；契约测试 `rerankIsKnownGapMH04` 只断言接口层契约，不触发真实调用） |

### 1.4 成本归集与查询

| # | 验收准则 | 代码落点 | 判定 |
|---|---------|---------|------|
| 13 | 用量明细表 model_usage：按 workspace/user/agent/thread/turn/binding/model 落行，只增不改 | `tioka-modules/tioka-module-model/tioka-module-model-biz/src/main/java/com/tioka/model/infrastructure/persistence/ModelUsageDO.java:11-34`（@TableName("model_usage")，继承 AppendOnlyDO；V102/V133 建列） | 一致 |
| 14 | 成本口径：cost_micros = prompt × input_price/1e6 + completion × output_price/1e6（四舍五入） | `tioka-modules/tioka-module-model/tioka-module-model-biz/src/main/java/com/tioka/model/application/gateway/UsageRecorder.java:84-90`（静态 `costMicros` 公式与准则逐字一致） | 一致 |
| 15 | 异步批量写入（不阻塞调用链） | `UsageRecorder.java:24-31`（MH-05，队列 + 后台线程每 1s 或满 200 条批量 INSERT，停机 flush :125-146） | 一致 |
| 16 | 明细携带 trace_id（全链贯通入参） | `ModelUsageDO.java:34` + `UsageRecorder.java:71-74`（取 `Span.current().getSpanContext().getTraceId()`） | **漂移**：代码落点存在，但取值依赖 OTel Span 有效——当前部署未挂 OTel agent（见 L6 台账），生产环境该列实际为 NULL。字段本身判定为「一致」，贯通性缺口记 L6 |
| 17 | 成本汇总：按 workspace/agent/user/model 分组 | `tioka-modules/tioka-module-model/tioka-module-model-biz/src/main/java/com/tioka/model/application/CostQueryService.java:26-36`（GOV-05；另含 trend/sessionCost/turnUsage） | 一致 |
| 18 | 汇总暴露为系统 API | `tioka-modules/tioka-module-model/tioka-module-model-biz/src/main/java/com/tioka/model/api/SystemCostController.java:26-48`（`/api/system/cost/summary|trend|...`） | 一致 |
| 19 | 无 tenantId（权威隔离字段为 workspaceId） | `ModelUsageDO` 全字段（契约测试 `modelUsageKeysOnWorkspaceNotTenant` 固化，含父类扫描） | 一致 |
| 20 | ModelGateway 专属契约测试 | 本轮新增 `P1L5CostAttributionContractTest`（此前无；历史 `UsageIT`/`CostIT`/`TokenBucketIT` 等为 IT 集成测试，依赖 Spring + Redis + PG，不在离线验收范围） | 一致（本轮补齐） |

## 2. P2 缺口（需迁移）

以下缺口均依赖 Flyway 迁移或部署变更，被 P1 全批次硬约束冻结，归 P2（详见 `P1-L5-gap-ledger.md`）：

1. **供给侧月度预算**（provider / binding / apiKey 维度的 token 或金额月度上限）：`gov_quota` 的 scope_type 仅 workspace/user/agent/tool（V102 CHECK 约束），无 provider 侧作用域。
2. **provider 分摊**：`model_usage` 无 `provider_id` 列，成本无法按供应商分摊对账。
3. **成本 80% 预警事件**：无任何「用量达上限 80%」的事件产出（无代码落点，事件总线亦无对应 topic）。
4. **rerank 实现（MH-04）**：`DefaultModelGateway.rerank` 抛 `NotImplemented`，需 provider 适配层支持（无需迁移，但为已登记 M 阶段缺口，随 P2 一并排期）。

## 3. 结论

L5 验收准则中「需求侧配额、限流、成本归集明细与查询」部分与代码**一致**；「供给侧预算、provider 分摊、80% 预警、rerank」为**缺失**，全部归 P2 且需放宽迁移约束。未发现「设计自身矛盾」态条目。隔离字段纪律（无 tenantId）在本链路全部落点成立。

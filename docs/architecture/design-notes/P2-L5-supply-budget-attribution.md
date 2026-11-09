# P2 / L5：供给侧月度预算与 provider 分摊（实现覆盖说明）

> 批次：P2 批次 2（迁移依赖型缺口池）
> 分支：`feature/p2-l5-supply`（基线 main = `244a9b0`）
> 收口缺口：`P1-L5-gap-ledger.md` GAP-L5-1、GAP-L5-2
> 硬约束遵守：迁移仅落 `db/migration`（V218）、contracts/db 按 X7 不加副本、不改 compose、不引新运行时依赖、workspaceId 权威、预警 MQ 事件与 rerank 保持冻结

## 一、交付范围

| 交付物 | 说明 |
|--------|------|
| `V218__p2_model_supply_budget.sql` | ① `model_usage` 加 `provider_id VARCHAR(64)` + 索引 `idx_usage_provider_ts`；② `model_binding` 加 `budget_token_month` / `budget_cost_month`（BIGINT，NULL=不限）；③ `gov_quota` metric CHECK 扩展 `'cost_month'` |
| `ModelBudgetGuard`（新增） | 供给侧月度预算守卫：按 binding 聚合本月已用（token / 微美分成本），调用 provider 前预检，超限抛 `MODEL_BUDGET_EXHAUSTED` |
| `ErrorCode.MODEL_BUDGET_EXHAUSTED` | 42902 / HTTP 429（与 QUOTA_EXCEEDED 同语义族，登记于唯一错误码字典） |
| 分摊链路贯通 | `Resolution.binding().providerId()` → `UsageRecordCommand.providerId` → `ModelUsageDO.providerId` → `model_usage.provider_id` |
| 分摊查询出口 | `CostQueryService` `groupBy=provider`（`summaryByProvider`：LEFT JOIN `model_provider` 取名称）+ `trendByDay` provider 维度；`CostQueryApi` javadoc 同步 |
| 管理面 | `CreateModelBindingRequest` / `UpdateModelBindingRequest` / `ModelBindingView` 增预算字段；`ModelAdminService` 创建/更新/视图透传；`ModelBinding` record 增组件并保留旧参兼容构造 |

## 二、预算语义（GAP-L5-1）

- **维度**：binding（供给侧）。台账原列 provider/apiKey 维度为「落地要点候选」，本轮按最小闭环落 binding 维度——预算随绑定走、与限流（rpm/tpm）同粒度；provider 维度可由多 binding 预算求和近似，apiKey 维度待后续批次评审。
- **窗口**：系统时区自然月（月初 00:00 起），与 `SystemCostController` 缺省汇总窗口一致。
- **判定**（任一命中即熔断）：
  - token：`本月已用(prompt+completion) + 本次预估 > budget_token_month`；
  - 成本：`本月已用 cost_micros ≥ budget_cost_month`（当次成本预估不可得，以已用口径熔断，恰好等于也算耗尽）。
- **熔断行为**：
  - chat（`chatTracked`）：预算耗尽沿故障接力链跳过候选（`budget-failover`，记 FAILOVER 用量行保持链路可观测）；链耗尽抛 `MODEL_BUDGET_EXHAUSTED`；
  - embed（无接力链）：预检失败直接抛。

## 三、provider 分摊链路（GAP-L5-2）

```
ModelBindingResolver.ofBinding ── Resolution(binding 快照含 providerId)
        │
DefaultModelGateway.account ── UsageRecordCommand(providerId)
        │
UsageRecorder.record ── ModelUsageDO.providerId ── model_usage.provider_id（V218）
        │
ModelUsageMapper.xml ── summaryByProvider（JOIN model_provider）/ trendByDay(provider)
        │
CostQueryService("provider") ── CostQueryApi / SystemCostController（权限语义不变）
```

- 历史行 `provider_id` 为 NULL，不回填、不出现于分摊结果（X7 口径：不改历史）。
- 权威隔离字段仍为 `workspaceId`，未引入 tenantId。

## 四、测试与验证

| 测试 | 结果 |
|------|------|
| `ModelBudgetGuardTest`（biz，Mockito 离线，7 用例：token 预估累计超限/放行、成本耗尽/放行、未配置放行、null 放行、负预估钳制） | 7/7 |
| `P2L5SupplyBudgetContractTest`（server，6 用例，nio 源码守护：V218 DDL、DO/Command providerId、record 预算组件、ErrorCode、网关预检、provider 查询出口） | 6/6 |
| `P1L5CostAttributionContractTest`（反转：`modelUsageCarriesCostAndTrace` 增 providerId 断言） | 4/4 |
| 全量回归（model-biz 21 + model-api 1 + server 80） | 102/102，0 失败 |
| 全仓编译 / install | Exit 0 |

端到端 IT（`QuotaIT` / `CostIT` 需 Docker + PostgreSQL）本机不可跑，由 CI 承接——与 P0 四 IT 闸门同一验证通道。

## 五、遗留与冻结

- GAP-L5-3（成本 80% 预警 MQ 事件）：涉 ADR-030 事件契约，保持冻结；V218 已预留 `cost_month` metric。
- GAP-L5-4（rerank MH-04）：保持冻结。
- provider / apiKey 维度预算、`IT` 闸门、OTel Span 层预算上下文：后续批次。

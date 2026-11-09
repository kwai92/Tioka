# P1 / L5 缺口台账（模型网关配额与成本归集）

> 登记日期：2026-11-09（P1 一致性审计批次）
> 前置：`P1-L5-acceptance-coverage.md`（准则映射与四态判定）
> 处置原则：P1 批次硬约束禁止新增 Flyway 迁移、禁止修改生产代码，以下缺口全部归 **P2**。
> **P2 进展（2026-11-09）**：GAP-L5-1 / GAP-L5-2 已随 `V218__p2_model_supply_budget.sql` + 供给侧预算链路落地（见 `P2-L5-supply-budget-attribution.md`）；GAP-L5-3 / GAP-L5-4 保持冻结。

## 缺口清单

| # | 缺口 | 态 | 阻塞约束 | P2 落地要点 |
|---|------|----|---------|------------|
| GAP-L5-1 | 供给侧月度预算（provider / binding / apiKey 维度 token/金额上限与超额熔断） | **P2 已落地（2026-11-09）**：binding 维度预算 + 超额熔断；provider/apiKey 维度预算未含，见落地说明 | 原：需 Flyway 迁移；需生产代码 | ✅ V218 `model_binding.budget_token_month/budget_cost_month` + `gov_quota` CHECK 扩展 `cost_month`；`ModelBudgetGuard` 在 DefaultModelGateway 调用 provider 前预检（chat 沿接力链跳过耗尽候选、embed 直接熔断）；`ErrorCode.MODEL_BUDGET_EXHAUSTED`(42902/429) |
| GAP-L5-2 | 成本按 provider 分摊 | **P2 已落地（2026-11-09）**；历史行 provider_id 为 NULL（不改历史，新行起计） | 原：需 Flyway 迁移 | ✅ V218 `model_usage.provider_id` + `idx_usage_provider_ts`；`UsageRecordCommand`/`ModelUsageDO`/`UsageRecorder` 透传（binding 快照解析 providerId）；`CostQueryService` `groupBy=provider` + `trendByDay` provider 维度 |
| GAP-L5-3 | 成本 80% 预警事件 | 缺失（冻结） | 预警涉 ADR-030 事件契约（outbox topic 新增），保持冻结；阈值配置可先行（V218 已扩 `cost_month` metric） | 待解冻后：① 配额消费路径（`DefaultQuotaGuard.consumeTokens`）达上限 80% 时发领域事件；② 事件总线（outbox）新增 topic；③ 前端/通知侧订阅 |
| GAP-L5-4 | rerank 实现（MH-04） | 缺失（接口契约在，实现抛 `NotImplemented`）（冻结） | 无需迁移；需 provider 适配层生产代码 | `DefaultModelGateway.rerank`（DefaultModelGateway.java）接入至少一家 provider 的 rerank 端点；补离线契约断言反转说明见 P1L5CostAttributionContractTest#rerankIsKnownGapMH04 |

## 交叉引用

- `model_usage.trace_id` 取值依赖 OTel Span 有效性，当前部署未挂 OTel agent，生产环境该列实际为 NULL——贯通性根因与处置方案见 L6 台账（`P1-L6-gap-ledger.md` GAP-L6-1/2/3）。

## 验收证据

- 离线契约测试：`tioka-server/src/test/java/com/tioka/server/model/P1L5CostAttributionContractTest.java`（纯 JUnit5 + 反射，无 Spring/Redis/PG）
  - `modelUsageKeysOnWorkspaceNotTenant` / `modelUsageCarriesCostAndTrace`（P2 后含 providerId 断言） / `gatewayInterfaceStable` / `rerankIsKnownGapMH04`
- P2 落地守护：`tioka-server/src/test/java/com/tioka/server/model/P2L5SupplyBudgetContractTest.java`（6 用例，nio 源码守护）+ `tioka-module-model-biz` `ModelBudgetGuardTest`（7 用例，Mockito 离线单测）

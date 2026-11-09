# P1 / L5 缺口台账（模型网关配额与成本归集）

> 登记日期：2026-11-09（P1 一致性审计批次）
> 前置：`P1-L5-acceptance-coverage.md`（准则映射与四态判定）
> 处置原则：P1 批次硬约束禁止新增 Flyway 迁移、禁止修改生产代码，以下缺口全部归 **P2**。
> **P2 进展（2026-11-09）**：GAP-L5-1 / GAP-L5-2 已随 `V218__p2_model_supply_budget.sql` + 供给侧预算链路落地；GAP-L5-3 / GAP-L5-4 已随批次 3 解冻落地（应用内预警事件 + NotificationPort / rerank 全链）。**L5 四项缺口全部收口**，详见 `P2-L5b-warn-rerank.md`。

## 缺口清单

| # | 缺口 | 态 | 阻塞约束 | P2 落地要点 |
|---|------|----|---------|------------|
| GAP-L5-1 | 供给侧月度预算（provider / binding / apiKey 维度 token/金额上限与超额熔断） | **P2 已落地（2026-11-09）**：binding 维度预算 + 超额熔断；provider/apiKey 维度预算未含，见落地说明 | 原：需 Flyway 迁移；需生产代码 | ✅ V218 `model_binding.budget_token_month/budget_cost_month` + `gov_quota` CHECK 扩展 `cost_month`；`ModelBudgetGuard` 在 DefaultModelGateway 调用 provider 前预检（chat 沿接力链跳过耗尽候选、embed/rerank 直接熔断）；`ErrorCode.MODEL_BUDGET_EXHAUSTED`(42902/429) |
| GAP-L5-2 | 成本按 provider 分摊 | **P2 已落地（2026-11-09）**；历史行 provider_id 为 NULL（不改历史，新行起计） | 原：需 Flyway 迁移 | ✅ V218 `model_usage.provider_id` + `idx_usage_provider_ts`；`UsageRecordCommand`/`ModelUsageDO`/`UsageRecorder` 透传（binding 快照解析 providerId）；`CostQueryService` `groupBy=provider` + `trendByDay` provider 维度 |
| GAP-L5-3 | 成本 80% 预警事件 | **P2 已落地（2026-11-09，批次 3 解冻）**；MQ outbox topic 对接待 GOV-13/NTF-01 接线（信封契约不动） | 原：涉 ADR-030 事件契约 | ✅ `QuotaThresholdExceededEvent`（system-api 领域事件）+ `DefaultQuotaGuard` 80% 阈值首次越线发布（Redis SETNX 月窗去重，失败不阻断计数）+ `QuotaWarnNotifier`（WARN 日志 + `NotificationPort` 可选通知，dedupKey 对齐约束 F21） |
| GAP-L5-4 | rerank 实现（MH-04） | **P2 已落地（2026-11-09，批次 3 解冻）**；`NotImplemented` 已移除 | 无需迁移；provider 适配层生产代码 | ✅ `ProviderClient.rerank`（mock=bigram 确定性打分；HTTP=Cohere 兼容 `POST /rerank`，relevance_score/score 双字段解析）+ `DefaultModelGateway.rerank` 全链（选模→预算→限流→排序截断 topN→记账），embed/rerank 错误记账合并 `accountError` |

## 交叉引用

- `model_usage.trace_id` 取值依赖 OTel Span 有效性，当前部署未挂 OTel agent，生产环境该列实际为 NULL——贯通性根因与处置方案见 L6 台账（`P1-L6-gap-ledger.md` GAP-L6-1/2/3）。

## 验收证据

- 离线契约测试：`tioka-server/src/test/java/com/tioka/server/model/P1L5CostAttributionContractTest.java`（纯 JUnit5 + 反射，无 Spring/Redis/PG）
  - `modelUsageKeysOnWorkspaceNotTenant` / `modelUsageCarriesCostAndTrace`（P2 后含 providerId 断言） / `gatewayInterfaceStable` / `rerankContractExists`
- P2 落地守护：`P2L5SupplyBudgetContractTest`（6 用例）+ `ModelBudgetGuardTest`（7）+ `QuotaWarnTest`（5）+ `ProviderClientRerankTest`（4）+ `DefaultModelGatewayRerankTest`（4）

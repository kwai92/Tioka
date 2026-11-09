# P1 / L5 缺口台账（模型网关配额与成本归集）

> 登记日期：2026-11-09（P1 一致性审计批次）
> 前置：`P1-L5-acceptance-coverage.md`（准则映射与四态判定）
> 处置原则：P1 批次硬约束禁止新增 Flyway 迁移、禁止修改生产代码，以下缺口全部归 **P2**。

## 缺口清单

| # | 缺口 | 态 | 阻塞约束 | P2 落地要点 |
|---|------|----|---------|------------|
| GAP-L5-1 | 供给侧月度预算（provider / binding / apiKey 维度 token/金额上限与超额熔断） | 缺失 | 需 Flyway 迁移（`gov_quota.scope_type` CHECK 扩展或新表 `provider_budget`）；需生产代码 | ① 迁移扩 scope_type 或建 provider 预算表；② QuotaGuard/ModelGateway 增加供给侧预检分支；③ 出入口：DefaultModelGateway 编排链在调用 provider 前预检 |
| GAP-L5-2 | 成本按 provider 分摊 | 缺失 | 需 Flyway 迁移（`model_usage` 增 `provider_id` 列，历史行回填策略需评审） | ① `model_usage.provider_id` 列 + 索引；② `ModelUsageDO`/`UsageRecordCommand` 透传 provider；③ `CostQueryService` 增 `groupBy=provider` |
| GAP-L5-3 | 成本 80% 预警事件 | 缺失 | 建议随 GAP-L5-1 迁移一并落地（预警阈值依赖上限配置） | ① 配额消费路径（`DefaultQuotaGuard.consumeTokens`）达上限 80% 时发领域事件；② 事件总线（outbox）新增 topic；③ 前端/通知侧订阅 |
| GAP-L5-4 | rerank 实现（MH-04） | 缺失（接口契约在，实现抛 `NotImplemented`） | 无需迁移；需 provider 适配层生产代码 | `DefaultModelGateway.rerank`（DefaultModelGateway.java:385-386）接入至少一家 provider 的 rerank 端点；补离线契约断言反转说明见 P1L5CostAttributionContractTest#rerankIsKnownGapMH04 |

## 交叉引用

- `model_usage.trace_id` 取值依赖 OTel Span 有效性，当前部署未挂 OTel agent，生产环境该列实际为 NULL——贯通性根因与处置方案见 L6 台账（`P1-L6-gap-ledger.md` GAP-L6-1/2/3）。

## 验收证据

- 离线契约测试：`tioka-server/src/test/java/com/tioka/server/model/P1L5CostAttributionContractTest.java`（纯 JUnit5 + 反射，无 Spring/Redis/PG）
  - `modelUsageKeysOnWorkspaceNotTenant` / `modelUsageCarriesCostAndTrace` / `gatewayInterfaceStable` / `rerankIsKnownGapMH04`

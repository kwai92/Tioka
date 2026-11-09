# P2 / L5 批次 3：成本 80% 预警事件 + rerank（MH-04）实现覆盖说明

> 批次：P2 批次 3（缺口池解冻收尾）
> 分支：`feature/p2-l5b-warn-rerank`（基线 main = `2533937`）
> 收口缺口：`P1-L5-gap-ledger.md` GAP-L5-3、GAP-L5-4（L5 四项缺口自此全部收口）
> 硬约束遵守：无迁移；不改 `deploy/compose.yml`；不引新运行时依赖；MQ 信封契约 `mq-envelope-v1.json` 不动（X13 不改判）；workspaceId 权威

## 一、GAP-L5-3：成本 80% 预警事件

| 交付物 | 说明 |
|--------|------|
| `QuotaThresholdExceededEvent`（system-api/spi，新增） | 领域事件 record：scopeType/scopeId/metric/limitValue/usedValue/thresholdPct/yearMonth |
| `DefaultQuotaGuard`（改造） | `consumeTokens` 计数后对 workspace/user/agent 三 scope 做 `warnIfCrossed`：已用 ≥ 80% 上限且**首次越线**（Redis SETNX `q:warn:{scope}:{id}:{yyyyMM}:80`，TTL 40 天覆盖整月）才发布事件；预警失败仅记日志，不阻断计数主流程 |
| `QuotaWarnNotifier`（system-biz，新增） | `@EventListener` 消费：WARN 日志 + `NotificationPort` **可选**通知（`ObjectProvider` 注入，端口未接线时静默；dedupKey `quota-warn:{scope}:{id}:{ym}:80` 对齐约束 F21） |

- **MQ outbox topic 保持不对接**：新增 topic 涉事件契约演进，随 GOV-13/NTF-01 通知域接线时启用；信封 schema 不动，应用内事件已形成可消费闭环（订阅方即 `QuotaWarnNotifier`）。

## 二、GAP-L5-4：rerank（MH-04）

| 交付物 | 说明 |
|--------|------|
| `ProviderClient.rerank`（新增） | mock 模式：query↔document 字符 bigram Jaccard 打分（确定性、离线可测）；HTTP 模式：Cohere 兼容 `POST /rerank`（`{model,query,documents}` → `results[{index,relevance_score}]`，兼容 `score` 字段回退） |
| `DefaultModelGateway.rerank`（替换 `NotImplemented`） | 全链镜像 embed：precheck → resolve(forced binding) → `ModelBudgetGuard` 预算预检 → 限流 acquire/reconcile → provider.rerank → 分数降序 + topN 截断 → `account` 记账（SUCCESS/ERROR/FAILOVER）→ `RerankResult(bindingId, Ranked[index,score])` |
| `accountError`（重构） | embed/rerank 错误记账合并为共用方法（ERROR / 0 token），消除重复 |

- 入参校验：`query` 空 / `documents` 空抛 `BAD_REQUEST`；`topN<=0` 全量返回。
- 请求 token 估算与 embed 同口径（字符 / 3），预算与限流共用。

## 三、测试与验证

| 测试 | 结果 | 覆盖 |
|------|------|------|
| `QuotaWarnTest`（system-biz，内存 KV 模拟 Redis） | 5/5 | 80% 越线三 scope 各发一次 / 月窗去重不再发 / 未越线不发 / 无上限配置不发 / notifier 去重键与变量 / 端口未接线静默 + 通知失败容忍 |
| `ProviderClientRerankTest`（provider 同包） | 4/4 | 相关文档得分更高 / 无重合 0 分 / 短输入 0 分 / 相同文本 1 分 |
| `DefaultModelGatewayRerankTest`（全链离线装配） | 4/4 | 排序 + topN 截断 / 用量行（SUCCESS + providerId 分摊）/ 入参校验 / embed 记账重构回归 |
| `P1L5CostAttributionContractTest` | 4/4 | rerank 断言反转（缺口 → 已落地） |

全量回归与提交前终验见提交信息（model-biz / system-biz / server）。

## 四、遗留

- MQ outbox 预警 topic：随 GOV-13 / NTF-01 接线（信封契约不动）。
- rerank 真实 provider 端点联调：需真实 API（当前 mock + Cohere 兼容实现，CI IT 闸门承接）。
- provider / apiKey 维度预算：随多租户预算管理批次评审。

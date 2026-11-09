# 定时任务互斥清单（OBS-05 / ADR-072）

> 日期：2026-11-10（M2 残余收口批次 B-2）
> 目标：多实例部署下，全部 `@Scheduled` 任务有明确的互斥机制或无副作用证明；验收口径「双实例下 SIEM 不收到重复记录」。

## 清单

| 任务 | 所在类 | 周期 | 副作用 | 互斥机制 | 说明 |
| --- | --- | --- | --- | --- | --- |
| 审计批量外送 | `AuditForwarder.forwardBatch` | fixedDelay 5s | 写 `AuditSink`（SIEM）+ `forwarded` 置位 | **PG advisory lock**（`pg_try_advisory_lock(hashtext('audit-forward-siem'))`，事务内取用与释放）+ `audit_forward_progress` 水位表（V221） | 未取得锁直接跳过本轮；水位记录本批最后一条 ID 供对账。**验收「双实例 SIEM 不重复」即此行** |
| 审计物理清理 | `AuditForwarder.purgeExpired` | cron 3:30 | DELETE forwarded 过期行 | **默认关闭**（`physicalPurgeEnabled=false`，GOV-09 前不得打开）+ cron 单时刻触发 | 打开后如多实例并发执行为幂等删除（同条件 DELETE），无重复风险 |
| 审计哈希链封印 | `AuditChainService.sealScheduled` | fixedDelay 60s | 回写 `prev_hash`/`hash` + `audit_seal` 锚 | **Redis SETNX**（`audit:seal:lock`，TTL 55s < 调度间隔，崩溃下一轮自动接管）；每链独立事务 crash-safe | GOV-09 批次 4 落地；单链内按 id ASC 幂等（hash IS NULL 部分索引） |
| 配额阈值预警 | `DefaultQuotaGuard.warnIfCrossed`（由 `consumeTokens` 触发，非定时） | 事件驱动 | 发 `QuotaThresholdExceededEvent` | **Redis SETNX** 月窗去重（`q:warn:{scope}:{id}:{ym}:80`，TTL 40d） | 预警失败仅日志；P2 批次 3 落地 |
| 运行排队清扫 | `QueuedSweeper`（M1 RT-06） | 周期 | 恢复超时 Run | 状态条件更新（仅 `QUEUED` 且超时的行） | 条件更新天然幂等，多实例并发不产生双重恢复 |
| 数据库迁移 | Flyway | 启动 | schema 变更 | Flyway 自带 `flyway_schema_history` 锁 | 框架内建 |

## 约定

1. 新增 `@Scheduled` 任务必须在本文登记副作用与互斥机制；互斥机制三选一：
   **PG advisory lock**（写库且依赖连接语义时）、**Redis SETNX**（跨实例去重去抖时）、**幂等性证明**（条件更新/删除使并发执行无额外副作用）。
2. advisory lock 必须在同一事务内取用与释放（MyBatis 每语句独立连接，离开事务即失效）；释放放 `finally`。
3. 验收测试：`AuditForwarderMutexTest`（互斥流程离线单测）+ 双实例真实不重复由 CI IT 承接。

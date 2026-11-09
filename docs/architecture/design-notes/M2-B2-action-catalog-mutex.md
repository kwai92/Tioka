# M2 残余收口批次 B-2：OBS-05 动作目录与互斥（ADR-072）实现覆盖说明

> 批次：M2 残余收口批次 B-2（接 B-1 / OBS-04a）
> 分支：`feature/m2-obs05-audit-mutex`（基线 main = `02a88da`）
> 收口范围：OBS-05 四子项全量（复盘报告缺口清单第 2 项；1.5 人天规划项）
> 硬约束遵守：迁移仅 `V221__m2_obs05_audit_catalog_mutex.sql`；不改 `deploy/compose.yml`；不引新运行时依赖；不动 ArchUnit 规则与 stored.rules

## 一、四子项 → 实现映射

| 子项（M2-00 OBS-05） | 实现 |
|------|------|
| 动作目录契约与校验 | `docs/contracts/audit/actions.yaml`（ADR-072 §2 登记制契约）：**84 个动作**全量登记（动作名/分类/资源类型/保留类别/SIEM 严重级别/负责模块），来源 = 79 个 `@Audited` 注解 + 5 个代码内固定动作（`permission.denied`/`http.access_denied`/`egress.denied`/`audit.query`/`audit.export`）。契约测试 `AuditedActionCatalogTest`（server，4 用例）：源码注解与目录**双向比对**——未登记动作测试失败（「先登记再使用」），目录结构（分类/保留类别枚举、module 归属）校验 |
| 委派链列提前建 | V221：`audit_event` ADD `root_principal`/`on_behalf_of`/`chain_json`/`grant_id`/`data_label`（列名沿用 ADR-039 §审计；MAS-01 到 M3 只填值） |
| 审计转发进度表 | V221：`audit_forward_progress`（channel PK / last_forwarded_id 水位 / batches / last_forwarded_at）；`AuditForwarder` 每批成功后推进水位（本批最后一条 ID） |
| 定时任务数据库锁与互斥清单 | `AuditForwarder.forwardBatch` 加 **PG advisory lock**（`pg_try_advisory_lock(hashtext('audit-forward-siem'))`，事务内取用 finally 释放；未取得锁直接跳过）——**验收「双实例下 SIEM 不收到重复记录」即此**；互斥清单落 `docs/architecture/ops/scheduled-task-mutex.md`（全仓 6 类定时任务逐一登记：advisory lock / Redis SETNX / 幂等性证明三选一） |

## 二、迁移号说明

规划登记 V135 → 实际落 **V221**（M2-00 第 9 节 V135 为 2026-11-07 新增规划号，实际占用延续 B-1 先例顺延；M3-00 §9 注记已同步「M3 后续新增迁移自 V221 起，先合者占号」口径——CCH-01 等 M3 工作项按合入顺序占用，M4 起号在 M4 开工 PR 按当时最高号重排）。

## 三、测试与验证

| 测试 | 结果 | 覆盖 |
|------|------|------|
| `AuditForwarderMutexTest`（system-biz，mock Repository） | 5/5 | 锁被占跳过（不读不写不解锁）/ 取锁转发 + markForwarded + 水位推进（本批末条）/ SIEM 失败中断不推水位 / Repository 异常仍释放锁（finally 语义）/ 空批次解锁不推水位 |
| `AuditedActionCatalogTest`（server 离线契约） | 4/4 | 目录结构合规（84 动作、枚举合法）/ @Audited 双向比对（先登记再使用）/ 固定动作登记 / module 归属 |
| 全量回归 | 见提交信息 | 全仓 `mvn test` |

## 四、遗留

- 双实例真实外送不重复：CI IT（需 Docker/PG，见互斥清单验收约定）。
- `details` 字段登记（ADR-072 §2 目录含 details 字段与类型）：当前 `@Audited.include()` 白名单已存在，逐动作类型化登记随「审计的审计」（M3 OBS-06）细化。
- SIEM 外送本体（AuditSinkAdapter 从 outbox 投递）：ADR-074/GOV-13 外送域接线时启用。

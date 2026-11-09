# P3 / GOV-09：审计哈希链封印与校验实现覆盖说明

> 批次：P3 批次 4（规划内项提前落地）
> 分支：`feature/p3-gov09-audit-chain`（基线 main = `aea3dfc`）
> 设计依据：ADR-025 §审计防篡改与外送（GOV-09 章节原文口径）
> 硬约束遵守：迁移仅 `V219__p3_audit_chain_seal.sql`（落 tioka-server 迁移目录）；不改 `deploy/compose.yml`；不引新运行时依赖；MQ 信封契约 `mq-envelope-v1.json` 不动（X13 不改判）；`physicalPurgeEnabled` 默认关闭守卫保持

## 一、ADR-025 口径 → 实现映射

| ADR-025 原文口径 | 实现 |
|------|------|
| 写审计时不算哈希，避免写入串行化 | `AuditService.write()` 路径零改动；封印由 `AuditChainService` 异步完成（`@Scheduled` 每分钟一轮） |
| 按 ID 顺序为新记录计算 `hash = sha256(prev_hash + 规范化后的记录)` | `Ids.newId` 为 Monotonic ULID（按 id 排序即时间序）；`listUnsealed` 按 `id ASC` 取 `hash IS NULL` 行；算法 `sha256Hex(prev + "\n" + canonical(row))`，链首 prev 取空串 |
| 每租户一条链 | 按 `COALESCE(workspace_id,'_system')` 分链；系统级记录（workspace 为空）归 `_system` 链（V219 注释、Mapper SQL、服务常量三处口径一致） |
| 每批末尾哈希写入封印表（可外送 SIEM 作外部锚点） | `audit_seal` 封印锚表 upsert（`workspace_key` PK / `last_id` / `last_hash` / `events_sealed` / `sealed_at`）；外送经 `AuditSinkAdapter` 从 outbox 投递，本轮不做（ADR 原文即如此） |
| 提供校验接口重算整条链 | `AuditChainService.verify(workspaceKey)`：重算整链逐条比对 prev_hash 链序与 hash 值，末端比对封印锚；REST 端点 `GET /api/system/audit/chain/verify` |

## 二、交付物

| 交付物 | 说明 |
|--------|------|
| `V219__p3_audit_chain_seal.sql` | `prev_hash`/`hash` 两列 V102 建表时已预留，不重复加列；补部分索引 `idx_audit_unsealed ON audit_event(id) WHERE hash IS NULL`（随封印推进自动收缩）+ `audit_seal` 封印锚表 |
| `AuditChainService`（system-biz，新增） | ① `sealPending(limit)`：Redis SETNX 锁（`audit:seal:lock`，TTL 55s < 60s 调度间隔，实例崩溃下一轮自动接管）多实例互斥 → 按 workspace 分链（LinkedHashMap 保持 id 序）→ 每链独立事务「读锚 → 逐条算哈希回写 → 链末锚 upsert」；② `verify(workspaceKey)`：重算校验，返回 `ChainVerifyResult`（intact / brokenAtId / expectedHash / actualHash / anchorHash / anchorMatch / 中文 summary）；③ `canonical`：17 字段固定顺序 `'|'` 连接、null→空串、时间戳统一 ISO-8601 UTC |
| `AuditEventMapper` / XML 扩展 | `listUnsealed`（hash IS NULL 按 id ASC）/ `markSealed` / `upsertSeal`（`ON CONFLICT` 累计封印数，幂等）/ `listSealedByChain`（含 `_system` CASE 分支）/ `getSeal` |
| `AuditEventRepository` 扩展 | 封印五方法透传封装——`AuditChainService` 依赖 Repository 而非直持 Mapper（遵守 ArchUnit 守护规则「application 只能依赖 Repository，禁止直持 Mapper」，`PersistenceArchTest` 复验） |
| `AuditController.verifyChain` | `GET /chain/verify`，权限 `AUDIT_READ`；非管理员强制本 workspace 且禁查 `_system` 链（与 `buildQuery` 租户隔离模式同源） |
| `AuditForwarder.purgeExpired` | 保持不动：`physicalPurgeEnabled` 默认关闭守卫 + 「GOV-09 前不得打开」注释——链落地后该守卫升级为**防断链前置条件**（契约测试守护） |

### crash-safe 设计要点

哈希回写与锚 upsert 放在**每链一个事务**（`TransactionTemplate` 编程式，规避 `@Transactional` 自调用代理陷阱）：进程崩溃或并发冲突时整链回滚、锚不动，下次运行从锚续链，不会出现「部分行已落 hash 但锚滞后」的断链窗口。

## 三、测试与验证

| 测试 | 结果 | 覆盖 |
|------|------|------|
| `AuditChainServiceTest`（system-biz 离线单测，内存 KV 模拟 Redis / mock 事务管理器与 Repository） | 12/12 | sha256 FIPS-180 已知向量 / canonical 固定字段序 + 时间戳 UTC 归一 / `_system` 链归一 / 封印推演（链式 prev 接续、跨租户分链、锚 upsert、锁释放）/ 从锚续链 / 锁被占跳过 / 空轮空转 / verify 完整链通过 / 篡改记录检出（brokenAtId 定位）/ 链序断裂检出 / 锚不一致检出 / 空链无锚视为完好 |
| `P3Gov09AuditChainContractTest`（server nio 源码守护，5 用例） | 5/5 | V219 封印结构与 `_system` 口径 / 封印算法与调度口径（sha256、SETNX 锁、每链事务、每分钟调度）/ Mapper 五方法 + XML 幂等 upsert / 校验端点租户隔离（`_system` 仅管理员）/ 物理清理守卫仍在（防断链） |
| 全量回归 | 见提交信息 | model-biz / system-biz / server 离线测试套件 |

端到端语义（真实 PG 上的链推演、崩溃恢复、多实例互斥）由 CI 的 IT 承接（同 L6 处置原则）。

## 四、遗留

- **SIEM 外送**：封印锚外送经 `AuditSinkAdapter` 从 outbox 投递——ADR 原文本轮即不包含，随 GOV-13/NTF-01 外送域接线时启用。
- **IT 用例**（需 Docker/PG）：真实 PG 链推演 + 崩溃恢复 + 多实例锁竞争，归 CI。
- **历史数据补封**：V219 上线时存量行 `hash IS NULL`，封印任务首轮自动补封（prev 取空串起链），无需人工干预；若需与历史日志外部锚点对齐，运维侧另行锚定。

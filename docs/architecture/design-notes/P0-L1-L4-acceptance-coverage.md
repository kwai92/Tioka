# P0 L1 + L4 验收测试覆盖文档

> 配套 `delivery/后续链路实施计划.md` §3.1（L1）与 §3.4（L4）。本批次（方案 A 冻结）重心是
> **验收测试 + 文档化**，不改动任何 `src/main`、不新增 Flyway 迁移、不改 `deploy/compose.yml`、
> 不引入 `tenantId`（权威隔离字段为 `workspaceId`）。

## 0. 本批次交付物

| 类型 | 路径 | 说明 |
| --- | --- | --- |
| 测试 | `tioka-server/src/test/java/com/tioka/server/runtime/P0L1AcceptanceIT.java` | L1 验收：会话锁 + 状态落库 + SSE 续传幂等 |
| 测试 | `tioka-server/src/test/java/com/tioka/server/gate/P0L4AcceptanceIT.java` | L4 验收：SSE 续传无重复 + 审批跨实例恢复不新开 Turn |
| 测试 | `tioka-module-runtime/.../kernel/events/EventIdTest.java`（追加） | 约束保护：重放幂等键 = threadId + EventId.seq，与 Last-Event-ID 同源 |
| 文档 | `docs/architecture/design-notes/P0-L1-L4-acceptance-coverage.md` | 本文件 |

## 1. L1 验收准则覆盖（§3.1 退出标准）

| 验收准则 | 覆盖落点 | 状态 |
| --- | --- | --- |
| 进程重启后状态可恢复 | `P0L1AcceptanceIT.statePersistedAndResumableAcrossReconnect`：items 经 `ItemRepository` 落 `runtime_item`，新 SSE 带 `Last-Event-ID="0"` 从 DB `listAfterSeq` 重放 seq 1/2/3 | 已由既有代码满足（测试固化） |
| 同 thread 并发串行化（DB 行锁证） | `P0L1AcceptanceIT.turnAcquireOnlyOnce`：两 worker 并发 `turns.acquire` 仅 1 胜（`RuntimeTurnMapper` 行锁串行化） | 已由既有代码满足（测试固化） |
| 同 thread 并发串行化（Redis 锁证） | `P0L1AcceptanceIT.sessionLockMutualExclusion`：复用 `ThreadLockIT` 模式断言 `RedisThreadLock.tryAcquire` 第二实例失败 / 释放后他人可 acquire | 已由既有代码满足（测试固化） |
| 契约测试绿 | `TurnStatusContractTest`（L1/L4 共用状态机）、`EventIdTest.snapshotReplayCursorUnifiesLastEventId` | 已由既有代码满足（测试固化） |
| `Last-Event-ID` 重放幂等可证 | `P0L1AcceptanceIT.statePersistedAndResumableAcrossReconnect`：断言重放集合恰为 {1,2,3} 无重复、不含游标自身 | 已由既有代码满足（测试固化） |
| 不新增任何 Flyway 迁移文件 | 本批次仅新增 `src/test` 与 `docs`；`git status` 无 `src/main` 改动，无新增 `V*.sql` | 本批次满足（约束保护） |

## 2. L4 验收准则覆盖（§3.4 退出标准）

| 验收准则 | 覆盖落点 | 状态 |
| --- | --- | --- |
| 事件 id 单调递增 | `P0L4AcceptanceIT.sseResumeNoDuplicates`：换实例带 `Last-Event-ID` 重连，断言所有事件 id `> cursor`；`EventId.compareTo` 单调契约 | 已由既有代码满足（测试固化） |
| 状态快照重放幂等 | `P0L4AcceptanceIT.sseResumeNoDuplicates`（续传无重复、不含 `Last-Event-ID` 自身）+ `EventIdTest` 幂等键约定 | 已由既有代码满足（测试固化） |
| 审批跨多次不新开 Turn | `P0L4AcceptanceIT.approvalSuspendResumeCrossInstanceNoNewTurn`：断言 `runtime_turn WHERE thread_id` 计数为 1；`WAITING_APPROVAL→(decide)→QUEUED→RUNNING→COMPLETED` | 已由既有代码满足（测试固化） |
| 跨实例恢复经 DB 重建审批卡 | 同上：在续跑实例 C 建立 SSE，断言收到 `turn.completed`；`runtime_approval` 行已决（非 PENDING）——验证 `ThreadEventsService` step1/step2 经 DB 重建 WAITING 与 card item | 已由既有代码满足（测试固化） |
| 契约测试绿 | `TurnStatusContractTest`（L1/L4 共用 `TurnStatus` 状态机） | 已由既有代码满足（测试固化） |

## 3. 约束保护（方案 A 冻结不变量）

- **唯一游标**：L1/L4 续传与重放统一使用 `EventId`（`seq` 即游标整数）；`Last-Event-ID` 头与 `after`
  参数收敛到同一 `EventId`（`ThreadEventsController` / `ThreadEventsService`）。不引入第三套游标。
- **不引入独立 checkpoint 表**：重放幂等键取 `threadId + EventId.seq`，复用 `runtime_turn` /
  `runtime_item` / `runtime_approval` / `runtime_thread`，无新增表。
- **不新增 Flyway 迁移**：本批次 `git diff` 仅含 `src/test` 与 `docs`，无 `V*.sql`、无 `src/main` 改动。

## 4. 残余韧性项（仅文档化，本批次不改生产代码）

受方案 A「不加表 / 不加迁移」约束，以下 G 缺口**本批次只记录、不改代码**，待 P2/L10 或设计调整再议：

| 编号 | 缺口 | 影响 | 本期处理 |
| --- | --- | --- | --- |
| L1-G01 | `StreamPump.subs` / `readPositions` 仍为 JVM 内存订阅态 | 硬崩溃后丢失在途 delta，无本地 checkpoint | 文档化；靠 Redis Stream 外置增量 + DB 重放兜底（TTL 内走 Stream，超出窗口回落 `listAfterSeq`） |
| L1-G04 | `EventStream` TTL（默认 1h，`streamMaxlen=10000`） | 超过窗口的 `Last-Event-ID` 无法从 Stream 重放 | 文档化；两段式续传已衔接 DB `listAfterSeq`；`expireAfterTurn` 为死代码（全仓无调用者），未清理以免改生产 |
| L1-G02 / L1-G03 | `MemoryItemSink.streaming` 内存态、`SignalBus.local` 本地信号 | 流式增量不落库、多实例信号不可达 | 属 P0 L1 后续补链范围，本批次以既有「落库 + Redis Stream 外置」现状验收，未新写补链代码 |

## 5. 运行说明（基础设施依赖）

- `P0L1AcceptanceIT`（tag `it`）、`P0L4AcceptanceIT`（tag `gate`）：依赖运行中的 **PostgreSQL + Redis**
  （`IntegrationTestSupport` / `GateCluster` 经 Testcontainers 拉起）。本环境若无 Docker/Testcontainers，
  **编译通过、运行阶段需跳过**，不判任务失败。
- `EventIdTest`：纯单元契约，**无需任何基础设施**，可直接 `mvn test` 运行。

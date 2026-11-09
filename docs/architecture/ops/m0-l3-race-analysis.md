# M0-L3 竞态分析：CrossUserIsolationIT 偶发失败（interrupt / steer）

> 状态：2026-11-10（M2 残余收口批次 B-3）。**只分析，不改代码。**
> 对象：`tioka-server/src/test/java/com/tioka/server/auth/CrossUserIsolationIT.java`
> 涉及用例：`interruptAndSteerOtherUsersTurnReturns404`、`interruptWinsRaceAgainstAcquire`（M0-L3 回归用例）。

## 1. 现象

M2-00 第 2 节第 17 条 / 34 号计划：`CrossUserIsolationIT` 在 CI 上偶发失败，集中于
interrupt / steer 相关断言；M2-00 第 84 行登记为 M0-L3（P1，0.5 人天，验收口径「连续 20 次通过」）。
失败形态（按历史代码推断）：越权 interrupt 后状态断言失败、或 interrupt 后 turn 既非 CANCELLED
也无 cancel 信号。

## 2. 根因（已确认，代码证据）

**根因不是测试 fixture 共享，也不是线程池复用，而是主代码 `TurnSignalService.interrupt` 对
「acquire 抢先」的单次读-判-写（TOCTOU）处理缺陷：CAS 失败静默返回。**

竞态双方（历史版本，commit 288e9eb 之前）：

1. 读路径：`TurnSignalService.interrupt` 先 `turns.findById(turnId)` 读状态，读到 `QUEUED`
   （证据：修复前代码，`git show 288e9eb` diff 左侧，原第 71〜79 行）。
2. 写路径 A（取消）：`turns.cancelQueued` 是 CAS——
   `RuntimeTurnMapper.xml:211` `UPDATE runtime_turn SET status='CANCELLED' ... WHERE id=? AND status='QUEUED'`。
3. 写路径 B（认领）：`TurnRepository.acquire`（`TurnRepository.java:53`）→
   `RuntimeTurnMapper.xml:73` `UPDATE runtime_turn SET status='RUNNING', lease_version=lease_version+1 ...`
   （同为状态条件 CAS，双方并发）。

缺陷：`interrupt` 读到 `QUEUED` 后调用 `cancelQueued`，若此时 acquire 已把状态置为 `RUNNING`，
CAS 影响行数为 0——**修复前的代码对 `n == 0` 直接 `return`**（静默放弃），既不重读、也不投递
cancel 信号。结果：

- turn 停在 `RUNNING` 且 `SignalBus` 无 cancel 标志；
- `interruptAndSteerOtherUsersTurnReturns404` 第 129 行断言 `CANCELLED` 失败；
- `interruptWinsRaceAgainstAcquire` 第 178〜181 行的双分支断言
  （`CANCELLED || (RUNNING && signalBus.isCancelFlag)`）两侧都不满足。

是否偶发取决于线程交错：两线程（测试自建线程 vs 容器内 worker / 或回归用例的两个 race 线程）
谁先提交 CAS，`cancelQueued` 返回 0 的窗口只在 acquire 恰好插在 findById 与 UPDATE 之间——
概率低但 CI 高频重跑必现，故为「偶发」。

steer 侧同类问题：修复前 `steer` 对 `QUEUED` 只读一次就 `appendSteerItem`（无二次确认），
与 interrupt/acquire 交错时行为不确定（修复已加二次确认，见下）。

**排除项**：

- 非共享 fixture：`seedQueuedTurn` 每个用例用 `Ids.newId` 造独立 ws/thread/turn；
- 非后台线程串扰：`IntegrationTestSupport.java:24` 显式关闭 QueuedSweeper
  （`tioka.runtime.queued-sweeper-enabled=false`），中继默认关（application.yml:126），
  compose 基础设施与用例无共享可变状态；
- 非异步断言时序：`interruptWinsRaceAgainstAcquire` 用 `CyclicBarrier` 对齐 + `CountDownLatch`
  等待两线程结束（15s 超时），断言在 `done.await` 之后，无时序假设。

## 3. 已落地的修复（确认在 main）

`288e9eb fix(M0-L3): close interrupt/steer race with acquire CAS miss`
（分支 `fix/M0-L3-isolation-race`，`git merge-base --is-ancestor 288e9eb main` 确认已入 main）：

1. `TurnSignalService.interrupt`（`TurnSignalService.java:71-141`）：CAS 失败（n==0）`continue`
   重读状态再分支，最多 3 轮；3 轮后仍非终态则按当前态投信号（RUNNING/WAITING_CHILD/INTERRUPTED
   → `publishCancel`）或抛 CONFLICT——**CAS miss 不再静默**。
2. `TurnSignalService.steer`（`TurnSignalService.java:153-162`）：QUEUED 分支写前二次
   `findById` 确认，状态已变则改走 RUNNING 信号或 CONFLICT。
3. IT 增补 `interruptWinsRaceAgainstAcquire`（`CrossUserIsolationIT.java:137-182`）：CyclicBarrier
   对齐 acquire 与 interrupt 两线程，断言两分支合法终态；commit message 记录本地 20×绿。

结论：**主代码修复与回归用例均已合入 main，M0-L3 的修复部分实质完成**；本批次登记项为
「竞态确认」——确认根因为 CAS-miss 静默返回，且修复已落地，剩余动作仅为 CI 侧按验收口径
（连续 20 次通过）跑绿后关闭条目。

## 4. 残余风险与建议（不改代码，供后续工作项参考）

1. **steer 仍存在窄 TOCTOU 窗口**（`TurnSignalService.java:155-159`）：第二次 `findById` 与
   `appendWithoutLease` 之间无 CAS，状态若在该窗口内被 acquire 置 RUNNING，steer item 仍会
   挂到已 RUNNING/CANCELLED 的 turn 上。影响：多一条 item、无状态破坏。建议（后续）：给
   `runtime_item.append` 增加 turn 状态条件，或改为对 `runtime_turn` 的条件 UPDATE 携带 steer 内容。
2. **interrupt 3 轮 CAS 全失败且状态反复横跳**（如 acquire/lease-reaper 反复 requeue）时抛
   CONFLICT（`TurnSignalService.java:140`）：语义合理，但调用方（API 层）应确认 409 透传而非 500。
3. **`isCancelFlag` 依赖 Redis 键**（`SignalBus.java:126-132`）：`publishCancel` 先 `SET ... EX 3600`
   再 PUBLISH，测试断言读同实例 Redis，时序安全；跨实例场景消费端靠 `register()` 时回读 cancel 键
   （`SignalBus.java:66-68`）兜底，语义自洽，无需改动。
4. **验收口径**：M2-00 第 84 行要求「该用例在 CI 连续 20 次通过」——建议在 CI 上以
   `-Dtest=CrossUserIsolationIT` 重复执行 20 轮后回填结论并关闭 M0-L3 条目。

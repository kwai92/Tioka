# M2 残余收口批次 B-6b：死信 list/replay/discard + REST 出口（ORC-07 后项 / FE-17 后端依赖）

> 批次：M2 残余收口批次 B-6b（生产代码 + 测试，零迁移）
> 分支：`feature/m2-b6b-deadletter`（基线 main = `01b1e04`）
> 收口范围：M2-00 第 75 行 ORC-07 后项（死信查询/重放/丢弃）、第 100 行 FE-17 后端依赖
> 硬约束遵守：**零迁移**（V130 `sys_dead_letter` 已含 `status CHECK IN ('OPEN','REPLAYED','DISCARDED')`、`handled_by/handled_at`，无需 V222）；不改 `deploy/compose.yml`；不引新运行时依赖；`mq-envelope-v1.json` 不动（`replayOf` 可选字段 M2-01 契约期已存在）；`sys_dead_letter` 为平台级技术表、无 workspace_id 列（Mapper `@InterceptorIgnore(tenantLine)`，ScopeLine 旁路），无空间隔离校验需求；未动 `docs/contracts/audit/actions.yaml` 与 tioka-module-orchestration。

## 一、设计依据（权威口径）

M2-01《消息与Outbox》ORC-07「管理端」节：

| 方法与路径 | 权限 | 说明 |
| --- | --- | --- |
| `GET /api/ops/dead-letters?consumer&status&page&size` | `orch:operate` | 列表；payload 只返回前 2000 字 |
| `POST /api/ops/dead-letters/{id}/replay` | `orch:operate` | 重放，状态置 REPLAYED，写审计 |
| `POST /api/ops/dead-letters/{id}/discard` | `orch:operate` | `{reason}`，状态置 DISCARDED |

重放语义按本批任务口径执行：调用既有投递通道 `IdempotentDeliveryService#deliver` 重新投递 payloadJson（reconsumeTimes 归零，不改信封格式），成功 → REPLAYED，仍失败 → 保持/回 OPEN 并累加 attempts。（M2-01 原文建议「重写一条 sys_outbox、新 eventId + replayOf」走异步中继；本批按同步重投实现，行为等价且失败可即时反馈，信封契约零改动。原 eventId 若已被 sys_consumed 记录，消费幂等自动跳过副作用。）

## 二、生产交付物

| 文件 | 内容 |
|------|------|
| `SysDeadLetterRepository`（扩展） | `findById` / `list(consumer,status,offset,size)`（create_at 倒序）/ `count` / `markReplayed` / `markDiscarded` / `markRetryFailed`——终态流转统一条件更新（`WHERE status='OPEN'`），重复流转条件不命中返回 false，天然幂等；status 常量 OPEN/REPLAYED/DISCARDED |
| `DeadLetterOpsService`（新增） | `list`（分页钳制 1..100）/ `replay(id, handledBy)`：仅 OPEN 可操作（否则 NOT_FOUND/CONFLICT），deliver 失败 → `markRetryFailed`（error 截 2000 字、attempts+1、保持 OPEN）并抛 CONFLICT；`discard(id, handledBy, reason)`：reason 落 error 列前缀 `[discarded:<reason>]`（表无 reason 列，零迁移处理） |
| `DeadLetterOpsController`（新增，`com.tioka.infra.messaging.api`） | 上述三端点；类级 `@RequirePermission(Permissions.ORCH_OPERATE)`；列表/操作视图 payload 截前 2000 字；REST 放 infra-biz（仿 orchestration-biz `OrchRuleController` 惯例），规避 ArchUnit `serverDependsOnlyOnApi`（server 禁依赖 `..infrastructure..`） |

## 三、测试交付物

| 测试类 | 覆盖点 | 结果 |
|--------|--------|------|
| `DeadLetterReplayIT$ReplayAndDiscard`（原 @Disabled 骨架重写，3 个跳过用例转实 + 新增 1 例） | ① list(OPEN) 分页过滤 + REST 视图 payload 截 2000 字；② 重放重投恰好一次（reconsumeTimes=0）→ REPLAYED、handledBy/handledAt 回填 + 幂等（已 REPLAYED 重复重放抛 CONFLICT，不再投递）；③ 重放仍失败 → 保持 OPEN、attempts 累加、error 回写、不置终态；④ 丢弃 → DISCARDED、原因入 error、不在 OPEN 列表、重放被拒 | **4/4 通过** |
| `DeadLetterReplayIT` 外层 5 用例（不变） | 死信写入链路回归 | 5/5 通过 |
| `DeadLetterOpsControllerTest`（新增，MockMvc standalone 离线） | REST 路由/分页参数/payload 截断；replay、discard（带 reason / 无 body）服务编排；权限由运行时拦截器 + ArchUnit `controllersMustDeclarePermission` 守护 | **4/4 通过** |

## 四、运行验证记录

- `mvn -pl tioka-modules/tioka-module-infra/tioka-module-infra-biz -am test -Dtest='DeadLetterReplayIT,DeadLetterOpsControllerTest'` → **13/13 通过，0 跳过**（DeadLetterReplayIT 9 = 外层 5 + 嵌套 4；DeadLetterOpsControllerTest 4）
- 架构测试无新违例（见提交报告）

## 五、审计动作处理（登记待主理人统一增补）

`AuditedActionCatalogTest`（ADR-072 §2 登记制）要求 @Audited 动作先登记 `docs/contracts/audit/actions.yaml`；该文件本批禁改（并行批次）。故 replay/discard 端点**暂未挂 @Audited**，登记两个待增补动作：

- `deadletter.replay`：category=EXECUTION、retentionClass=BIZ、module=tioka-module-infra（参考 ADR-072 第 50 行「死信重放」归 EXECUTION）
- `deadletter.discard`：category=EXECUTION、retentionClass=BIZ（破坏性操作）、module=tioka-module-infra

主理人增补 actions.yaml 后，在 `DeadLetterOpsController#replay/#discard` 补挂 `@Audited(action="deadletter.replay/discard", resourceType="sys_dead_letter", resourceIdArg="#id")`。目前操作者留痕由 `handled_by/handled_at` 列回填承担。

## 六、未尽事项

1. 审计注解（见上节，等 actions.yaml）。
2. `consumer.max-attempts=1` 时重放失败会走 deliverOnce 末次分支写新死信而非上抛，重放失败无法被识别——默认 3 满足前提（≥2），已在 `DeadLetterOpsService` javadoc 注明运维不得配置为 1。
3. 真库全链路（落行→重放→丢弃）容器 IT 归 CI failsafe 承接（同 B-5 登记）。
4. M2-00 台账回写由主理人统一处理（本批未改 M2-00）。

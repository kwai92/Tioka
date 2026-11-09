# M2 残余收口批次 B-6a：编排规则自动暂停/恢复（ORC-07 缺口之一）

> 批次：M2 残余收口批次 B-6a（生产代码 + 契约 + 测试）
> 分支：`feature/m2-b6a-rule-pause`（基线 main = `01b1e04`）
> 收口范围：M2-00 第 75 行 ORC-07「规则自动暂停」缺口；解锁 FE-17（第 100 行「规则暂停与恢复」）后端能力
> 硬约束遵守：零迁移（V131 列已存在，未用 V222）、不改 `deploy/compose.yml`、不引新运行时依赖、MQ 信封契约不动、隔离字段 workspaceId

## 一、行为规格与实现决策

规格来源（不重新裁决）：M2-01「规则自动暂停」第 165–167 行、ADR-020 第 36 行「同一规则连续 5 次 Run 失败则自动暂停规则」、04-推进规划第 60 行、M2-06 PK-17。要点：Run 以 `FAILED` 结束时 `consecutive_failures + 1`，`SUCCEEDED` 清零；达到 5 → 自动暂停，通知规则负责人；恢复为显式操作。

| 决策点 | 选择 | 依据 |
|--------|------|------|
| 失败阈值 | `tioka.orch.rule.pause-threshold`，默认 5 | ADR-020 / M2-01 明文 5，application.yml 登记 `tioka.orch.rule.pause-threshold: 5` |
| 暂停状态存储 | 复用 `orch_rule` 现有列 `paused_at` / `paused_reason` / `consecutive_failures` | V131__m2_rule_pause_trace.sql 已建列，**零迁移，V222 未占用**（M4 起号无需顺延） |
| 失败计数统计点 | Run 终态写点挂钩 `RulePauseService#recordRunOutcome(ruleVersionId, terminalStatus)`，共 5 处：`ReceiptApplyService#publishRunFinished`（回执收口汇聚点）、`TurnResultWritebackService#handleFailed` 终态分支（Turn 重试耗尽）、`OrchDispatchService#revokeAndFail`/`#failMandate`、`OrchApprovalHandler#fail` | 这些是 Run `FAILED`/`SUCCEEDED` 全部生产写点；CANCELLED/UNCERTAIN 不计数（M2-01 只定义 FAILED/SUCCEEDED 语义） |
| 暂停期跳过派发 | `OrchEngineRepository#findActiveRulesForEventType` 过滤 `paused_at IS NOT NULL` | 该方法是 ingest 派发入口（`OrchIngestService#ingest:74`），RulePauseIT 原验收口径第 2 条即指此方法 |
| 暂停事件出口 | ① 审计 `orch.rule.pause`（`AuditApi#record`，actor=system，与业务同事务）；② 通知端口 `NotificationPort`（约束 F21，`ObjectProvider` 可选注入，未接线静默跳过；recipient=规则 ownerId，template=`orch.rule.auto_paused`，dedupKey=`orch-rule-paused:{ruleId}`）；③ WARN 结构化日志 | M2-00 F21：告警一律经通知端口；`QuotaWarnNotifier` 为既有同款用法先例 |
| 恢复入口 | 应用服务 `OrchRuleAppService#resumeRule` + REST `POST /api/orch/rules/{id}/resume`；`@RequirePermission(ORCH_WRITE)` + `@Audited(action="orch.rule.resume")` | 权限/审计注解风格对齐同 Controller 既有端点；恢复清 `paused_at/paused_reason` 并清零 `consecutive_failures` |
| 审计动作登记 | actions.yaml 增补 2 条：`orch.rule.pause`（CONFIG/orch_rule/BIZ/MEDIUM，暂停属系统熔断行为，严重级别 MEDIUM）、`orch.rule.resume`（CONFIG/orch_rule/BIZ/LOW） | OBS-05 登记制；`AuditedActionCatalogTest` 双向比对天然兼容（`everyAuditedActionIsRegistered` 扫描 @Audited 注解——新增 `orch.rule.resume` 已登记；`orch.rule.pause` 为代码内固定动作，登记后多余条目仅告警不失败） |
| 写入方式 | 暂停/恢复/计数用 `UpdateWrapper` 显式 set（`OrchRuleRepository#markPaused/#clearPause/#setConsecutiveFailures`） | MyBatis-Plus 默认非 null 更新策略会丢弃置 null 字段（恢复必须显式 set null） |
| 幂等 | 已暂停规则收到 FAILED 不再累加/重复暂停/重复审计通知；SUCCEEDED 仅在计数 >0 时写库 | 防止暂停期残留 Run 终态回写重复触发 |

关键文件：`orchestration-biz` 新增 `application/pause/RulePauseService.java`；`OrchRuleDO` 补 3 字段映射（V131 列）；挂钩 5 处终态写点（均为构造器注入 `RulePauseService`，无环——它只依赖 `OrchRuleRepository` + `AuditApi` + `ObjectProvider<NotificationPort>`）。

## 二、测试清单（离线，纯 JUnit5 + Mockito，同 HopsIT 惯例）

| 测试类 | 位置 | 覆盖点 | 结果 |
|--------|------|--------|------|
| `RulePauseServiceTest`（新增） | `tioka-module-orchestration-biz/src/test/.../application/pause/` | 未达阈值仅累加；第 5 次触发 markPaused + 审计（action/resourceType/resourceId/actor/workspaceId/details 全断言）+ 通知（recipient/template/dedupKey 全断言）；SUCCEEDED 清零；清零后重新起算；CANCELLED/UNCERTAIN 不计数；已暂停幂等；通知端口未接线仍暂停+审计；审计/通知异常降级不阻断暂停；resume 清暂停；未知 rule NOT_FOUND；未暂停 resume BAD_REQUEST；未知 version/rule 静默跳过 | **13/13 通过** |
| `RulePauseIT`（转绿重写，原 @Disabled 骨架） | `tioka-server/src/test/.../orch/` | 验收口径 4 条：① 连续失败达阈值 → markPaused + 审计 orch.rule.pause + 通知；② 暂停期 `findActiveRulesForEventType` 不返回（真实 OrchEngineRepository + mock mapper），未暂停返回；③ `resume` 清暂停后重新参与派发；④ 未达阈值不受影响；另附 resume 防御与 SUCCEEDED 清零 | **6/6 通过（0 跳过）** |

## 三、运行验证记录

- `mvn -pl tioka-modules/tioka-module-orchestration/tioka-module-orchestration-biz -am test` → 模块全量 **23/23 绿**（含新 RulePauseServiceTest 13 项）
- `mvn -pl tioka-modules/tioka-module-orchestration/tioka-module-orchestration-biz -am test -Dtest='HopsIT,DeadLetterReplayIT' -Dsurefire.failIfNoSpecifiedTests=false` → HopsIT **4/4 绿**（DeadLetterReplayIT 归属 infra-biz，另跑）
- `mvn -pl tioka-modules/tioka-module-infra/tioka-module-infra-biz -am test -Dtest='DeadLetterReplayIT' -Dsurefire.failIfNoSpecifiedTests=false` → **5 通过 / 3 骨架跳过**（与 B-5 基线一致，未破坏）
- `mvn -pl tioka-server -am test -Dtest='PersistenceArchTest,TiokaArchitectureTest,M2ArchitectureTest' -Dsurefire.failIfNoSpecifiedTests=false` → ArchUnit **22/22 绿**，无新违例
- `mvn -pl tioka-server -am test -Dtest='AuditedActionCatalogTest' -Dsurefire.failIfNoSpecifiedTests=false` → **4/4 绿**（yaml 增补 orch.rule.pause / orch.rule.resume 与双向比对兼容）
- 默认 `mvn test` 不受影响：`*IT.java` 由根 pom surefire excludes 排除；新增 `RulePauseServiceTest` 为普通 `*Test`，随默认构建执行

## 四、未覆盖项（登记台账，后续批次承接）

1. **连续失败第 3 次的 tell 规则通知**（M2-01 第 167 行引用 ADR-020 修订第 7 条）：本批只实现达阈值暂停时的单次负责人告警，第 3 次失败按规则 `tell` 策略的中间告警未做。
2. **REST 端点 + 审计 AOP + 权限端到端**（真实容器全链路）：恢复端点离线覆盖到应用服务层；`PermissionAspect`/`AuditedAspect` 拦截链路由 CI failsafe/容器 IT 承接，动作名已登记由 `AuditedActionCatalogTest` 契约校验。
3. **`RuleView`/`GET /api/orch/rules` 未暴露暂停字段**：FE-17 前端展示暂停态（pausedAt/pausedReason/consecutiveFailures）需要读取接口返回——本批只做行为闭环，视图字段随 FE-17 前端联调批次补。
4. **自动暂停的「恢复前重复事件静默丢弃」落库口径**：暂停期事件经 `findActiveRulesForEventType` 过滤后按既有「无规则 FILTERED」路径落库，未新增专门的 disposition 原因（如 M2-01 提到的 `decision=UNAVAILABLE, reason="rule_paused"`——该口径针对事件命中后决定阶段，本批在派发入口前置过滤，语义等价但 reason 不落 decision 表；如需精确对齐 M2-01 口径需在 decide 层再加判断，登记待裁）。
5. **并发计数竞态**：`consecutive_failures` 为读-改-写，未加行锁；单 worker 消费（MQ 顺序消费）场景下成立，多实例并行终态回写极小概率漏计——漏计只延后暂停不误暂停，登记为已知限制。

## 五、M2-00 回写（由主理人统一回写，本批不改 M2-00）

- ORC-07（第 75 行）：建议标注「B-6a 规则自动暂停完成（阈值 5 熔断 + 恢复入口 + 审计/通知，离线 19/19 绿；遗留：第 3 次 tell 中间告警、容器端到端）」。跳数熔断（B-5）与死信入口缺口不变。
- FE-17（第 100 行）：后端「规则暂停与恢复」解锁；前端需 RuleView 暴露暂停字段后联调。

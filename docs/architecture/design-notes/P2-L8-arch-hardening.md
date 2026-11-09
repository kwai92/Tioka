# P2-L8 ArchUnit / 契约加固实现记录

> 归属：P2 缺口池 / L8。承接 `P1-L8-acceptance-coverage.md`（P2 加固建议 ①~④）。
> 约束：纯测试代码，无迁移、无新依赖；`archunit.properties` 与既有 `archunit_store/` 未改动。

## 1. 改动清单（分支 feature/p2-l8-arch）

| 文件 | 改动 |
|---|---|
| `tioka-server/src/test/java/com/tioka/server/arch/LayeredDependencyArchTest.java`（新增，3 用例） | ① **全局「biz 仅依赖他模块 api」规则**：对 15 个域模块逐一断言 `com.tioka.<m>.(application\|infrastructure\|domain\|kernel)..` 不得依赖其他模块同层包（`*.api..`、`com.tioka.framework..`、本模块自身合法），失败汇总报告；② **skill/eval 双向隔离**：两者互不依赖；③ **skill/eval 禁止反向依赖 `com.tioka.server..`**。`allowEmptyShould(true)` 防误报；`DO_NOT_INCLUDE_TESTS` 排除测试代码。 |
| `tioka-modules/tioka-module-runtime/tioka-module-runtime-api/src/test/.../RuntimeEventSchemaContractTest.java`（+130 行，5→7 用例） | **运行时 SSE 样本校验**：`runtimeEmittedSamplesAllValidate` 对事件工厂（StreamPump 原样下发的 JSON）生成的**全部** `RuntimeEventType` 样本逐一过 `schema/runtime-event-v1.json` 校验；`corruptedSamplesAreRejected` 7 个损坏样本（缺 payload/非法 type 等）断言校验失败。补齐 P1 登记的「仅静态校验偏松」缺口。 |
| `tioka-server/src/test/java/com/tioka/server/arch/P1L8ArchSuiteIntegrityTest.java` | 套件完整性守护加入 `LayeredDependencyArchTest.java`（7 个 ArchUnit 类） |

## 2. 关键结果

- **全局 biz-only-api 规则零违例**：现有代码源级完全合规（biz 间仅经 api 直连），**无需冻结流程**——`archunit.properties`（allowStoreCreation/Update=false）与 stored.rules 保持原样，规则立即全量生效。
- SSE 运行时校验通过：全部事件类型的工厂样本与 schema 一致（此前仅有静态枚举比对，现堵住「字段改了但枚举没动」的漂移窗口）。

## 3. 冻结库清零排期（遗留唯一项）

`stored.rules` 现状：15 条冻结规则，14 条零存量，仅 `noTruncatedUuid` 有 3 条存量：
| 位置 | 处置建议 |
|---|---|
| `McpAdminService`（约 L251） | 修复：改用全格式 UUID 或非 UUID 随机标识生成器（`Ids`），随后删除冻结条目 |
| `TokenService.issue`（约 L35） | 修复或永久豁免：若属「随机令牌非标识」语义，建议改为显式豁免名单（同 SecretStore 白名单模式）而非冻结 |
| `OidcFederationService.resolveOrProvision`（约 L132） | 同上 |

排期：随下一次触及上述三类的功能批次顺手修复（零风险机械改动），不建议单开批次。

## 4. 与 P1-L8 P2 建议的对应

| P1 建议 | 状态 |
|---|---|
| ① 全局 biz-only-api 规则（配冻结） | ✅ 本轮落地，零违例免冻结 |
| ② skill/eval 边界 | ✅ 双向隔离 + server 反向依赖禁止 |
| ③ 运行时 SSE 逐帧校验 | ✅ 全类型样本 + 损坏样本拒绝 |
| ④ 冻结库清零排期 | 📋 排期已定（上节），修复随功能批次执行 |

## 5. 验证（本机实测）

- L8 全套 50/50 绿：Tioka 11 / M2 5 / M3 8 / **LayeredDependency 3（新）** / ModuleDependency 5 / Persistence 6 / SecretStore 1 / P1L8 完整性 4（含 7 类守护）/ RuntimeEventSchema 7（含 2 个新用例）。
- 禁区检查：`archunit.properties`、`archunit_store/`、`src/main`、`deploy/compose.yml` 全部未动。

# M3 批次 C-1a：MH-08 Model Hub 对象版本化 / 别名 / 系统别名迁移 / 调用记录带版本（GOV-12 §9）实现覆盖说明

> 批次：M3 批次 C-1a（MH-08）
> 分支：`feature/m3-c1a-mh08`（基线 main = `f4b13a8`）
> 收口范围：Model Hub 四类对象（provider / binding / alias / routing_plan）接入 ConfigVersionApi；`model_logical` → `model_alias` 重命名 + `purpose/target_type/target_id` 契约列；存量对象第 1 版登记与指针回填；`model_default_plan` 行转 `sys.*` 系统别名；调用记录携带对象版本；ModelCatalog 只读接口（M1 约束 F11）
> 硬约束遵守：全程无 tenantId（ADR-029，仅 workspaceId）；业务 ID 一律 `Ids.newId()`（ENG-*）；不引新运行时依赖；不改 `deploy/compose.yml`；不做 MH-06 路由模式；不动 M3-00 与 34 号文件；`sys.*` 别名不在 PersistenceProperties 豁免语义内建空间隔离（model_alias 为系统级对象，登记豁免清单）

## 一、迁移计划（ADR-041 §9 对照）

| 号 | 内容 | 破坏性 | 备注 |
|------|------|--------|------|
| V150（已落，基线） | GOV-12 框架三表（cfg_draft/cfg_version/cfg_change）+ 四投影表指针列；`model_logical` 重命名**留给 MH-08** | 否 | |
| V151 | `V151__Mh08AliasRenameAndV1Registration`（**Java 迁移**，自包含 JDBC，仿 V104/V145）：① `ALTER TABLE model_logical RENAME TO model_alias` + ADD `purpose/target_type/target_id` + `idx_model_alias_name`；② 读存量 `model_default_plan` 行 → 转 `sys.<purpose>` 别名（ON CONFLICT DO NOTHING）+ `cfg_change` 变更记录；③ 四类对象逐行登记 `cfg_version` 第 1 版（幂等：已有版本仅回填指针）+ 回填投影表指针列 + `cfg_change` | 否（重命名保数据） | V145 Java 迁移先例；因 server 对 biz 模块仅 runtime scope，迁移不 import model-biz，spec 常量与构造复用 model-api 的 `ModelHubSpecs`（单一事实源） |
| V156 | `V156__m3_drop_model_default_plan.sql`：`DROP TABLE IF EXISTS model_default_plan` | **是** | 前置 V151（sys.* 转换完成）；**演练登记：上线前须在预生产库演练（先 V151 校验 sys.* 转换完整 → 备份 model_default_plan → V156）** |
| V222 | `V222__m3_mh08_usage_version_columns.sql`：`model_usage` ADD `binding_version_no / alias_version_no / plan_version_no` | 否 | ADR 原号位 V153 让渡给 MH-11（model_usage 其余列），本批顺延至 V222（当前最高 V221） |
| V222 起顺延 | 本批后续新增迁移 | — | 已占用 V222 |

ADR §9 差异处理：① `model_logical` 重命名原登记于 V150 内容行，但 V150 已落库、无空闲 <V151 号 → 并入 V151 Java 迁移（V145 先例）；② `model_usage` 版本列原登记 V153 → 顺延 V222，V153 留给 MH-11；③ 契约副本同步落 `docs/contracts/db/`（V151/V156/V222，与迁移内容一致，说明文件仿 V145）。

## 二、objectType 命名（与 GOV-12 框架映射一致）

| 对象 | objectType | 投影表 | 指针列 |
|------|-----------|--------|--------|
| provider | `model.provider`（常量 `ModelHubSpecs.OT_PROVIDER`） | model_provider | current_version_id / current_version_no |
| binding | `model.binding` | model_binding | 同上 |
| alias | `model.alias` | model_alias | 同上 |
| routing_plan | `model.routing_plan` | model_routing_plan | 同上 |

`CfgStore.updateTypeTablePointer` 与 `CfgProjectionPointerMapper.updateAlias` 同步接线（`updateLogical` 改名 `updateAlias`，XML UPDATE `model_alias`）。

## 三、范围 → 实现映射

| 项 | 实现 |
|------|------|
| 版本化接入 | `ModelConfigVersionService`（model-biz）：四类对象 `available/saveDraft/publishCurrent/publishDraft/rollback/current`；spec 构造统一走 `ModelHubSpecs`（provider/binding/alias/plan spec；canonicalJson TreeMap 排序 + SHA-256 contentHash）；行→spec→`ConfigVersionApi.saveDraft→publish`（STALE_REVISION 409 由框架层保证） |
| 别名重命名 | `ModelAliasDO`（@TableName model_alias，+ purpose/target_type/target_id/current_version_id/current_version_no）→ `ModelAliasMapper`（BaseMapper + 手写 `findByName` XML，含 `deleted=FALSE`）→ `ModelEntitiesRepository.putAlias/hasAlias/getAlias/aliases/findAliasByName` → domain `ModelEntities`（record `ModelAlias`）；三层封装，application 层不直持 Mapper（ArchUnit 守护） |
| `sys.*` 系统别名 | `ModelHubSpecs.sysAliasName(purpose)="sys."+lower`；V151 将存量 default_plan 转为 `alias_sys_<purpose>` 别名（target=routing_plan 或 binding）；`ModelBindingResolver.resolvePlan` 解析顺序：显式 planId → `findAliasByName(sysAliasName(purpose))` → target routing_plan/binding → published plan fallback → first enabled binding |
| 调用记录带版本 | `ModelBindingResolver.Resolution` 携带 `aliasId/aliasVersionNo/planVersionNo`；`DefaultModelGateway` 经 `carryContext`/`ofBinding` 6 参透传；`UsageRecordCommand` + `UsageRecorder` + `ModelUsageDO` 落 `binding_version_no/alias_version_no/plan_version_no`（无别名来源时 alias 版本为 null，合成 HARD 时 plan 版本为 null） |
| ModelCatalog（F11） | model-api `ModelCatalog` 只读接口：`currentProvider/currentBinding/currentAlias/aliasByName/currentPlan` + 快照 record（含 `currentVersionNo`；ProviderSnapshot 不含密钥引用）；model-biz `DefaultModelCatalog` 30s TTL 缓存实现 |
| Controller | `ModelAdminController`：GET/POST `/aliases`（@Audited `model.alias.create`）、@Deprecated `/logical-models` 委托 createAlias（20+ server IT 兼容）、移除 `/default-plans`、新增 `/configs/{objectType}/{objectId}/draft|publish|rollback|current`（publish @Audited `config.publish`、rollback @Audited `config.rollback`；publish 端点 `publishDraft().or(publishCurrent())`） |
| 审计登记 | `docs/contracts/audit/actions.yaml` 新增 `config.publish / config.rollback / model.alias.create`；移除失效条目 `model.default_plan.upsert`（端点已删，AuditedActionCatalogTest 仅告警不失败的 stale 清理） |
| ArchUnit | `M3ArchitectureTest` 新增两条普通 noClasses 规则（非 FreezingArchRule）：① `cfgInfraOnlyInGovernance`——governance/server 包外不得依赖 Cfg* 前缀类（执行侧不直读 cfg_*）；② `cfgVersionRowsAreImmutable`——CfgVersionMapper 不得有 update/delete/upsert/truncate 方法（cfg_version 不可更新） |

## 四、实现决策

1. **V151 自包含**：tioka-server 对 biz 模块是 runtime scope（编译期不可见），Java 迁移不能 import model-biz；spec 构造逻辑上移到 model-api `ModelHubSpecs`（provider/binding/alias/plan spec、canonicalJson、contentHash、sysAliasSeed），V151 与业务侧共用同一事实源。
2. **ENG-05（禁 new ObjectMapper）**：`ModelHubSpecs` 全部方法参数化 ObjectMapper（首参 mapper）；`ModelConfigVersionService` 用实例方法 + `this::` 方法引用；V151 位于 `db.migration` 包（ArchUnit AnalyzeClasses 不分析），自持 MAPPER。
3. **幂等**：V151 每类登记先 `hasAnyVersion` 判断，已有版本仅回填指针（可重放）；sys.* 转换 ON CONFLICT DO NOTHING。
4. **ModelPurpose 校验**：createAlias 校验 name 必填、重名 CONFLICT、purpose 合法、targetType/targetId 成对且 target 存在、purpose 与 binding kind 匹配（EMBEDDING→embedding、RERANK→rerank、其余→chat）。
5. **`createLogicalModel` 保留为 @Deprecated 委托**（`model.logical.create` 审计动作保留），20+ server IT 与 AuditCoverageIT 白名单不受影响。
6. **workspace 豁免**：`PersistenceProperties.workspace-scope-exempt-tables` 清单 `model_default_plan/model_logical` → `model_alias`（系统级 Model Hub 对象，deny-by-default 拦截器要求）。

## 五、测试清单

| 测试 | 结果 | 覆盖 |
|------|------|------|
| `ModelAliasIT`（model-biz，离线） | 3/3 | 别名 CRUD + 校验矩阵（重名/用途不匹配/target 存在性）；V151 模拟：sys.chat→plan（别名版本 2、计划版本 5、binding 版本 3）+ sys.embedding→binding 合成 HARD（别名版本 4、计划版本 null）；@Deprecated createLogicalModel 变别名 |
| `UsageCarriesVersionIT`（model-biz，离线） | 2/2 | sys.rerank→plan 真实链路 usage 行落 `binding=2/alias=7/plan=3`（按 seed 版本）；强制 binding 时 binding 版本落、alias/plan 为 null |
| `ModelHubSpecsTest`（model-biz，离线） | 4/4 | sysAliasName 往返；sysAliasSeed 映射（id/name/purpose/targetType/targetId）；canonicalJson 键排序与 contentHash 稳定；isSupportedType |
| `M3C1aMigrationContractTest`（server，离线 nio 守护） | 7/7 | V151 重命名+契约列+无 tenantId；V151 四类 objectType 常量登记+cfg_version/指针/cfg_change；V156 破坏性删表+演练登记；V222 三版本列；契约副本一致；三层封装移除（Resolver 经别名解析、无 ModelDefaultPlan 依赖）；调用链带版本（Command/Recorder/DO/Catalog） |
| `M3C1aMigrationIT`（server，Testcontainers pgvector:pg16） | 1/1 | 两段 Flyway（target 150 → seed → 全量）后 DB 断言：表重命名、契约列、sys.* 别名行、cfg_version 第 1 版、指针回填、cfg_change 记录、model_default_plan 已删（V156） |
| governance-biz 既有 ConfigVersion IT | 3/3 | ConfigVersionLifecycleIT / ConfigRollbackIT / ConfigRefreshAcrossInstancesIT（CfgStore/CfgProjectionPointerMapper/getDraft 改动后回归） |
| model-biz surefire 全量 | 33/33 | 含既有 ModelRouterFreezeTest、DefaultModelGatewayRerankTest（改用 ModelTestSupport + createAlias） |
| ArchUnit 四件套（server） | 全绿 | 含 M3ArchitectureTest 新增 2 条规则；PersistenceArchTest 豁免登记生效；TiokaArchitectureTest ENG-05/UUID 规则通过 |
| `AuditedActionCatalogTest`（server） | 4/4 | 新登记 3 动作双向校验通过 |
| server 全量 surefire | 108 tests，**仅剩基线失败** | 失败均为 M2 B-4（ccd9ee4）connector 遗留：ModuleDependencyArchTest.businessMustNotCreateRawHttpClient（HttpOAuthTokenClient）、RuntimeApiContractTest 9 errors（DefaultUserTokenVault 无默认构造 → Spring 上下文失败）。本批次改动零新增违例 |

## 六、未覆盖项（如实登记）

- **V156 演练**：破坏性删表未在本机预生产演练，登记为上线前演练待办（步骤见 §一 V156 行）。
- **controller HTTP 层**：`/configs/*` 端点无 @SpringBootTest（约束禁用）；契约测试源码守护 + ModelConfigVersionService 单测覆盖；HTTP 参数绑定归 CI IT。
- **DefaultModelCatalog TTL 缓存的并发失效**：30s TTL 已覆盖一致性窗口，跨实例刷新经 ConfigVersionRefreshBus 的主动失效未接入 Catalog（GOV-12 框架能力具备，MH-08 范围外）。
- **基线遗留失败**（非本批范围）：connector 模块 M2 B-4 引入的 HttpOAuthTokenClient（ArchUnit 违例）与 DefaultUserTokenVault（Spring 装配失败）导致 server 1+9 个测试失败，已如实上报，归 B-4 收口责任方。
- **MH-11（model_usage 其余列）**：V153 号位保留，未动。

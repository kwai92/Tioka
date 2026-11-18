# Foundation 中台化：能力归属与重复概念清单

> 建立日期：2026-11-16。机器可读版本：[`capability-ownership-v1.json`](capability-ownership-v1.json)，
> 守门脚本：[`scripts/verify-capability-ownership.mjs`](../../scripts/verify-capability-ownership.mjs)。
> 本文回答「哪些能力归中台、哪些归业务应用、哪些重叠必须建适配层」，**不修改任何业务代码**。

## 1. 为什么需要这份清单

Foundation 要成为整个 Tioka 的中台底座，后续业务应用（如 `tioka-ai`）都应从它派生。但按代码核实，
当前接线并不是「底座」：`tioka-framework/pom.xml` 只引 3 个构件
（`foundation-starter-core`、`foundation-common-api`、`foundation-starter-data`），用途仅为 `Ids`、
DO 基类与配置属性；除 F4 在 `tioka-server` 引入 `foundation-starter-client` 用于授权快照外，
`security` / `audit` / `web` / `mq` / `cache` 五个 starter 仍未引。
身份、账号、组织、角色权限、字典、审计、SCIM 全在 Tioka 自建。

如果此时直接把 Agent 平台模块拆成 `tioka-ai`，会把 `tioka-framework` 这套自建身份权限一起复制过去
——那是**分叉**，不是派生。因此先把归属边界固化，再谈拆分。

## 2. 三类归属

### 2.1 归中台（Foundation Owned）

| 能力 | Foundation 模块 | Tioka 现有重复实现 | 状态 | 目标 |
| --- | --- | --- | --- | --- |
| 账号与登录 | `foundation-account`、`foundation-auth` | `AuthUserDO`、`UserAdminService` | 双实现 | 下沉或改走中台 |
| 组织与人员 | `foundation-org`、`foundation-starter-client/org` | `SysMemberDO`、`WorkspaceService` | 双实现 | 组织树归中台；Workspace 保留为 Tioka 历史概念 |
| 角色与权限 | `foundation-authz`、`foundation-starter-security` | `SysRoleDO`、`SysUserRoleDO` | 双实现 | 授权快照归中台；权限码需映射 |
| SCIM 用户同步 | `foundation-sync/scim` | `ScimUserDO`、`ScimSyncService` | 双实现 | 派生应用只接中台单一数据源 |
| 字典参数与编号 | `foundation-common`、`foundation-sequence` | 无 | 待接入 | 派生应用直接复用 starter，禁止自建 |
| 文件审计与通知 | `foundation-file`、`foundation-audit`、`foundation-notify` | 无 | 待接入 | 派生应用直接复用 starter，禁止自建 |

### 2.2 归平台（Platform Owned，随 `tioka-ai` 派生）

| 能力 | 模块 | 说明 |
| --- | --- | --- |
| Agent 委托授权 | `tioka-module-runtime/grant` | `Grant` / `Charter` 授权链是 Agent 平台自有语义，不属中台 |
| 模型路由与预算 | `tioka-module-model`、`tioka-module-governance` | 随 tioka-ai 派生 |
| 本体知识库与编排评测 | `tioka-module-ontology`、`tioka-module-runtime` | 随 tioka-ai 派生 |
| Turn Skill MCP 与沙箱隔离 | `tioka-module-runtime` | ADR-014 沙箱目录隔离属安全边界；即使不引入 Workspace，仍须保留 session 级隔离键 |

### 2.3 必须建适配层

| 适配项 | 涉及实现 | 状态 | 说明 |
| --- | --- | --- | --- |
| 身份上下文适配 | `Principal`、`PrincipalHolder`、`RequirePermission` | **已完成** | 权限判定抽为 `PermissionEvaluator` 端口，`local` / `foundation` 双实现互斥装配；认证链路未动 |
| 授权快照接入 | `AuthorizationSnapshotSource`、`AuthorizationSnapshotClient` | **已完成** | 底座提供只读快照客户端，应用装配层适配为框架端口；见第 8 节 |
| 权限码映射 | `RolePermissionExpander`、`AuthzSnapshot` | **已完成** | `permission-code-mapping-v1.json` 显式登记 37 个权限点；通配不提升、未知码忽略、超管需显式授权 |
| Workspace 与组织树映射 | `WorkspaceAwareDO`、`ScopeLineHandler`、`DataScopeFilterProvider` | 待做 | 仅 Tioka 保留 Workspace；派生应用走组织树与数据范围 |
| 双模式键同源 | `tioka.foundation.mode`、`foundation.client.mode` | **已完成** | `application.yml` 同源派生，`FoundationModeConfigConsistencyTest` 守门 |

## 3. 重复概念冻结表

两侧同名或语义重叠的实现按下表冻结；新增、删除、改名都必须先更新
`capability-ownership-v1.json`，否则守门脚本会失败。

| 类 | Tioka 侧 | Foundation 侧 | 处置 |
| --- | --- | --- | --- |
| `Principal` | `tioka-framework/.../security/Principal.java` | `foundation-starter-security/.../principal/Principal.java` | 适配器：Tioka 版含 `workspaceId` / `onBehalfOf`，不能直接替换 |
| `PrincipalHolder` | 同名不同包 | 同名不同包 | 适配器：双实现按模式切换 |
| `RequirePermission` | 同名不同包 | 同名不同包 | 适配器：权限码体系不同 |
| `RolePermissionExpander` | `tioka-framework/.../security/` | 无同名（对应 `RoleDataScope` / `AuthzSnapshot`） | 迁移候选 |
| `WorkspaceAwareDO` | `tioka-framework/.../persistence/` | 无 | 留在 Tioka，禁止传播 |
| `ScopeLineHandler` | `tioka-framework/.../persistence/` | 无（派生应用用 `DataScopeFilterProvider`） | 留在 Tioka，禁止传播 |
| `PersistenceProperties` | `tioka-framework/.../persistence/` | 无 | 留在 Tioka，禁止传播 |
| `ScimSyncService` | `tioka-module-system/.../application/` | 无同名（`foundation-sync/scim`） | 适配器：派生应用单一数据源 |
| `ScimUser` | 无同名（对应 `ScimUserDO` / `ScimUserMapper`） | `foundation-sync/.../scim/domain/ScimUser.java` | 适配器 |
| `AuthzSnapshot` | 无 | `foundation-authz` 与 `foundation-starter-security` 各一份 | Foundation 内部刻意同名 |
| `OrgUnit` | 无 | `foundation-org` 与 `foundation-starter-client` 各一份 | Foundation 内部刻意同名 |

## 4. Workspace：为什么先不删

Workspace 不是多租户残留，而是 **ADR-029 第 60 条**定案的现任隔离边界（当年废止 `tenant_id` 后改为
`workspace_id` + `owner_id`）。实测渗透度：

| 项 | 数值 |
| --- | --- |
| 含 workspace 的 Java 文件 | 487 |
| Java 中 workspace 匹配行数 | 2322 |
| Java 中 workspace 字面出现次数 | 2980 |
| 含 `workspace_id` 的迁移 / 迁移总数 | 36 / 84 |
| 核心机制 | `ScopeLineHandler` 行级 deny-by-default；`WorkspaceAwareDO` 基类；`PersistenceProperties` 旁路表白名单 |

> 数值口径：守门对**全文**做 `/workspace/gi` 匹配，**不剥离注释**（与同脚本 `@Autowired` 检测的
> 剥离口径不同，属历史设计）。故上表含注释中的 `workspace_id` 字样——2026-11-17 由
> `PersistenceProperties` 旁路表登记说明 +2（`cfg_change` 漏登记修复）而更新；同日再因新增
> `GfTiokaFoundationModeAuthzIT`（foundation 模式授权链路闸门，含 `workspace_admin` 角色名与
> `workspaceId` 请求字段，与既有 IT 同惯例）+1 文件 / +12 行 / +12 次。**均无新增业务字段**。
> 若将来要改成「剥离注释」口径，三处数字会同时变化，须一并同步本表与 JSON 清单。

它混了四种本该分开的职责：① 数据可见范围（可由 Foundation 组织树 + `DataScopeFilterProvider` 承接）；
② 资产与内容分组（空间级记忆、提示词库、知识库、配额、评测集，目前无替代概念）；
③ 角色作用域 `SYSTEM` / `WORKSPACE`；④ ADR-014 沙箱文件隔离（**安全边界，不能没有隔离键**）。

**本轮决策**：Tioka 保留现状、零回归；`tioka-ai` 等派生应用**不引入 Workspace**，直接用 Foundation
组织树与数据范围；两套之间通过「Workspace 与组织树映射」适配层衔接。彻底统一需另立 ADR、定义
②④ 的承接方并做数据迁移，不在本轮范围。

## 5. 守门与更新流程

```bash
node scripts/verify-capability-ownership.mjs
node scripts/verify-permission-code-mapping.mjs
```

脚本只读校验：冻结类的实际路径与清单逐字一致、Workspace 渗透度数字未漂移、双键同源、
单轨版本坐标 `0.2.0-SNAPSHOT` 未被改回分轨、文档覆盖所有能力与冻结类；
第二个脚本额外校验权限码契约同步、判定实现不回落本地、接入链路的装配与出站令牌接线。
失败时不要直接改脚本放宽断言——先判断是「真实新增了重复实现」还是「能力已按归属迁移」，
再更新 JSON 与本文。

## 6. 边界

- 本轮**零删除**：没有删除任何类、表、列或迁移，也没有改动 Tioka 业务代码。
- 不变更 ADR-029 第 60 条；Workspace 的存废属于独立立项。
- Foundation 已收敛为单轨 `main` / `0.2.0-SNAPSHOT`；不配远程、不 deploy、不改 CI 发布链路。
- 身份权限双实现与权限码映射已于 2026-11-16 下午落地，见第 7 节；授权快照接入见第 8 节；
  模块拆分与 Workspace 存废仍属后续立项。

## 7. 权限判定双实现与映射契约（2026-11-16 下午）

按「Foundation 成为中台底座」的方向，把权限判定的**来源**从切面里抽出来，
但没有引入新的 starter 依赖、没有替换两套同名身份类、没有碰认证链路：

| 层 | 实现 | 约束 |
| --- | --- | --- |
| 端口 | `PermissionEvaluator.firstMissing(...)` | 返回第一个缺失权限码，沿用既有 403 与 DENIED 审计语义 |
| 数据源端口 | `AuthorizationSnapshotSource.permissionsFor(personId)` | 由应用装配层提供真实中台适配器；框架层不绑定 SDK |
| 本地实现 | `LocalPermissionEvaluator` | 逐字等价改造前的 `Principal.hasPermission`，`local` 模式零行为变化 |
| 接入实现 | `FoundationPermissionEvaluator` | 先经权限码映射再判定；**绝不读取本地权限集**；数据源不可用一律拒绝 |
| 映射 | `PermissionCodeMapper` + `permission-code-mapping-v1.json` | 37 个权限点与 `Permissions.ALL_CONCRETE` 逐项一致；含 `*` 的中台码全部丢弃 |
| 装配 | `FoundationModeConfiguration` | 两实现按模式互斥；`foundation` 模式缺数据源或 Spring `ObjectMapper` 时启动期拒绝 |
| 切面 | `PermissionAspect` | 只把判定循环换成端口调用，deny 审计与异常类型不变 |

**为什么必须显式映射**：Tioka 的 `*` 是超管、`agent:*` 是通配；Foundation 授权快照只做精确匹配。
若把中台返回的 `*` 直接灌进 `Principal.permissions()`，普通用户会被静默提升为超管。
契约默认 `superAdminCodes` 为空，中台无法直接授予 Tioka 全量权限。

**守门**：`scripts/verify-permission-code-mapping.mjs` 校验契约与代码目录同步、
策略不被放宽、接入实现不回落本地权限、切面确实委托端口；
`PermissionCodeMapperTest` / `PermissionEvaluatorTest` / `PermissionAspectUnitTest` 覆盖
通配、别名、超管、fail-closed、403 与审计。`FoundationModeSwitchTest` 固化互斥装配与缺源拒绝。

## 8. 授权快照接入（F4，2026-11-16 晚）

第 7 节只完成了「判定来源可插拔」，`foundation` 模式因缺少真实数据源仍在启动期拒绝。
本批把链路接通，让底座第一次能被第二个业务应用照着接入。

| 层 | 实现 | 约束 |
| --- | --- | --- |
| 底座客户端 | `foundation-starter-client` 的 `AuthorizationSnapshotClient` / `HttpAuthorizationSnapshotClient` | 只在 `foundation.client.mode=foundation` 装配；复用 `FoundationHttpClient` 的版本头、服务账号令牌、重试、熔断与 trace 透传 |
| 快照校验 | `HttpAuthorizationSnapshotClient` | 响应必须为对象，`personId` / `appCode` 与请求一致，`permissions` 为文本数组；任一不满足返回 null；`personId` 做 URL 编码，`app-code` 按登记正则校验 |
| 应用装配 | `tioka-server` 的 `FoundationAuthzConfiguration` | 按 `tioka.foundation.mode=foundation` 注册 `AuthorizationSnapshotSource`；缺底座客户端时启动期拒绝 |
| 端口适配 | `FoundationAuthorizationSnapshotSource` | 只转发原始权限项，不改码、不缓存、不读本地权限集 |
| 判定 | `FoundationPermissionEvaluator` + `PermissionCodeMapper` | 先映射后判定；快照 null、抛异常或版本错配一律拒绝 |

**同时修掉的真实缺陷**：`FoundationClientAutoConfiguration` 原先构造 `FoundationHttpClient` 时把
`BearerTokenSupplier` 传成 `null`，服务账号令牌 Bean 虽然注册却从未被出站请求使用；
接入中台快照端点时不会带 `Authorization`，会被服务端的 `requireSameApp` 拒绝。本批改为注入令牌提供者。

**配置**：接入模式需显式设置 `FOUNDATION_URL`、`foundation.app-code`，以及
`FOUNDATION_CLIENT_ID` / `FOUNDATION_CLIENT_SECRET` / `FOUNDATION_TOKEN_URI`。
凭据缺失时令牌提供者返回空，中台端点拒绝请求，链路保持 fail-closed，不会静默回落本地权限。

**验证**：`FoundationAuthorizationChainTest`（10 例，本地 HTTP 桩走完整链路）与
`FoundationAuthzConfigurationTest`（4 例，模式互斥、缺源拒绝、接入模式不再拒绝启动）全绿。
覆盖正常授权、缺权限、本地超管权限集不参与判定、中台通配码不提升、未登记码不回源、
500/404、空响应、结构非法、版本错配不重试、令牌与版本头出站。

**刻意不做**：不引入 `foundation-starter-security`（服务端安全 starter 会装配认证过滤器，
与 Tioka 本地认证链路冲突）；不缓存授权快照（本切片每次回源，避免陈旧授权放行，
缓存与版本失效属后续优化）；不配远程、不 deploy、不改 CI 发布链路。

## 9. 接入平面产品化（P0，2026-11-16 晚）

从平台视角复核发现，第 7、8 节虽然打通了链路，但**平台资产长在业务应用里**：映射契约随
`tioka-framework` jar 分发、接入样板写在 `tioka-server`、判定端口在 Tioka framework。
第二个应用接入会变成复制适配类 + 反向依赖第一个业务应用。本批按「规则归平台、实例归应用」
收敛，全部为增量，零删除、零业务行为变化。

| 能力 | 归属 | 落点 |
| --- | --- | --- |
| 权限码映射规则 | 平台 | `tioka-foundation/docs/contracts/permission-code-mapping-v1.schema.json`（冻结字段与三条策略） |
| 权限码目录实例 | 应用 | `tioka-framework/.../permission-code-mapping-v1.json`，改为应用无关的 `applicationPermissionCodes` 并声明 `platformContract` |
| 接入护栏 | 平台 | `foundation-starter-client` 的 `FoundationIntegrationAutoConfiguration` |
| 六项自检包 | 平台 | `FoundationIntegrationSelfCheck` / `IntegrationSelfCheckReport`，任意派生应用可复用 |
| 快照失效钩子 | 平台 SPI | `SnapshotInvalidationHook`，不绑定缓存实现 |
| 第二接入方验收 | 平台 | `foundation-it-example` 的 `SecondAppOnboardingTest`，不依赖任何 `com.tioka` 类 |

**平台判据**（用于后续验收，不靠数模块）：① 第二个应用接入零改中台代码；② 契约变更由平台守门
而不是应用自觉；③ 中台不可用时 N 个应用一致 fail-closed。本批让 ① 有了可复现证据，
② 由两仓脚本双向守门，③ 由自检的 `fail-closed` 项可验证；P1 再解决「中台退出请求热路径」。

## 10. 运行平面（P1，2026-11-16 夜）

P0 让第二个应用能接上，但每次鉴权仍回源中台——ADR-051 第 5 条「Foundation 不在请求热路径」
未落实。核实发现标准路径（`foundation-starter-security`）早已有 `SnapshotCache` /
`AuthzChangedListener`，缺的是**轻量只读路径**的同一能力，以及三项「单应用是债务、N 个应用是事故」
的契约缺口。本批仍按「规则归平台、实例归应用」：Tioka 侧零改动。

| 能力 | 归属 | 落点 |
| --- | --- | --- |
| 版本化缓存 | 平台 | `AuthorizationSnapshotCache`（命中零回源、版本不回退、陈旧拒绝） |
| 缓存化客户端 | 平台 | `CachingAuthorizationSnapshotClient`，端口仍是 `AuthorizationSnapshotClient` |
| 事件失效 | 平台 | `AuthorizationSnapshotInvalidationListener` 解析 `fdn.authz.changed`，钩子自动指向缓存 |
| 失效钩子 | 平台自动装配 | `SnapshotInvalidationHook` 由 `FoundationClientAutoConfiguration` 默认注册，自检第 6 项在真实接入中自动 PASS |
| MQ 幂等键 | 平台 | `MybatisOutbox` 写 `deliveryId`；`DeliveryAwareIdempotentConsumer` 按 `eventType + deliveryId` 去重 |
| Webhook 防重放 | 平台 + SDK | envelope 可选字段 `sentAt`（在 HMAC 覆盖的 body 内）；Node `assertReplayWindow` |
| 降级标记异步传递 | 平台 | `DegradeContext.capture/restore/wrap/call`，`DegradeHeaderFilter` 绑定共享快照 |

**三条治理原则**：平台只定义契约与能力、不定义应用语义；应用只依赖 SDK 与契约、禁止依赖另一个应用；
不可用一律 fail-closed，降级策略由平台统一声明（缓存刻意不提供「过期放行」开关，配置收敛在
`foundation.client.authz.cache-ttl`，不与 security starter 的 `foundation.authz.snapshot.*` 串线）。

**仍未做**：应用间授权与门户卡片注册、能力目录注册表与破坏性变更流程（P2）、`tioka-ai` 拆分（P3）。
Workspace 与组织树映射仍为 `pending`；两项待定决策（Tioka 是否退化为纯派生壳、Workspace 是否
整体废止）仍只阻塞 P3。

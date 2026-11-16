# Foundation 中台化：能力归属与重复概念清单

> 建立日期：2026-11-16。机器可读版本：[`capability-ownership-v1.json`](capability-ownership-v1.json)，
> 守门脚本：[`scripts/verify-capability-ownership.mjs`](../../scripts/verify-capability-ownership.mjs)。
> 本文回答「哪些能力归中台、哪些归业务应用、哪些重叠必须建适配层」，**不修改任何业务代码**。

## 1. 为什么需要这份清单

Foundation 要成为整个 Tioka 的中台底座，后续业务应用（如 `tioka-ai`）都应从它派生。但按代码核实，
当前接线并不是「底座」：`tioka-framework/pom.xml` 只引 3 个构件
（`foundation-starter-core`、`foundation-common-api`、`foundation-starter-data`），用途仅为 `Ids`、
DO 基类与配置属性；`security` / `client` / `audit` / `web` / `mq` / `cache` 六个 starter 一个都没引。
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
| 身份上下文适配 | `Principal`、`PrincipalHolder`、`RequirePermission` | **已完成** | 权限判定抽为 `PermissionEvaluator` 端口，`local` / `foundation` 双实现互斥装配；认证链路未动，中台快照适配器待 F2 |
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
| 含 workspace 的 Java 文件 | 486 |
| Java 中 workspace 匹配行数 | 2308 |
| Java 中 workspace 字面出现次数 | 2966 |
| 含 `workspace_id` 的迁移 / 迁移总数 | 36 / 84 |
| 核心机制 | `ScopeLineHandler` 行级 deny-by-default；`WorkspaceAwareDO` 基类；`PersistenceProperties` 旁路表白名单 |

它混了四种本该分开的职责：① 数据可见范围（可由 Foundation 组织树 + `DataScopeFilterProvider` 承接）；
② 资产与内容分组（空间级记忆、提示词库、知识库、配额、评测集，目前无替代概念）；
③ 角色作用域 `SYSTEM` / `WORKSPACE`；④ ADR-014 沙箱文件隔离（**安全边界，不能没有隔离键**）。

**本轮决策**：Tioka 保留现状、零回归；`tioka-ai` 等派生应用**不引入 Workspace**，直接用 Foundation
组织树与数据范围；两套之间通过「Workspace 与组织树映射」适配层衔接。彻底统一需另立 ADR、定义
②④ 的承接方并做数据迁移，不在本轮范围。

## 5. 守门与更新流程

```bash
node scripts/verify-capability-ownership.mjs
```

脚本只读校验：冻结类的实际路径与清单逐字一致、Workspace 渗透度数字未漂移、双键同源、
单轨版本坐标 `0.2.0-SNAPSHOT` 未被改回分轨、文档覆盖所有能力与冻结类。
失败时不要直接改脚本放宽断言——先判断是「真实新增了重复实现」还是「能力已按归属迁移」，
再更新 JSON 与本文。

## 6. 边界

- 本轮**零删除**：没有删除任何类、表、列或迁移，也没有改动 Tioka 业务代码。
- 不变更 ADR-029 第 60 条；Workspace 的存废属于独立立项。
- Foundation 已收敛为单轨 `main` / `0.2.0-SNAPSHOT`；不配远程、不 deploy、不改 CI 发布链路。
- 身份权限双实现与权限码映射已于 2026-11-16 下午落地，见第 5 节；模块拆分与 Workspace 存废仍属后续立项。

## 5. 权限判定双实现与映射契约（2026-11-16 下午）

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

**守门**：`scripts/verify-permission-code-mapping.mjs`（36 项）校验契约与代码目录同步、
策略不被放宽、接入实现不回落本地权限、切面确实委托端口；
`PermissionCodeMapperTest` / `PermissionEvaluatorTest` / `PermissionAspectUnitTest` 覆盖
通配、别名、超管、fail-closed、403 与审计。`FoundationModeSwitchTest` 固化互斥装配与缺源拒绝。

**边界**：本批是纯本地代码逻辑改造。`foundation` 模式所需的真实中台快照适配器
（`AuthorizationSnapshotSource` 实现）尚未接线，属 F2；因此该模式目前会启动期拒绝，
不会出现「配了接入中台却静默按本地权限跑」的半接线状态。权限 IT 依赖 Testcontainers，
本机无 Docker 环境，改由不依赖容器的切面单测覆盖。

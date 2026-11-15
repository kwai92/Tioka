# F-07 — 后端接入 starter（Foundation）

- 状态：施工设计（2026-11-13）。**设计权威**是 [S15 Foundation 基础平台系统](../systems/S15-Foundation基础平台系统.md) 第 5.2、9、11.3、11.4、12、13、14 节与 [ADR-051〜054](../adr/)；**排期权威**是 [F-00 F 阶段实施计划](F-00-F阶段实施计划.md)，冲突时以 F-00 为准；本文只写「怎么做」
- 负责人：BE-C（并行任务包 [PF-C06](F-01-并行开发任务包.md) §4.3）
- 工作项：BAS-08（W84–W85）
- 约束：ADR-051〜054、ADR-029 第 14、18、27、32、60、69、81 条、[ADR-076](../adr/ADR-076-app-to-app-authorization.md)（应用间授权，S15 §22；**实现归 F-12**，本文只登记接入点）。硬约束：单企业独立部署，**禁止 `tenant_id` / `sys_tenant`**（ADR-029 第 60 条）；**Foundation 不进请求热路径**（ADR-051 第 5 条，靠快照本地缓存）；错误码沿用 ADR-029 第 14/18 条口径（`UNAUTHORIZED`→401、`FORBIDDEN`→403）
- 排期与人天：以 [F-00](F-00-F阶段实施计划.md) §3.1 为准（BAS-08 在 W84–W85）；任务包 [PF-C06](F-01-并行开发任务包.md) §4.3；依赖 BAS-07（[F-06](F-06-数据权限与授权快照.md) 快照与事件）、BAS-09（[F-02](F-02-工程底座与骨架.md) 字典与参数）
- 前置：BAS-24（[F-02](F-02-工程底座与骨架.md) §BAS-24，九个 starter 骨架与 `foundation-test` 已抽取）、BAS-04/05/06/07（应用登记、权限码、数据范围、授权快照）
- 迁移编号段：Foundation 库自 **V1** 起（独立 Flyway 历史表，不占 Tioka 号段）。**本包基本无表**——快照、字典、参数、组织与人员的缓存都在**应用侧 Redis**（键加 `fdn` 前缀），审计失败的本地兜底用**应用侧磁盘 spool 文件**，均不落库。若日后审计 spool 改为落库，用 **Foundation V24＋**（承接 [F-05](F-05-应用与权限中心.md) 的 V10〜V23，实际以合入时首个空号为准）
- 仓库：`tioka-foundation`（`foundation-starter-security` / `foundation-starter-audit` / `foundation-starter-client`；**不涉及 Tioka 业务代码**，Tioka 侧改造在 [F-08/BAS-20](F-00-F阶段实施计划.md)）

---

## BAS-08 后端接入 starter（P0，7 人天）

本文覆盖工作项 **BAS-08**：把 BAS-04〜07 定义的「登录、权限、数据范围、主数据」通过三个 Spring Boot starter 交付给业务应用（Tioka、合同业务系统、后续子系统）。接入后，应用**不直连 Foundation**，运行时靠 starter 的本地缓存决策；Foundation 只承担登记、计算与事件广播（ADR-051 第 5 条）。

### 0. 范围与构件边界

S15 §5.2 把底座拆成九个 starter（core、web、security、data、mq、cache、observability、audit、client）。**BAS-24（F-02）只建模块骨架与自动配置骨架**；BAS-08 在这些骨架内**实现接入能力**，并在 `foundation-test` 上补齐测试基座。本文聚焦 **security / audit / client** 三者，其余六个（core、web、data、mq、cache、observability）的通用能力归 [F-02](F-02-工程底座与骨架.md) §BAS-24，本文只声明依赖。

| 构件 | 承载能力（本文小节） | 直接依赖 | 归属 |
| --- | --- | --- | --- |
| `foundation-starter-security` | 令牌校验与当前用户注入（§2）、`@RequirePermission` / `@PublicApi`（§3）、授权快照本地缓存（§4）、`@DataScope` + 条件生成器（§5）、`@RequireAppGrant` 接入点（§9，占位） | `foundation-starter-core`；可选 `foundation-starter-cache`（Redis）、`foundation-starter-web` | BAS-08；`@RequireAppGrant` 实现归 BAS-30（F-12） |
| `foundation-starter-audit` | `@Audited` + `AuditReporter`（§6） | `foundation-starter-core`、`foundation-starter-security`（取当前用户） | BAS-08 |
| `foundation-starter-client` | 字典与参数读取（§7）、组织与人员查询（§8）、应用间调用客户端（§9，占位） | `foundation-starter-core`；可选 `foundation-starter-web` | BAS-08；应用间调用实现归 BAS-30（F-12） |
| `foundation-starter-core` / `web` / `data` / `mq` / `cache` / `observability` | 错误码与统一返回、ID、Jackson、请求上下文、全局异常、Idempotency-Key、MyBatis-Plus 基类、RocketMQ 与 outbox、Redis 与锁、traceId 透传 | — | [F-02](F-02-工程底座与骨架.md) §BAS-24（本文只依赖） |

**starter 之间只依赖 core**（S15 §5.2）；`audit` 因取当前用户而依赖 `security`（S15 §5.2 明确允许的唯一例外）。所有 starter 配置前缀统一为 `foundation.*`。

**硬约束（贯穿全文，Code Review 必查）**：

- **禁止** `tenant_id` / `sys_tenant`（ADR-029 第 60 条）；接入侧不引入租户概念，隔离边界仍是应用内自身的空间 / 归属（Tioka 侧）与 Foundation 的组织树。
- Foundation **不进请求热路径**：所有校验走本地缓存（应用侧 Redis 或进程内），令牌校验只做签名与声明提取，不在请求线程同步调 Foundation。
- 错误码：未登录 → `UNAUTHORIZED(40100)` → HTTP 401；无权限 / 快照不可用 → `FORBIDDEN(40300)` → HTTP 403（ADR-029 第 14、18 条；沿用 [M0-02](M0-02-身份与空间.md) 既有口径）。
- 主键 ULID、时间列 `TIMESTAMPTZ`、DO 字段 `LocalDateTime`（UTC）——由依赖的 `core`/`data` starter 提供。

### 1. starter 能力清单

逐条给出能力、依赖、关键类与接口、配置项；用法示例见紧随其后的 Java 代码块。

| # | 能力 | 构件 | 依赖 | 关键类与接口 | 配置项（`foundation.*`） |
| --- | --- | --- | --- | --- | --- |
| ① | 自动配置与 `foundation.*` 绑定 | security / audit / client | core | `FoundationSecurityAutoConfiguration`、`FoundationAuditAutoConfiguration`、`FoundationClientAutoConfiguration`、`FoundationSecurityProperties` / `FoundationAuditProperties` / `FoundationClientProperties` | `foundation.enabled`、`foundation.app-code`、`foundation.base-url`、`foundation.service-account.*` |
| ② | 登录用户注入：从令牌解析 `Principal` | security | core；可选 web | `PrincipalResolver`（`JwtPrincipalResolver` / `OpaqueTokenPrincipalResolver`）、`Principal`、`PrincipalHolder`、`CurrentUserArgumentResolver` | `foundation.security.token-source` |
| ③ | 权限校验 `@RequirePermission` / `@PublicApi` | security | core | `@RequirePermission`、`@PublicApi`、`PermissionAspect` | — |
| ④ | 授权快照本地缓存（按「人员 + 应用」、TTL 5 分钟、事件失效、降级拒绝） | security | core、cache（Redis） | `AuthzSnapshotClient`、`AuthzSnapshot`、`SnapshotCache`、`AuthzChangedListener` | `foundation.authz.snapshot.*`、`foundation.authz.event.*` |
| ⑤ | 数据范围 `@DataScope` + 条件生成器 | security | core | `@DataScope`、`DataScopeFilterProvider`、`DataScopeFilter`（`(all, sql, params)`） | `foundation.data-scope.*` |
| ⑥ | 审计上报 `@Audited` + `AuditReporter`（异步批量、失败重试、本地磁盘兜底） | audit | core、security | `@Audited`、`AuditReporter`、`AsyncBatchAuditReporter`、`DiskSpool` | `foundation.audit.*` |
| ⑦ | 字典与参数读取（本地缓存 + 事件刷新） | client | core；可选 web | `DictClient`、`ParamClient` | `foundation.dict.*`、`foundation.param.*` |
| ⑧ | 组织与人员查询客户端（按工号/ID 查人员、按编码解析组织树） | client | core；可选 web | `OrgClient`、`PersonClient` | `foundation.org.*`、`foundation.person.*` |
| ⑨ | 应用间调用客户端（M2M `client_credentials` / OBO Token Exchange，RFC 8693） | client / security | core；可选 web | `AppTokenClient`、`@RequireAppGrant`（**接口占位**） | `foundation.app-grant.*` |

> ①〜⑧ 在本包实现；⑨ 只登记接口与接入点，**实现归 [F-12](F-00-F阶段实施计划.md) / BAS-30**（[ADR-076](../adr/ADR-076-app-to-app-authorization.md)、S15 §22）。

### 2. 令牌校验与当前用户注入（能力②）

**令牌只带身份、不带权限**（S15 §9.2、ADR-051 第 3 条）。Foundation 经 Keycloak 签发令牌，声明里只有：Keycloak 用户 ID、Foundation 账号 ID、人员 ID、工号、姓名、主组织单元编码。角色与权限一律来自授权快照（§4）。

两种令牌来源并存，由 `PrincipalResolver` 抽象：

- `jwt`：应用把 Keycloak / IdP 令牌当资源服务器校验（[ADR-029 第 32 条「JWT 资源服务器路径供持有 IdP 令牌的系统调用」](../adr/ADR-029-design-rulings.md)）。starter 只验签、验 `aud`、提取身份声明。
- `opaque`：Tioka 延续 FND-05 的「OIDC 令牌换 `tka_` 不透明令牌」（S15 §9.2 第 3 步，[M1-01](M1-01-权限与身份联邦.md)），由应用提供 `PrincipalResolver` 实现（BAS-20 落地），starter 只负责在线程内暴露 `Principal`。

```java
// com.foundation.starter.security：Principal 只含身份（不含权限、不含 tenantId）
public record Principal(
        String userId,          // 应用内用户/账号 ID（Tioka 为十进制字符串，ADR-029 第 31 条）
        String personId,        // Foundation 人员 ID（per_…）
        String accountId,       // Foundation 账号 ID（acc_…）
        String employeeNo,      // 工号
        String displayName,
        String primaryOrgUnit,  // 主组织单元编码
        String appCode) {}

public interface PrincipalResolver {
    /** 从未认证请求中解析身份；无令牌/无效返回 Optional.empty()，不抛异常。 */
    Optional<Principal> resolve(Object request);
}

public final class PrincipalHolder {
    public static Optional<Principal> current() { ... }        // 请求线程内取当前人员
    public static Principal require() { ... }                  // 取不到抛 BizException(UNAUTHORIZED)
    /** 后台任务恢复身份：以快照指定版本运行，避免长任务的权限漂移。 */
    public static <T> T runAs(Principal principal, long snapshotVersion, Supplier<T> action) { ... }
}
```

**约定**：`PrincipalHolder` 用 `ThreadLocal`，由 `OncePerRequestFilter` 或 `WebMvc` 参数解析器（`CurrentUserArgumentResolver`）在请求边界清空；后台任务（定时、MQ 消费）无请求线程，必须显式 `runAs` 恢复身份后才可调 `require()`。

### 3. 权限校验：`@RequirePermission` 与 `@PublicApi`（能力③）

- `@RequirePermission("资源:动作")`：可标方法或类；**多个值为「全部需要」**（与 [M1-01 §`@RequirePermission` 落地](M1-01-权限与身份联邦.md) 一致）。权限码取自快照的 `permissions` 集合；格式沿用 S15 §11.1 的 `资源:动作`（如 `contract:read`、`audit:read`）。
- `@PublicApi`：显式声明免鉴权端点白名单；ArchUnit 规则要求每个 Controller 方法**要么声明权限、要么标 `@PublicApi`**（S15 §5.3 规则集）。
- `PermissionAspect`（`order` 早于事务）：不满足抛 `BizException(FORBIDDEN, 40300)`，并**以独立事务**写一条 `DENIED` 审计（ADR-029 第 8 条）。
- 权限码**只能引用常量目录**（ArchUnit `permissionLiteralsFromCatalog`，S15 §5.3）；应用侧的清单文件（§10 第 3 步）从同一份常量导出。

```java
@RestController
@RequestMapping("/api/contracts")
public class ContractController {

    @PublicApi
    @GetMapping("/health")                       // 免鉴权白名单
    String health() { return "ok"; }

    @RequirePermission({"contract:read"})        // 需要 contract:read
    @GetMapping
    List<ContractView> list(@DataScope(column = "region_code", dimension = "region") Query q) { ... }

    @RequirePermission({"contract:read", "contract:write"}) // 两个都需满足
    @PutMapping("/{id}")
    void update(@PathVariable String id, @RequestBody ContractCmd cmd) { ... }
}
```

### 4. 授权快照本地缓存（能力④）

**粒度**：一个人员在一个应用里一份快照（S15 §11.3）。**获取**：应用以**服务账号**调快照接口，starter 在**应用侧 Redis** 缓存；**缓存键含版本号**（`fdn:snap:{appCode}:{personId}:{version}`），避免版本漂移（[F-06](F-06-数据权限与授权快照.md) §获取）。**失效**：订阅 `fdn.authz.changed`（含人员、应用、新版本号），收到后删除该键。**TTL 兜底**：默认 **5 分钟**，事件丢失时到期即强制回源。**降级拒绝**：Foundation 不可用时用**未过期**缓存；**已过期且取不到时拒绝**（抛 `FORBIDDEN`），**不放行**——延续 S9 第 11 节「降级拒绝」（S15 §11.3、[F-06](F-06-数据权限与授权快照.md) §故障降级）。

快照内容见 [F-06](F-06-数据权限与授权快照.md) §粒度与内容（有效角色、权限码集合、菜单树、部门范围、各维度取值、人员属性、版本号）。

```java
// com.foundation.starter.security
public interface AuthzSnapshotClient {
    /** 取「人员 + 应用」快照；过期且回源失败时抛 FORBIDDEN（降级拒绝，不返回空）。 */
    AuthzSnapshot load(String personId);
    /** 只取版本号，用于判本地缓存是否过期。 */
    long version(String personId);
    void evict(String personId);                 // 事件回调入口
}

public record AuthzSnapshot(
        String personId, String appCode, long version,
        Set<String> roles, Set<String> permissions,
        List<MenuNode> menus,
        DeptScope deptScope, Map<String, DimensionScope> dimensions,
        PersonAttrs person) {
    public boolean has(String permission) { return permissions.contains(permission); }
    public boolean hasAll(String... codes) { ... }
}

// 事件订阅（RocketMQ 主题 fdn-events，S15 §14.2）：只带 ID、版本、变化类型
@Component
class AuthzChangedListener {                      // fdn.authz.changed
    void on(FdnEvent e) { if (e.appCode().equals(props.appCode())) client.evict(e.personId()); }
}
```

**进程内一级缓存**：为减少 Redis 往返，提供可选的进程内一级缓存（默认**关**，`foundation.authz.snapshot.local-cache.enabled=false`），TTL 极短（≤ 10 秒）且**只缓存命中过的版本**；开启后仍以 Redis 为失效权威。二级缓存（更长 TTL）由 [F-00 §7 风险 6](F-00-F阶段实施计划.md) 在 BAS-20 按需追加，不在本包强制。

### 5. 数据范围：`@DataScope` + 条件生成器（能力⑤）

执行**在应用里**，starter 提供两种用法（S15 §11.4）：

1. **注解 + 持久层条件生成**：查询方法上声明「本查询的部门字段、维度字段对应哪一列」，starter 按快照生成过滤条件拼进 MyBatis-Plus 查询。生成器**返回 SQL 片段与参数**而不是大集合（沿用 [M1-01 `VisibilityFilter`](M1-01-权限与身份联邦.md) 的做法，避免大集合进内存）。
2. **直接读取快照取值集合**：从 `AuthzSnapshot` 的 `deptScope` / `dimensions` 取值集合自行判断，适合复杂业务规则。

**与 Tioka 既有空间行级过滤并存、互不替代**：Tioka 的 `FND-03` 空间行级过滤（[M0-02](M0-02-身份与空间.md)、`foundation-starter-data` 明确「空间过滤留在 Tioka」，S15 §5.2）仍由应用自己拼；`@DataScope` 只补「Foundation 部门 / 维度」这一层，两者条件**取交集**拼入同一查询（S15 §11.4 明确「和 Tioka 现有的空间行级过滤并存，互不替代」）。

```java
// 注解：声明本查询的部门字段与维度字段
public @interface DataScope {
    String column() default "dept_code";     // 部门字段列名
    String dimension() default "";           // 维度编码（如 region）；空表示只用部门范围
    String dimensionColumn() default "";     // 维度字段列名
}

// 条件生成器：返回可直接拼入 MyBatis-Plus Wrapper 的条件
public interface DataScopeFilterProvider {
    DataScopeFilter filter(String departmentColumn, String dimension,
                           String dimensionColumn, AuthzSnapshot snapshot);
}
public record DataScopeFilter(boolean all, String sql, Map<String, Object> params) {}
```

**语义**（S15 §11.4、[F-06](F-06-数据权限与授权快照.md)）：部门五档 `ALL` / `CUSTOM` / `DEPT` / `DEPT_AND_SUB` / `SELF`；**跨角色取并集、同一角色内部门范围与各维度取交集**；`all=true`（「全部」）时生成 `1=1` 且不追加维度条件。**默认拒绝**：注解存在但快照缺失或取不到 → 抛 `FORBIDDEN`；未标注解的查询不受影响（是否强制标注由应用的 ArchUnit 规则决定，starter 不强制）。

### 6. 审计上报：`@Audited` + `AuditReporter`（能力⑥）

管理类与业务关键写操作经注解上报（S15 §12.2）；**异步批量投递**，Foundation 不可用时**本地落盘重试**（S15 §12.2、§13）。

```java
public @interface Audited {
    String action();                          // 如 contract.publish
    String resource() default "";
    boolean recordResult() default true;      // 记录成功/失败与结果摘要
}

public interface AuditReporter {
    void report(AuditEvent event);            // 非阻塞，入本地队列
}
```

**语义与格式**：

- 业务操作成功 / 失败的审计，**格式只汇聚不改**（[F-00 §5 约定 5](F-00-F阶段实施计划.md)、M4 约束 F46），字段口径沿用 ADR-017 与 [ADR-029 第 8 条](../adr/ADR-029-design-rulings.md)：成功操作可与业务同事务；`DENIED`、`ERROR` 审计用独立事务（`REQUIRES_NEW`）不随业务回滚（与 §3 的 `PermissionAspect` 共用同一写入路径）。Tioka 的哈希链与归档（GOV-09、OBS-10）原样保留，本包不做改写。
- 上报接口 `POST /api/fdn/audit/events:batch`（[F-02 §BAS-09 REST 风格](F-02-工程底座与骨架.md)、S15 §14.1 日志分组），要求 `Idempotency-Key`（S15 §14.1）。
- **投递**：`AsyncBatchAuditReporter` 有界队列（`batch-size` 满或 `flush-interval` 到即发），失败按指数退避重试；超过 `retry.max-attempts` 后转 `DiskSpool` 落盘（`foundation.audit.spool-dir`），后台补偿线程扫描重放。**磁盘 spool 是文件，不建表**（见头部「迁移编号段」）。

> ADR-050 相关的记忆 / 上下文口径不改；若与原文冲突，以原文为准，本文只固定「应用上报的审计」这一条链路。

### 7. 字典与参数读取（能力⑦）

`foundation-starter-client` 提供 `DictClient` / `ParamClient`：本地缓存（应用侧 Redis 或进程内），订阅 `fdn.dict.changed` / `fdn.param.changed` 刷新（S15 §12.2、§14.2）。

```java
public interface DictClient {
    List<DictItem> items(String typeCode);            // 全局项 + 本应用项，应用项优先
}
public interface ParamClient {
    Optional<String> value(String key);               // 敏感参数（secret=true）拒绝并返回空
}
```

- 作用域：`app_code` 为空表示全局，非空归属该应用；查询时**应用项优先于全局项**（[F-02 §BAS-09](F-02-工程底座与骨架.md)）。
- **敏感参数只写不读**：`ParamClient.value` 遇到 `secret=true` 时**拒绝**（返回空 / 抛 `FORBIDDEN`），密文只允许 Foundation 服务间内部接口解密（[F-02 §BAS-09](F-02-工程底座与骨架.md)）。
- 缓存 TTL 兜底 + 事件刷新，二者与 §4 快照同构。

### 8. 组织与人员查询客户端（能力⑧）

`OrgClient` / `PersonClient`：**按工号 / ID 查人员、按编码解析组织树**，带本地缓存（S15 §12.2）。

```java
public interface OrgClient {
    Optional<OrgUnit> byCode(String orgCode);                 // 按编码解析组织单元
    List<OrgUnit> children(String orgCode);                   // 下级（含/不含本单元按参数）
}
public interface PersonClient {
    Optional<Person> byEmployeeNo(String employeeNo);         // 按工号
    Optional<Person> byId(String personId);                   // 按人员 ID
    List<Person> byOrgUnit(String orgCode, boolean includeSub);// 组织下人员
}
```

- 缓存 TTL 兜底，订阅 `fdn.org.changed`、`fdn.person.created` / `updated` / `disabled` 刷新（S15 §14.2）。
- 只读：人员的增删改在 Foundation 管理台，starter 不提供写接口。
- `fdn.person.disabled` 的**离职联动**由应用侧监听处理（Tioka 的记忆清理、令牌吊销等，[F-00 §5 约定 7](F-00-F阶段实施计划.md)）；starter 只保证事件可订阅，不替应用做业务反应。

### 9. 应用间调用客户端（能力⑨，接口占位，实现归 F-12）

仅登记接口与接入点，**不在本包实现**（[ADR-076](../adr/ADR-076-app-to-app-authorization.md)、S15 §22；实现归 BAS-30 / [F-12](F-00-F阶段实施计划.md)）。

- **调用方**（`foundation-starter-client`）：`AppTokenClient` 自动取 / 缓存令牌、透传 `request_id` 与 `traceparent`（ADR-073）、按 ADR-074 写 `ext_interaction` 台账。
  - M2M：OAuth 2.0 `client_credentials`，`sub` = 服务账号、`aud` = 被调应用、`act` = 调用方应用。
  - OBO：OAuth 2.0 Token Exchange（RFC 8693），`sub` = 用户、`act` = 应用 A。
- **被调侧**（`foundation-starter-security`）：标注 `@RequireAppGrant(appCode, permission)`，校验撤销、调用方是否登记、授权关系是否生效，OBO 时叠加「用户权限 ∩ 调用方被授予能力」收窄；不满足返回 **403** 并上报审计。授权关系变更发 `fdn.app.grant.changed`，按 `(调用方应用, 被调应用)` 失效缓存，事件丢失靠 TTL 兜底。

```java
public interface AppTokenClient {                       // 调用方；实现见 BAS-30
    String clientCredentials(String targetAppCode);     // M2M
    String onBehalfOf(String targetAppCode, Principal user); // OBO（RFC 8693）
}

public @interface RequireAppGrant {                     // 被调侧；实现见 BAS-30
    String appCode();
    String permission();
}
```

### 10. 「新应用接入」在代码侧的落地（S15 §12.1）

S15 §12.1 的六步接入，在代码侧的落点如下：

| # | S15 §12.1 步骤 | 代码侧落点（本包 / 相关包） |
| --- | --- | --- |
| 1 | 在 Foundation 登记应用，自动开通 OIDC 客户端与服务账号 | Foundation 管理台操作（BAS-04）；应用侧只填 `foundation.app-code` 与服务账号凭据 |
| 2 | 应用后端引 starter、配 Foundation 地址、应用编码、服务账号；前端引前端包 | `pom.xml` 加 `foundation-starter-security` / `-audit` / `-client`；`application.yml` 配 `foundation.*`（§13）；前端包由 BAS-FE-01（[F-00 §3.2](F-00-F阶段实施计划.md)）提供 |
| 3 | 应用提交权限清单（菜单、权限码、维度） | 应用仓库维护清单文件（由权限码常量目录导出），CI 或启动时调 `/api/fdn/apps/{code}/manifest:import`（BAS-05 提供）；starter 提供清单校验工具 |
| 4 | 管理员在 Foundation 配置应用角色与授权 | Foundation 管理台操作（BAS-05），不在应用侧 |
| 5 | 应用订阅事件（人员停用、授权变更、字典变更） | starter **自动订阅** `fdn.authz.changed` / `fdn.dict.changed` / `fdn.param.changed` / `fdn.org.changed` / `fdn.person.*`（§4、§7、§8）；应用侧只声明 consumer 组名 |
| 6 | 跑接入自检六项（登录、菜单、按钮、数据范围、单点注销、离职联动） | `foundation-test` 提供自检基类（`@Tag("starter-selfcheck")`），六项各自断言；管理台自检页（BAS-FE-01）逐项展示结果 |

**「六项接入自检全部通过」是 BAS-08 的验收**（[F-00 §3.1](F-00-F阶段实施计划.md)）；[F-01](F-01-并行开发任务包.md) §4.3 的 PF-C06 验收口径同为「六项接入自检」。

### 11. starter 与 Foundation 服务之间的调用

- **服务账号**：应用登记时由 Foundation 自动开通（BAS-04，`client_credentials`）。starter 用服务账号换 access token，缓存至过期前，调 Foundation 的 `/api/fdn/...` 只读接口与审计上报接口。凭据**只写不读**，经环境变量注入（对齐 [F-02 §BAS-01](F-02-工程底座与骨架.md)）。
- **调用清单**：
  | 用途 | 接口 | 频次 |
  | --- | --- | --- |
  | 取快照 / 版本 | `GET /api/fdn/apps/{appCode}/persons/{personId}/snapshot[/version]`（[F-06](F-06-数据权限与授权快照.md)） | 缓存未命中或回源时 |
  | 字典 / 参数 | `GET /api/foundation/dict/{type}`、`/param/{key}`（[F-02 §BAS-09](F-02-工程底座与骨架.md)） | 缓存未命中时 |
  | 组织 / 人员 | 组织树、人员查询（S15 §14.1 组织分组） | 缓存未命中时 |
  | 审计上报 | `POST /api/fdn/audit/events:batch`（§6） | 批量 |
- **重试与熔断**：读接口按**指数退避 + 抖动**重试（默认最多 3 次）；连续失败按本地失败率短窗口**熔断**，熔断期内直接用缓存 / 进入降级（快照 **拒绝**、字典参数用旧缓存）。**不新增第三方依赖**：重试与熔断用 starter 自带的轻量实现，避免扩大 Foundation 依赖面（[F-02](F-02-工程底座与骨架.md) 的 `foundation-dependencies` 未列 Resilience4j）。
- **超时**：连接 1s、读 3s（默认），全部可配。
- **透传**：所有出站请求带 `traceparent` 与 `request_id`（[M0-01 ENG-11 / GOV-08](F-02-工程底座与骨架.md) 的 trace 约定）；写接口带 `Idempotency-Key`（S15 §14.1）。
- **不把 Foundation 拉进热路径**：以上调用**都不在请求线程同步发生**的路径上（快照 / 字典 / 组织均有本地缓存），从架构上保证「Foundation 短时不可用不影响已登录用户」（ADR-051 第 5 条）。

### 12. 依赖与打包

- **starter 不强制引入具体 web / mq 实现**：`foundation-starter-security` / `-client` 对 `spring-boot-starter-web`、`foundation-starter-mq` 为 `optional` 或 `provided`；应用没引入 web 时，`FoundationSecurityAutoConfiguration` 整体不激活（`@ConditionalOnClass`）。这样 starter 也能给定时任务、MQ 消费者等非 web 进程复用（只需 `PrincipalHolder.runAs`）。
- **Redis**：快照 / 字典 / 参数 / 组织的缓存需要 Redis；引入 `foundation-starter-cache` 时启用，未引入时**退化为进程内缓存**（TTL 更短，且事件订阅需应用自行桥接）——由 `@ConditionalOnBean(RedisTemplate.class)` 决定。
- **Maven 坐标**（`com.foundation` 组，版本随 `foundation-dependencies`，[F-02 §BAS-23](F-02-工程底座与骨架.md)）：
  ```xml
  <dependency><groupId>com.foundation</groupId><artifactId>foundation-starter-security</artifactId></dependency>
  <dependency><groupId>com.foundation</groupId><artifactId>foundation-starter-audit</artifactId></dependency>
  <dependency><groupId>com.foundation</groupId><artifactId>foundation-starter-client</artifactId></dependency>
  ```
- **与九个 starter 的边界**（S15 §5.2）：本文实现 security / audit / client 三者的**接入能力**；core、web、data、mq、cache、observability 的通用能力由 [F-02 §BAS-24](F-02-工程底座与骨架.md) 提供，本文只依赖。Tioka 侧「把 `tioka-framework` 换成 starter」是 BAS-26（[F-02](F-02-工程底座与骨架.md)）。
- **打包**：每个 starter 是普通 jar（含 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`），不打包成可执行 jar。

### 13. 配置项（`foundation.*`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `foundation.enabled` | `true` | 接入总开关；关时所有 starter 自动配置不激活（便于本地模式） |
| `foundation.app-code` | —（必填） | 应用编码（BAS-04 登记，正则 `^[a-z][a-z0-9-]{1,31}$`） |
| `foundation.base-url` | `http://localhost:8080` | Foundation 服务基址 |
| `foundation.service-account.client-id` / `client-secret` | — | 服务账号凭据（`client_credentials`；secret 只写不读，经环境变量） |
| `foundation.security.token-source` | `jwt` | `jwt`（资源服务器校验）或 `opaque`（应用自定义 `PrincipalResolver`） |
| `foundation.security.public-api-paths` | `/actuator/**` | `@PublicApi` 之外的免鉴权路径（框架级，慎用） |
| `foundation.authz.snapshot.cache-ttl` | `5m` | 快照缓存 TTL（事件丢失兜底，S15 §11.3） |
| `foundation.authz.snapshot.local-cache.enabled` | `false` | 进程内一级缓存（§4；默认关） |
| `foundation.authz.snapshot.fail-mode` | `deny` | 缓存过期且取不到时的行为：`deny`（拒绝） |
| `foundation.authz.event.topic` | `fdn-events` | 事件主题（S15 §14.2） |
| `foundation.authz.event.consumer-group` | `{appCode}-authz` | 消费者组名 |
| `foundation.data-scope.default-mode` | `deny` | 注解存在但快照缺失时的行为：`deny` |
| `foundation.audit.batch-size` | `100` | 上报批大小 |
| `foundation.audit.flush-interval` | `2s` | 上报刷新间隔 |
| `foundation.audit.retry.max-attempts` | `5` | 失败重试上限（超过转磁盘 spool） |
| `foundation.audit.spool-dir` | `${java.io.tmpdir}/fdn-audit-spool` | 本地磁盘兜底目录（文件，不落库） |
| `foundation.client.connect-timeout` / `read-timeout` | `1s` / `3s` | 出站超时 |
| `foundation.client.retry.max-attempts` | `3` | 读接口重试上限 |
| `foundation.client.circuit-breaker.failure-rate` | `50%` | 熔断失败率阈值（短窗口） |
| `foundation.dict.cache-ttl` / `foundation.param.cache-ttl` | `10m` | 字典 / 参数缓存 TTL |
| `foundation.org.cache-ttl` / `foundation.person.cache-ttl` | `10m` | 组织 / 人员缓存 TTL |

```yaml
foundation:
  enabled: true
  app-code: tioka
  base-url: ${FOUNDATION_BASE_URL:http://localhost:8080}
  service-account:
    client-id: tioka
    client-secret: ${FOUNDATION_SERVICE_SECRET}
  security:
    token-source: opaque          # Tioka 用自换 tka_ 令牌（M1-01 FND-05）
  authz:
    snapshot: { cache-ttl: 5m, fail-mode: deny, local-cache: { enabled: false } }
    event: { topic: fdn-events, consumer-group: tioka-authz }
```

### 14. 包结构与关键类清单

```text
com.foundation.starter.security
├── config/          FoundationSecurityAutoConfiguration、FoundationSecurityProperties、
│                    FoundationAuthzProperties、FoundationDataScopeProperties
├── principal/       Principal、PrincipalResolver、JwtPrincipalResolver、
│                    OpaqueTokenPrincipalResolver、PrincipalHolder、CurrentUserArgumentResolver
├── permission/      @RequirePermission、@PublicApi、PermissionAspect
├── snapshot/        AuthzSnapshotClient、AuthzSnapshot、SnapshotCache、AuthzChangedListener
├── datascope/       @DataScope、DataScopeFilterProvider、DataScopeFilter
└── appgrant/        @RequireAppGrant（占位，实现见 F-12）

com.foundation.starter.audit
├── config/          FoundationAuditAutoConfiguration、FoundationAuditProperties
├── annotation/      @Audited
├── report/          AuditReporter、AsyncBatchAuditReporter、AuditEvent
└── spool/           DiskSpool

com.foundation.starter.client
├── config/          FoundationClientAutoConfiguration、FoundationClientProperties
├── http/            FoundationHttpClient、ServiceAccountTokenProvider
├── dict/            DictClient
├── param/           ParamClient
├── org/             OrgClient、PersonClient
└── appgrant/        AppTokenClient（占位，实现见 F-12）
```

| 关键类 / 接口 | 职责 | 归属（本文小节） |
| --- | --- | --- |
| `Principal` / `PrincipalHolder` | 令牌身份承载与线程内注入（只带身份） | §2 |
| `PrincipalResolver` | 令牌来源抽象（jwt / opaque） | §2 |
| `@RequirePermission` / `@PublicApi` / `PermissionAspect` | 权限注解与拦截（无权限 403 + DENIED 审计） | §3 |
| `AuthzSnapshotClient` / `AuthzSnapshot` / `SnapshotCache` | 快照取用、缓存、降级拒绝 | §4 |
| `@DataScope` / `DataScopeFilterProvider` / `DataScopeFilter` | 数据范围条件生成 | §5 |
| `@Audited` / `AuditReporter` / `AsyncBatchAuditReporter` / `DiskSpool` | 审计上报与磁盘兜底 | §6 |
| `DictClient` / `ParamClient` | 字典与参数读取（敏感参数拒读） | §7 |
| `OrgClient` / `PersonClient` | 组织与人员查询 | §8 |
| `AppTokenClient` / `@RequireAppGrant` | 应用间调用（**占位**） | §9（实现 F-12） |

### 15. 测试

| 用例 | 层 | 断言 |
| --- | --- | --- |
| `SnapshotCacheTest` | 单元 | 命中不打回源；`fdn.authz.changed` 后缓存被删；TTL 到期强制回源；版本号变化不串缓存 |
| `SnapshotDegradeTest` | 单元 | 未过期缓存可用；**已过期且回源失败 → 抛 `FORBIDDEN`（不放行）** |
| `PermissionAspectTest` | 单元 | 无权限 → 403（`40300`）且写 `DENIED`；多值「全部需要」；`@PublicApi` 放行；未登录 → 401（`40100`） |
| `DataScopeFilterTest` | 单元 | 五档各生成正确 SQL；「全部」→ `1=1`；跨角色并集、同角色内 AND；注解存在但快照缺失 → `FORBIDDEN` |
| `AuditSpoolTest` | 单元 | 批量刷新的触发条件；重试耗尽后落盘；补偿线程重放并清理 |
| `PrincipalPrincipalHolderTest` | 单元 | `runAs` 恢复身份与快照版本；请求边界清空 `ThreadLocal` |
| `Testcontainers`：PostgreSQL 16 + Redis 7 + **一个 mock Foundation 服务**（WireMock） | 集成 | 服务账号取 token；快照 / 字典 / 组织取用与缓存；审计批量上报与幂等；`fdn-events` 消费失效 |
| `SnapshotEventIT` | 集成 | 发 `fdn.authz.changed` 后，本地缓存对应键被删，下一次取用回源到新版本 |
| **闸门** `GfStarterGateIT`（`@Tag("gate")`） | 闸门 | ① 无权限请求返回 **403**；② 快照失效事件发出后 **5 秒内**本地缓存生效（新版本可见）；③ mock Foundation 停用且缓存过期时**拒绝**（403）而非放行 |

**闸门归属**：`GfStarterGateIT` 由本包（PF-C06）在 W85 去 `@Disabled` 转绿；与 [F-00 §6.1](F-00-F阶段实施计划.md) 的 `GfSnapshotEventGateIT`（W84，BAS-07）衔接——后者验事件与版本，前者验 starter 侧的缓存与拒绝行为。

### 16. 完成标准

- [ ] 三个 starter（security / audit / client）可在示例工程中按需引用，自动配置与 `foundation.*` 绑定生效（[F-02 §BAS-25](F-02-工程底座与骨架.md) 的模板可直接引用）
- [ ] §1 能力清单 ①〜⑧ 全部实现并有测试；⑨ 的接口占位就位（实现指向 F-12）
- [ ] 权限校验无权限返回 **403**、未登录返回 **401**，错误码沿用 `40300` / `40100`（ADR-029 第 14、18 条）
- [ ] 快照按「人员 + 应用」缓存、TTL 5 分钟、`fdn.authz.changed` 删缓存、**过期且取不到时拒绝**
- [ ] 审计异步批量 + 失败重试 + 磁盘兜底；Tioka 哈希链与归档格式不变（M4 约束 F46）
- [ ] 字典 / 参数 / 组织 / 人员客户端带本地缓存与事件刷新；敏感参数拒读
- [ ] `GfStarterGateIT` 转绿（W85）；示例应用的**六项接入自检全部通过**（[F-00 §3.1](F-00-F阶段实施计划.md)）
- [ ] 全程未引入 `tenant_id`；Foundation 不出现在请求热路径（快照 / 字典 / 组织均走本地缓存）
- [ ] `foundation-test` 的 ArchUnit 规则集在示例工程生效（Controller 必须声明权限或 `@PublicApi`、权限码只引用常量目录、禁止 `tenant_id`）

### 迁移编号说明

本包**不新增 Foundation 库表**（快照 / 字典 / 参数 / 组织缓存都在应用侧 Redis，审计兜底为应用侧文件）。因此本文件**不占 Foundation 迁移号**；若日后审计 spool 改为落库（或新增「应用接入自检结果」等表），用 **Foundation V24＋**，以合入时首个空号为准（承接 [F-05](F-05-应用与权限中心.md) 的 V10〜V23）。

---

## 与其他文档的关系

| 文档 | 关系 |
| --- | --- |
| [S15 Foundation 基础平台系统](../systems/S15-Foundation基础平台系统.md) | **设计权威**：§5.2（九个 starter 与依赖）、§9（令牌只带身份）、§11.3（快照）、§11.4（数据范围两种用法）、§12（接入步骤与 starter 能力）、§13（公共服务）、§14（接口与事件）、§22（应用间授权） |
| [F-00 F 阶段实施计划](F-00-F阶段实施计划.md) | **排期权威**：BAS-08 行（W84–W85）、§5 实现约定、§6.1 闸门（`GfSnapshotEventGateIT` 与本包 `GfStarterGateIT`）、§7 风险 6（快照缓存） |
| [F-01 并行开发任务包](F-01-并行开发任务包.md) | 本包 = PF-C06；仓库边界、迁移号段（Foundation 自 V1）、硬约束 |
| [F-02 工程底座与骨架](F-02-工程底座与骨架.md) | BAS-24（九个 starter 骨架、`foundation-test`、ArchUnit 规则集）、BAS-01（服务骨架、Keycloak 连接）、BAS-09（字典与参数，§7 的上游）、BAS-25（工程模板） |
| [F-05 应用与权限中心](F-05-应用与权限中心.md) | BAS-04（应用编码、服务账号、OIDC 客户端）、BAS-05（权限码目录、清单导入）、BAS-27（功能开关经快照与 starter 生效）——本文 `@RequirePermission` / 清单的权限码来源 |
| [F-06 数据权限与授权快照](F-06-数据权限与授权快照.md) | BAS-06（部门五档、自定义维度）、BAS-07（快照接口、`fdn.authz.changed`、版本与降级约定）——本文 §4、§5 的契约来源 |
| [M1-01 权限与身份联邦](M1-01-权限与身份联邦.md) | 体例与 Tioka 侧约定：`Principal`、`@RequirePermission`、`@PublicApi`、权限码常量目录、`VisibilityFilter` 条件生成范式、审计 `DENIED` 用独立事务 |
| [00-施工总纲](00-施工总纲.md) | §2 全局约定（分层、标识、错误与响应、配置、测试分层）、迁移号段 |
| [ADR-029 设计定案](../adr/ADR-029-design-rulings.md) | 第 14、18 条（错误码）、第 27 条（HCI/工作项编号）、第 32 条（OIDC 令牌与 JWT 资源服务器路径）、第 60 条（禁止 `tenant_id`）、第 69 条（Foundation 定案）、第 81 条（F2 增强批次，应用间授权归 F2） |
| [ADR-051 Foundation 基础平台](../adr/ADR-051-foundation-platform.md) | 接入方式（SDK + 契约）、令牌只带身份、**Foundation 不在请求热路径**、六项接入自检 |
| [ADR-052](../adr/ADR-052-org-account-authz-center.md) / [ADR-053](../adr/ADR-053-foundation-common-services.md) / [ADR-054](../adr/ADR-054-engineering-base-subsystem-microfrontend.md) | 组织账号权限中心、公共服务取舍、底座与微前端（功能开关经快照生效） |
| [ADR-076 应用间授权](../adr/ADR-076-app-to-app-authorization.md) | §9 的接口占位依据；**实现归 F-12 / BAS-30**，本文只登记接入点 |
| [F-12（应用间授权）](F-00-F阶段实施计划.md) | BAS-29〜30（F2 增强批次）：`@RequireAppGrant`、`fdn_app_grant`、M2M / OBO 客户端的真实实现，替换本文 §9 的占位 |

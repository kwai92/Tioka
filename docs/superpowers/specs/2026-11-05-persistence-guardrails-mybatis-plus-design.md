# Tioka 持久化护栏与 MyBatis-Plus 收口设计

> 日期：2026-11-05  
> 状态：已批准（待实施计划）  
> 依据：`41-TA` / `42-DA` / `44-EA`；会话决策：方案 A + BaseDO 逻辑删/审计大一统；存量立刻全量失败并修复。

---

## 1. 问题与目标

### 问题

架构已定 ORM = **MyBatis-Plus**，分层要求 `application → infrastructure.Repository → Mapper`。现状为大量 `JdbcTemplate` + 手写 SQL；本地配置排除了 MyBatis-Plus 自动配置；Cursor/ArchUnit 未覆盖持久化约束，导致文档与代码持续偏离。

### 目标

1. **立刻硬失败**：任何生产代码使用 `JdbcTemplate`（及同类直接 JDBC API）导致 ArchUnit 失败。  
2. **全量修复**：清除存量 JDBC，统一 MyBatis-Plus。  
3. **行级字段大一统**：`BaseDO` 承载审计字段 + 逻辑删除；不适用实体有显式例外类型。  
4. **项目级防偏**：Cursor Rules + ArchUnit + 模块依赖对齐，后续生成/提交不再漂回 JDBC。

### 非目标

- 不引入 `tenantId`（本次明确排除；多租户另开 ADR）。  
- 不做 AuditSink / SIEM 业务审计外送改造。  
- 不做完整 DataScope 行级权限引擎。  
- 不改前端。  
- 不借机重写业务语义。

---

## 2. 字段与基类约定（已锁定）

### 2.1 `BaseDO`（`tioka-framework`）

| Java 属性 | 列名（建议 snake） | 说明 |
| --- | --- | --- |
| `id` | `id` | 主键；沿用现有 VARCHAR/雪花策略，本轮不强制改类型 |
| `createAt` | `create_at` | 创建时间；插入自动填充 |
| `updateAt` | `update_at` | 更新时间；插入/更新自动填充 |
| `createBy` | `create_by` | 创建人标识（用户 ID 或 `system`） |
| `updateBy` | `update_by` | 更新人标识 |
| `deleted` | `deleted` | 逻辑删除；Java `Boolean`，库 `BOOLEAN`/`TINYINT`；未删=`false`/`0`，已删=`true`/`1`；`@TableLogic` |

时间类型统一 `java.time.LocalDateTime`（UTC 或 JVM 默认区在配置中写死为 **系统默认时区**，填充值为 `LocalDateTime.now()`）。  
`createBy`/`updateBy` 类型统一 `String`。

**禁止**在 `BaseDO` 增加 `tenantId`。

### 2.2 可选 `WorkspaceAwareDO extends BaseDO`

| Java 属性 | 列名 | 说明 |
| --- | --- | --- |
| `workspaceId` | `workspace_id` | 空间级资源强制；无空间语义的实体不继承 |

### 2.3 例外基类 / 标记

| 类型 | 用途 | 审计/逻辑删 |
| --- | --- | --- |
| `ImmutableSnapshotDO`（或等价标记） | 已发布不可变快照（AgentVersion 发布行、RoutingPlan 发布、FlowVersion 等） | 可有 `createAt/createBy`；**禁止**逻辑删与随意 update；废止走状态机 |
| `AppendOnlyDO`（或等价标记） | 高频追加日志（usage、event、outbox 等） | 时间戳必有；**不**做逻辑删；清理靠 TTL/归档任务 |

新建业务 Entity 必须继承上述之一；禁止无基类裸 POJO 落库。

---

## 3. 分层与依赖

```
controller / api 适配
        ↓
application.XxxService     （事务边界；禁止直接 Mapper/JdbcTemplate）
        ↓
infrastructure.XxxRepository   （薄封装，可测）
        ↓
infrastructure.mapper.XxxMapper extends BaseMapper<Entity>
```

- 现有 `*Store` 迁为 Repository + Mapper，去掉 `JdbcTemplate`。  
- 所有 `*-biz` 引入 `mybatis-plus-spring-boot3-starter`。  
- 取消 `application-local.yml` 对 `MybatisPlusAutoConfiguration` 的 exclude。  
- 删除主数据只能走逻辑删（`removeById` / `deleted` 更新）；禁止业务代码对主数据表物理 `DELETE`。

---

## 4. 项目级护栏

### 4.1 Cursor Rules（`.cursor/rules/`）

1. **alwaysApply**：架构权威文档指针（`41-TA`/`42-DA`/`44-EA`）；biz 禁止互依；持久化必须 MyBatis-Plus + BaseDO 字段集。  
2. **globs `**/tioka-module-*-biz/**/*.java`**：禁止 `JdbcTemplate` / `NamedParameterJdbcTemplate` / `DriverManager`；Service 禁止直接依赖 Mapper；Entity 必须继承约定基类。

### 4.2 ArchUnit（`tioka-server`，生产类扫描）

| 规则 | 行为 |
| --- | --- |
| 禁止 JDBC 模板 | `com.tioka..` 生产代码不得依赖 `org.springframework.jdbc..`（测试代码可除外） |
| 分层 | `..application..` 不得依赖 `..mapper..` 与 `com.baomidou.mybatisplus.core.mapper` |
| 模块依赖 | 保留并加强现有 R1–R3（biz 不互依、runtime 不直连 connector） |
| Entity 基类 | 凡带 `@TableName` 的类必须是 `BaseDO` / `WorkspaceAwareDO` / `ImmutableSnapshotDO` / `AppendOnlyDO` 的子类（ArchUnit：`assignableTo` 断言） |

**策略**：无白名单过渡；规则一上线，存量必须同 PR/同波次修绿。

### 4.3 配置

- 全局逻辑删字段：`deleted`。  
- `MetaObjectHandler`：填充 `createAt`/`updateAt`/`createBy`/`updateBy`；操作者从安全上下文读取，缺失时用 `system`。  
- Mapper 扫描锁定：`com.tioka.**.infrastructure.mapper`。

---

## 5. Schema 变更

- 同步修改 `schema.sql`、`schema-pg.sql` 及测试用 schema。  
- 凡适用 `BaseDO` 的表补齐：`create_at`, `update_at`, `create_by`, `update_by`, `deleted`。  
- 需要空间语义的表保留/补齐 `workspace_id`。  
- 存量/DDL 默认：`deleted = FALSE`；`create_at`/`update_at` 默认 `CURRENT_TIMESTAMP`；`create_by`/`update_by` 默认 `'system'`。  
- **不**添加 `tenant_id`。

与 `42-DA` 字面 `created_at`/`tenant_id` 的差异以本文 + 后续短 ADR 为准（DA 文档在实施后回写对齐字段名，并注明暂无 tenant 列）。

---

## 6. 迁移范围与顺序

约 30 个生产/测试 Java 文件现用 `JdbcTemplate`，覆盖 system / infra / runtime / orch / chat / knowledge / flow / model / agent / mcp / skill / connector。

建议顺序：

1. Framework：`BaseDO` 族、`MetaObjectHandler`、MyBatis 自动配置。  
2. Cursor Rules + ArchUnit（预期先红）。  
3. Schema 补字段。  
4. 模块迁移：`system` → `infra` → `runtime`/`orchestration` → `chat`/`knowledge`/`flow` → `model`/`agent`/`mcp`/`skill` → `connector`。  
5. 测试改为装配 Mapper/Repository 或 `@MybatisPlusTest`；禁止测试借 `JdbcTemplate` 写业务持久化绕过规则（schema init SQL 允许）。  
6. `mvn -pl tioka-server -am test` 全绿。

---

## 7. 成功标准

- 生产代码 `JdbcTemplate`（及 `org.springframework.jdbc`）引用数 = **0**。  
- ArchUnit 持久化规则在 CI/本地测试中强制执行；新增 JDBC 必红。  
- 适用表均含约定审计 + `deleted` 字段；Entity 继承约定基类。  
- Cursor 在 biz 场景加载对应 rules。  
- 现有关键单测与 `tioka-server` 启动路径通过。

---

## 8. 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| H2 `MERGE`/方言与 MP 行为差异 | 统一用 Mapper API；避免依赖 H2 特有 MERGE |
| 不可变快照被误加逻辑删 | `ImmutableSnapshotDO` + 评审清单 + ArchUnit 例外登记 |
| 改动面大导致长时间红 | 按模块波次修，但合并门禁以 server 测试全绿为准，不合并半成品主线 |
| 操作者上下文缺失 | Handler 回退 `system`；后续接 Sa-Token 再收紧 |

---

## 9. 决策记录摘要

| 决策 | 选择 |
| --- | --- |
| ORM | MyBatis-Plus（方案 A） |
| 存量 JDBC | 立刻全量失败并修复 |
| 审计字段 | `createAt`, `updateAt`, `createBy`, `updateBy` |
| 逻辑删 | `deleted` |
| 租户列 | **不做** `tenantId` |
| 空间列 | 需要时用 `WorkspaceAwareDO.workspaceId` |

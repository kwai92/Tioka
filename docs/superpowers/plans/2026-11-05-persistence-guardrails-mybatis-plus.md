# Persistence Guardrails + MyBatis-Plus 收口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 清除全仓 `JdbcTemplate` 业务持久化，统一 MyBatis-Plus + `BaseDO`（`createAt`/`updateAt`/`createBy`/`updateBy`/`deleted`，无 `tenantId`），并用 Cursor Rules + ArchUnit 永久卡住回退。

**Architecture:** Framework 提供 `BaseDO` 族与 `MetaObjectHandler`；各 `*-biz` 的 Entity/Mapper/Repository 落在 `infrastructure`；`application` 只依赖 Repository；`tioka-server` ArchUnit 禁止 `org.springframework.jdbc` 与 application→mapper 直连。

**Tech Stack:** Java 17、Spring Boot 3.2.x、MyBatis-Plus 3.5.9、ArchUnit 1.3.0、H2（local）/PostgreSQL。

**Spec:** [docs/superpowers/specs/2026-11-05-persistence-guardrails-mybatis-plus-design.md](../specs/2026-11-05-persistence-guardrails-mybatis-plus-design.md)

## Global Constraints

- 审计字段仅：`createAt`, `updateAt`, `createBy`, `updateBy`, `deleted`（列：`create_at`, `update_at`, `create_by`, `update_by`, `deleted`）
- **禁止** `tenantId` / `tenant_id`
- 时间类型：`java.time.LocalDateTime`；填充 `LocalDateTime.now()`；操作者缺失时 `createBy`/`updateBy` = `"system"`
- Mapper 包：`com.tioka.**.infrastructure.mapper`
- 生产代码禁止依赖 `org.springframework.jdbc..`
- `application` 禁止依赖 `..infrastructure.mapper..` 与 `com.baomidou.mybatisplus.core.mapper`
- 主数据删除只走逻辑删；不可变快照用 `ImmutableSnapshotDO`；追加日志用 `AppendOnlyDO`
- Git：仅在用户明确要求时 commit（本计划 Step「Commit」默认跳过，除非用户已授权）

---

## File map（新建 / 改造责任）

| 路径 | 责任 |
| --- | --- |
| `tioka-framework/.../persistence/BaseDO.java` 等 | 统一 DO 基类 |
| `tioka-framework/.../persistence/OperatorAuditor.java` | 解析当前操作者字符串 |
| `tioka-framework/.../persistence/TiokaMetaObjectHandler.java` | MP 自动填充 |
| `tioka-framework/.../persistence/MybatisPlusConfig.java` | 逻辑删等全局配置（若放 server 亦可，优先 framework 自动配置） |
| `.cursor/rules/*.mdc` | AI 硬约束 |
| `tioka-server/.../PersistenceArchTest.java` | JDBC/分层/Entity 基类 ArchUnit |
| `tioka-server/src/main/resources/schema.sql` + `schema-pg.sql` | 全表审计列 |
| 各 `*-biz/.../infrastructure/**` | Entity、Mapper、Repository |
| 各 `application/*Service` / `domain/*Store` | 去掉 JdbcTemplate，改调 Repository |

---

### Task 1: Framework — BaseDO 族 + 审计填充

**Files:**
- Create: `tioka-framework/src/main/java/com/tioka/framework/persistence/BaseDO.java`
- Create: `tioka-framework/src/main/java/com/tioka/framework/persistence/WorkspaceAwareDO.java`
- Create: `tioka-framework/src/main/java/com/tioka/framework/persistence/ImmutableSnapshotDO.java`
- Create: `tioka-framework/src/main/java/com/tioka/framework/persistence/AppendOnlyDO.java`
- Create: `tioka-framework/src/main/java/com/tioka/framework/persistence/OperatorAuditor.java`
- Create: `tioka-framework/src/main/java/com/tioka/framework/persistence/TiokaMetaObjectHandler.java`
- Create: `tioka-framework/src/main/java/com/tioka/framework/persistence/MybatisPlusConfiguration.java`
- Create: `tioka-framework/src/test/java/com/tioka/framework/persistence/OperatorAuditorTest.java`
- Modify: `tioka-framework/pom.xml` — 增加 `mybatis-plus-spring-boot3-starter`（compile）

**Interfaces:**
- Produces: `BaseDO` getters/setters for `createAt`, `updateAt`, `createBy`, `updateBy`, `deleted`
- Produces: `OperatorAuditor.currentOperator(): String`
- Produces: `TiokaMetaObjectHandler` registered as Spring `@Component`

- [ ] **Step 1: 写失败单测（操作者回退）**

```java
package com.tioka.framework.persistence;

import com.tioka.framework.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OperatorAuditorTest {
    @AfterEach
    void clear() { TenantContext.clear(); }

    @Test
    void fallsBackToSystemWhenNoUser() {
        assertEquals("system", OperatorAuditor.currentOperator());
    }

    @Test
    void usesUserIdWhenPresent() {
        TenantContext.set(null, 42L, null);
        assertEquals("42", OperatorAuditor.currentOperator());
    }
}
```

- [ ] **Step 2: 运行单测确认失败**

Run: `mvn -pl tioka-framework -Dtest=OperatorAuditorTest test`  
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现基类与填充（最小集）**

`OperatorAuditor.java`:

```java
package com.tioka.framework.persistence;

import com.tioka.framework.tenant.TenantContext;

public final class OperatorAuditor {
    private OperatorAuditor() {}
    public static String currentOperator() {
        Long userId = TenantContext.getUserId();
        return userId == null ? "system" : String.valueOf(userId);
    }
}
```

`BaseDO.java`（字段 + getter/setter；`@TableLogic` on `deleted`；`@TableField(fill=...)` on 审计字段）：

```java
package com.tioka.framework.persistence;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import java.time.LocalDateTime;

public abstract class BaseDO {
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateAt;
    @TableField(fill = FieldFill.INSERT)
    private String createBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updateBy;
    @TableLogic
    private Boolean deleted;
    // getters/setters
}
```

`WorkspaceAwareDO`：extends `BaseDO` + `workspaceId`。  
`ImmutableSnapshotDO`：仅 `createAt`/`createBy`（无 `deleted`/`update*` 逻辑删）。  
`AppendOnlyDO`：`createAt`/`createBy`（无逻辑删）。

`TiokaMetaObjectHandler`：insert 填四个审计字段 + `deleted=false`（若空）；update 填 `updateAt`/`updateBy`。

`MybatisPlusConfiguration`：`@Configuration` + `@MapperScan("com.tioka.**.infrastructure.mapper")`；如需 `GlobalConfig` 明确 logic-delete 字段名 `deleted`。

`pom.xml` 增加：

```xml
<dependency>
  <groupId>com.baomidou</groupId>
  <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
</dependency>
```

- [ ] **Step 4: 再跑单测**

Run: `mvn -pl tioka-framework -Dtest=OperatorAuditorTest test`  
Expected: PASS

- [ ] **Step 5: Commit（仅用户授权时）**

```bash
git add tioka-framework
git commit -m "$(cat <<'EOF'
feat: 增加 BaseDO 与 MyBatis-Plus 审计填充

EOF
)"
```

---

### Task 2: Cursor Rules（防后续 AI 偏离）

**Files:**
- Create: `.cursor/rules/tioka-architecture.mdc`（`alwaysApply: true`）
- Create: `.cursor/rules/tioka-persistence-biz.mdc`（`globs: **/tioka-module-*-biz/**/*.java`）

- [ ] **Step 1: 写入 alwaysApply 规则**

`.cursor/rules/tioka-architecture.mdc` 要点（全文 <50 行）：
- 权威文档：`docs/architecture/enterprise/41-TA`、`42-DA`、`44-EA` 与 spec `2026-11-05-persistence-guardrails-mybatis-plus-design.md`
- `*-biz` 禁止依赖其他 `*-biz`
- 持久化：仅 MyBatis-Plus；禁止新增 `JdbcTemplate`
- BaseDO 字段集；禁止 `tenantId`

- [ ] **Step 2: 写入 biz 持久化规则**

`.cursor/rules/tioka-persistence-biz.mdc`：
- ❌ `JdbcTemplate` / `NamedParameterJdbcTemplate` / `DriverManager`
- ❌ `application` 注入 Mapper
- ✅ `application` → `infrastructure.*Repository` → `infrastructure.mapper.*Mapper`
- Entity 必须继承 `BaseDO` / `WorkspaceAwareDO` / `ImmutableSnapshotDO` / `AppendOnlyDO`
- 主数据删除用逻辑删

- [ ] **Step 3: 人工确认文件存在**

Run: `ls .cursor/rules/`  
Expected: 两个 `.mdc` 文件

- [ ] **Step 4: Commit（仅用户授权时）**

---

### Task 3: Schema 全表补审计列

**Files:**
- Modify: `tioka-server/src/main/resources/schema.sql`
- Modify: `tioka-server/src/main/resources/schema-pg.sql`
- Modify: 各模块 `src/test/resources/*-schema.sql`（`auth-test-schema.sql`, `secret-test-schema.sql`, `model-test-schema.sql` 等）

**约定片段（每张适用表追加；主键策略不变）：**

```sql
create_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
update_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
create_by VARCHAR(64) NOT NULL DEFAULT 'system',
update_by VARCHAR(64) NOT NULL DEFAULT 'system',
deleted BOOLEAN NOT NULL DEFAULT FALSE
```

**分类：**
- **BaseDO 表**（主数据/业务实体）：几乎全部现有业务表（`auth_user`, `sys_workspace`, `kb_*`, `chat_*`, `agent_*`, `mcp_*`, `flow_draft`, `skill_package`, …）
- **ImmutableSnapshotDO**：`agent_version`, `flow_version`, `model_routing_plan`（已发布语义）— 仅 `create_at`/`create_by`，**不加** `deleted`（若表需废止用 `published` 状态，不加逻辑删）
- **AppendOnlyDO**：`cost_event`, `trace_span`, `im_outbox`, `orch_why`, `auth_token`, `runtime_idem`, `orch_idem` — 仅 `create_at`/`create_by`（`im_outbox.created_at` **重命名为** `create_at` 以统一）

- [ ] **Step 1: 重写 `schema.sql` 全表定义**（按上表分类补齐；禁止新增 `tenant_id`）
- [ ] **Step 2: 同步 `schema-pg.sql`**（类型对齐 PG：`BOOLEAN`/`TIMESTAMPTZ` 或 `TIMESTAMP`，与现有 PG 文件风格一致）
- [ ] **Step 3: 同步测试 schema**
- [ ] **Step 4: 快速语法检查** — 启动依赖后续 Task；本步可用 H2 跑空应用或 `mvn -pl tioka-server -DskipTests package` 确保资源打进 jar
- [ ] **Step 5: Commit（仅用户授权时）**

---

### Task 4: 启用 MyBatis-Plus（server + 全 biz 依赖）

**Files:**
- Modify: `tioka-server/src/main/resources/application-local.yml` — **删除** `MybatisPlusAutoConfiguration` exclude
- Modify: 每个尚无 MP 的 `tioka-module-*-biz/pom.xml`，增加：

```xml
<dependency>
  <groupId>com.baomidou</groupId>
  <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
</dependency>
```

涉及（当前缺 MP）：`runtime`, `orchestration`, `chat`, `knowledge`, `flow`, `model`, `agent`, `mcp`, `skill`, `connector`（`system`/`infra` 已有则核验版本来自 BOM）。

可选 `application.yml`：

```yaml
mybatis-plus:
  global-config:
    db-config:
      logic-delete-field: deleted
      logic-delete-value: true
      logic-not-delete-value: false
  configuration:
    map-underscore-to-camel-case: true
```

- [ ] **Step 1: 改 yml + 各 pom**
- [ ] **Step 2: `mvn -pl tioka-server -am -DskipTests compile`**  
Expected: BUILD SUCCESS
- [ ] **Step 3: Commit（仅用户授权时）**

---

### Task 5: system 模块迁移（模板波次）

**Files:**
- Create: `.../system/infrastructure/persistence/AuthUserDO.java`（`@TableName("auth_user") extends BaseDO`，业务字段 + `id`）
- Create: `.../system/infrastructure/mapper/AuthUserMapper.java`
- Create: `.../system/infrastructure/AuthUserRepository.java`
- 同样模式：`AuthToken`（`AppendOnlyDO` 或无逻辑删 DO）、`SysWorkspace`（`WorkspaceAwareDO` 或 BaseDO+字段）、`SysQuota`、`ScimUser`、`SecretStore` 行实体
- Modify: `AuthService`, `WorkspaceService`, `QuotaService`, `ScimSyncService`, `LocalSecretStore`
- Modify: `AuthServiceTest`, `LocalSecretStoreTest` — 去掉测试里对业务路径注入 `JdbcTemplate`；改为 MyBatis SqlSession 装配或 Spring Boot test

**Interfaces:**
- `AuthUserRepository.findByUsername(String): AuthUserDO`
- `AuthUserRepository.insert(AuthUserDO): void`
- `AuthUserRepository.nextId(): long`（或 Service 内查 max）
- `AuthTokenRepository.save(token, userId)` / `findUserId(token)`

- [ ] **Step 1: 先改 `AuthServiceTest` 为依赖 Repository 接口（可用手写 fake）并失败**
- [ ] **Step 2: 实现 AuthUser Mapper/Repository 与 AuthService 无 JDBC**
- [ ] **Step 3: `mvn -pl tioka-module-system-biz -Dtest=AuthServiceTest,LocalSecretStoreTest test`**  
Expected: PASS
- [ ] **Step 4: 迁移 Workspace/Quota/Scim/Secret 同样去掉 JdbcTemplate**
- [ ] **Step 5: 确认 `rg JdbcTemplate tioka-module-system` 无生产命中**（测试若仍用 EmbeddedDatabaseBuilder 仅作数据源创建，不得把 `JdbcTemplate` 传入业务类）
- [ ] **Step 6: Commit（仅用户授权时）**

**AuthUserDO 示例：**

```java
@TableName("auth_user")
public class AuthUserDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private Long id;
    private String username;
    private String passwordHash;
    private String displayName;
    private Boolean enabled;
}
```

**AuthUserMapper：** `extends BaseMapper<AuthUserDO>` + `@Mapper`  
**Repository：** 构造注入 Mapper；`selectOne(Wrappers.<AuthUserDO>lambdaQuery().eq(AuthUserDO::getUsername, username))`

---

### Task 6: infra + runtime + orchestration

**Files（生产 JDBC 清单）：**
- `infra/.../TraceService.java` → `TraceSpanDO`（`AppendOnlyDO`）+ Mapper + Repository
- `runtime/.../RuntimeSessionService.java`, `AutomationJobService.java`, `CostTimelineService.java`
- `orchestration/.../OrchService.java`

- [ ] **Step 1: 各实体按表建 DO/Mapper/Repository，Service 改注入 Repository**
- [ ] **Step 2: `mvn -pl tioka-module-infra-biz,tioka-module-runtime-biz,tioka-module-orchestration-biz -am test`**（有测跑测；无测则 compile）
- [ ] **Step 3: `rg JdbcTemplate` 上述模块 main 为 0**
- [ ] **Step 4: Commit（仅用户授权时）**

---

### Task 7: chat + knowledge + flow

**Files:**
- `chat/domain/ChatStore.java`, `chat/application/ChatDensityService.java`
- `knowledge/application/KnowledgeService.java`, `WikiService.java`, `DataSourceService.java`
- `flow/application/FlowService.java`, `FlowPublicationService.java`

- [ ] **Step 1: `ChatStore`/`KnowledgeService`/`FlowService` 改为 Repository 门面（可保留类名 Store 作 Repository 别名，但包迁到 `infrastructure` 更佳）**
- [ ] **Step 2: 模块 test/compile 通过**
- [ ] **Step 3: main 源码无 JdbcTemplate**
- [ ] **Step 4: Commit（仅用户授权时）**

---

### Task 8: model + agent + mcp + skill

**Files:**
- `model/domain/ModelEntities.java`, `DecisionModelService.java`, `ModelRouterFreezeTest.java`
- `agent/domain/AgentStore.java`, `MarketplaceService`, `AgentShareService`, `SandboxPolicyService`
- `mcp/domain/McpStore.java`
- `skill/application/SkillService.java`

- [ ] **Step 1: Store/Entities 迁 infrastructure + Mapper；不可变版本表用 `ImmutableSnapshotDO`**
- [ ] **Step 2: 修 `ModelRouterFreezeTest` 去掉业务路径 JdbcTemplate
- [ ] **Step 3: 四模块 main 无 JdbcTemplate；相关测试 PASS
- [ ] **Step 4: Commit（仅用户授权时）**

---

### Task 9: connector

**Files:**
- `FeishuDocSyncService.java`
- `FeishuImChannelAdapter.java`, `WeComImChannelAdapter.java`, `DingTalkImChannelAdapter.java`（outbox → `AppendOnlyDO`）

- [ ] **Step 1: 建 `ImOutboxDO`/`FeishuDocMapDO` 等 + Repository；Adapter 改依赖 Repository**
- [ ] **Step 2: compile/test；main 无 JdbcTemplate
- [ ] **Step 3: Commit（仅用户授权时）**

---

### Task 10: ArchUnit 硬门禁（全量必须绿）

**Files:**
- Create: `tioka-server/src/test/java/com/tioka/server/arch/PersistenceArchTest.java`
- Modify: `ModuleDependencyArchTest.java`（可选：抽出共享 `JavaClasses` 导入）

```java
package com.tioka.server.arch;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tioka.framework.persistence.AppendOnlyDO;
import com.tioka.framework.persistence.BaseDO;
import com.tioka.framework.persistence.ImmutableSnapshotDO;
import com.tioka.framework.persistence.WorkspaceAwareDO;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class PersistenceArchTest {
    static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.tioka");
    }

    @Test
    void noSpringJdbcInProductionCode() {
        noClasses()
                .that().resideInAPackage("com.tioka..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.jdbc..")
                .because("禁止 JdbcTemplate 等直接 JDBC；使用 MyBatis-Plus")
                .check(classes);
    }

    @Test
    void applicationMustNotDependOnMappers() {
        noClasses()
                .that().resideInAPackage("com.tioka..application..")
                .should().dependOnClassesThat().resideInAPackage("com.tioka..infrastructure.mapper..")
                .because("application 只能依赖 Repository")
                .check(classes);
    }

    @Test
    void tableNameEntitiesMustExtendAgreedBase() {
        classes()
                .that().areAnnotatedWith(TableName.class)
                .should().beAssignableTo(BaseDO.class)
                .orShould().beAssignableTo(WorkspaceAwareDO.class)
                .orShould().beAssignableTo(ImmutableSnapshotDO.class)
                .orShould().beAssignableTo(AppendOnlyDO.class)
                .because("所有 @TableName 实体必须继承约定基类")
                .check(classes);
    }
}
```

注意：若 ArchUnit 的 `orShould` 链式在 1.3 不可用，改为自定义 `ArchCondition`：检查 `JavaClass` 是否 assignable 到四基类之一。

- [x] **Step 1: 先全仓确认** `rg "org.springframework.jdbc" --glob '*.java' -g '!**/test/**'` 为空（或仅框架测试豁免——生产 main 必须空）
- [x] **Step 2: 添加 `PersistenceArchTest`**
- [x] **Step 3: `mvn -pl tioka-server -am test`**  
Expected: 全部 PASS（含 ArchUnit）
- [ ] **Step 4: Commit（仅用户授权时）**

---

### Task 11: 文档回写

**Files:**
- Modify: `docs/architecture/enterprise/42-DA-数据架构.md` — 统一约定改为 `create_at/update_at/create_by/update_by/deleted`；注明 **暂无 tenant_id 列**（另 ADR）
- Create: `docs/architecture/adr/ADR-009-persistence-mybatis-plus-base-do.md` — 记录 JDBC PoC → MP + 字段决策

- [x] **Step 1: 写 ADR-009 与 DA 段落修订**
- [ ] **Step 2: Commit（仅用户授权时）**

---

### Task 12: 最终验收

- [x] **Step 1:** `rg JdbcTemplate -g '*.java'` → 仅允许出现在本 plan/spec 文档，**代码为 0**（含 test 业务装配；EmbeddedDatabaseBuilder 可保留但不传入业务）
- [x] **Step 2:** `mvn -pl tioka-server -am test` → BUILD SUCCESS
- [x] **Step 3:** 确认 `.cursor/rules` 两文件在位
- [x] **Step 4:** 向用户汇报：剩余风险（H2 方言、不可变表分类是否需微调）

---

## Spec coverage 自检

| Spec 项 | Task |
| --- | --- |
| BaseDO 字段锁定 / 无 tenantId | 1 |
| WorkspaceAware / Immutable / AppendOnly | 1, 5–9 |
| MetaObjectHandler + MapperScan | 1, 4 |
| Cursor Rules | 2 |
| Schema 审计列 | 3 |
| 全 biz MP 依赖 + 取消 exclude | 4 |
| 清除 JdbcTemplate 全模块 | 5–9 |
| ArchUnit 全量失败策略（完成后必绿） | 10 |
| DA/ADR 回写 | 11 |
| 成功标准验收 | 12 |

## 执行建议

本计划跨多模块，建议 **Subagent-Driven**：每 Task 一个子代理，Task 5–9 可按模块串行（勿并行改同一 schema）。Task 10 必须在 5–9 全部完成后再跑。

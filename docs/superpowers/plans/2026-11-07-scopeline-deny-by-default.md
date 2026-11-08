# ScopeLine deny-by-default Implementation Plan

> **For agentic workers:** 按任务顺序落地；每任务自测。

**Goal:** 将 ScopeLine 从 allowlist 反转为 exempt-list deny-by-default。

**Architecture:** `PersistenceProperties.workspaceScopeExemptTables` + `ScopedTables.isExempt`；Handler 仅 SYSTEM(无 ws) 与 exempt 旁路；ArchUnit 双向守门。

**Tech Stack:** MyBatis-Plus TenantLine、Spring Boot ConfigurationProperties、ArchUnit、JUnit5

## Global Constraints

- 禁止 `tenantId`；TenantContext 不重命名
- `sys_member` / `sys_api_key` 必须 exempt
- 不改 system plan；不做 V132

---

### Task 1: Properties + ScopedTables + Handler

**Files:**
- Modify: `tioka-framework/.../PersistenceProperties.java`
- Modify: `tioka-framework/.../ScopedTables.java`
- Modify: `tioka-framework/.../ScopeLineHandler.java`
- Modify: `tioka-server/src/main/resources/application.yml`
- Modify: `ScopedTablesTest.java` / `ScopeLineHandlerTest.java`

### Task 2: ArchUnit

**Files:**
- Modify: `tioka-server/src/test/java/com/tioka/server/arch/PersistenceArchTest.java`

### Task 3: Verify

- `mvn -pl tioka-framework,tioka-server -am -Dtest=ScopeLineHandlerTest,ScopedTablesTest,PersistenceArchTest,ModuleDependencyArchTest test`

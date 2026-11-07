# M0/M1 路径 A 收口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在开 M2 前关闭 M0-P2（UTC/DO）、OBS-01/02（GOV-02 收口）、夜间与演示走查，使 M1 可按 `M1-00` §12 诚实宣告完成。

**Architecture:** 不扩 M2 能力。W0 固定 JVM/JDBC UTC 并将 `RuntimeTurnDO`/`RuntimeApprovalDO`（及同波次触及的 Mutable 表）对齐 `MutableRecordDO` + `LocalDateTime`；W1 在审计写入路径自动补齐 `trace_id`/`request_id`/可信 IP，并修正失败语义与停用物理删；W2 按 ADR-072 四类豁免清零覆盖白名单；W3/W4 门禁与文档宣告。

**Tech Stack:** Java 17、Spring Boot、MyBatis-Plus、Flyway、JUnit5、Testcontainers、GitHub Actions

**Spec:** [2026-11-07-m0-m1-closure-path-a-design.md](../specs/2026-11-07-m0-m1-closure-path-a-design.md)

## Global Constraints

- 无 `tenantId` / `tenant_id` 列或领域字段
- `*-biz` 禁止依赖其它 `*-biz`；只依赖 `*-api` + framework + starter
- ORM 仅 MyBatis-Plus；分层 `application` → `infrastructure.Repository` → `mapper`
- DO 时间字段仅 `LocalDateTime`（语义 UTC）；禁止 DO 使用 `OffsetDateTime`
- G0/G1 全程保持绿；本计划不含 CON-04、ENG-07、OBS-03+、depth-p0、GOV-09 全量
- **提交：** 仅当用户在会话中明确授权 `commit` 时执行各 Task 的 commit 步骤；否则改完停在工作区

---

### Task 1: UTC 配置 + `TimeZoneIT`

**Files:**
- Modify: `tioka-server/src/main/resources/application.yml`（datasource url）
- Modify: `tioka-server/src/main/resources/application-local.yml`（同 url）
- Modify: `.github/workflows/ci.yml`（backend + gate 的 Java 步骤加 `JAVA_TOOL_OPTIONS`）
- Modify: `.github/workflows/nightly-real-model.yml`（同上）
- Create: `tioka-server/src/test/java/com/tioka/server/TimeZoneIT.java`
- Create: `deploy/Dockerfile`（若仓库仍无镜像文件；ENTRYPOINT 带 `-Duser.timezone=UTC`）
- Modify: `docs/architecture/ops/oidc-keycloak.md` 或新建 `docs/architecture/ops/timezone-utc.md` 一行说明本地启动也需 UTC

**Interfaces:**
- Produces: JDBC URL 含 `options=-c%20TimeZone=UTC`；进程 `-Duser.timezone=UTC`；`TimeZoneIT` 断言

- [ ] **Step 1: 写失败测试 `TimeZoneIT`**

```java
package com.tioka.server;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Tag("it")
class TimeZoneIT {
    @Autowired JdbcTemplate jdbc;

    @Test
    void jvmAndSessionAreUtc() {
        assertEquals("UTC", TimeZone.getDefault().getID());
        String zone = jdbc.queryForObject("SHOW timezone", String.class);
        assertEquals("UTC", zone);
    }
}
```

- [ ] **Step 2: 跑测试确认当前失败**

Run: `mvn -pl tioka-server -am -Dtest=TimeZoneIT -DfailIfNoTests=false test`

Expected: FAIL（JVM 或 `SHOW timezone` 非 UTC）

- [ ] **Step 3: 改配置**

`application.yml` / `application-local.yml` 的 url 改为：

```yaml
url: jdbc:postgresql://${TIOKA_DB_HOST:localhost}:${TIOKA_DB_PORT:5432}/${TIOKA_DB_NAME:tioka}?options=-c%20TimeZone%3DUTC
```

CI / nightly 在 `setup-java` 后或 `run` 环境增加：

```yaml
env:
  JAVA_TOOL_OPTIONS: -Duser.timezone=UTC
```

`deploy/Dockerfile` 最小示例：

```dockerfile
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY tioka-server/target/tioka-server-*.jar /app/app.jar
ENV JAVA_TOOL_OPTIONS=-Duser.timezone=UTC
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

Surefire 若需本地 IT 也固定时区，在根 `pom.xml` 或 `tioka-server/pom.xml` 的 surefire `argLine` 追加 `-Duser.timezone=UTC`（与现有 argLine 合并，勿覆盖 jacoco）。

- [ ] **Step 4: 再跑 `TimeZoneIT`**

Expected: PASS

- [ ] **Step 5: Commit（仅用户授权时）**

```bash
git add tioka-server/src/main/resources/application.yml \
  tioka-server/src/main/resources/application-local.yml \
  tioka-server/src/test/java/com/tioka/server/TimeZoneIT.java \
  .github/workflows/ci.yml .github/workflows/nightly-real-model.yml \
  deploy/Dockerfile docs/architecture/ops/timezone-utc.md
git commit -m "$(cat <<'EOF'
fix(m0-p2): pin JVM and JDBC session to UTC

EOF
)"
```

---

### Task 2: `RuntimeTurnDO` / `RuntimeApprovalDO` → Mutable + `LocalDateTime`

**Files:**
- Modify: `tioka-modules/tioka-module-runtime/tioka-module-runtime-biz/src/main/java/com/tioka/runtime/kernel/infrastructure/persistence/RuntimeTurnDO.java`
- Modify: `tioka-modules/tioka-module-runtime/tioka-module-runtime-biz/src/main/java/com/tioka/runtime/kernel/infrastructure/persistence/RuntimeApprovalDO.java`
- Modify: 所有读写上述 DO 时间字段的 Repository / Mapper XML / 手写 SQL 赋值处（先 `rg OffsetDateTime|setLeaseUntil|getLeaseUntil|RuntimeTurnDO` 于 `tioka-module-runtime`）
- Modify: 相关 IT（至少 `TurnRunAsInvalidIT` 等仍传 `OffsetDateTime` 的测试）
- Verify: `tioka-server/src/test/java/com/tioka/server/arch/PersistenceArchTest.java`（若有 `doTimeFieldsAreLocalDateTime`，本 Task 后 Runtime 两 DO 不得再进冻结库）

**Interfaces:**
- Consumes: `MutableRecordDO`（已有 `createAt`/`updateAt`/`createBy`/`updateBy` 为 `LocalDateTime`）
- Produces: DO 时间 getter 返回 `LocalDateTime`；Repository 对外领域仍用 `Instant`（`ldt.toInstant(ZoneOffset.UTC)`）

- [ ] **Step 1: 列出触点**

Run: `rg -n 'OffsetDateTime|RuntimeTurnDO|RuntimeApprovalDO' tioka-modules/tioka-module-runtime tioka-server/src/test --glob '**/*.{java,xml}'`

- [ ] **Step 2: 改 DO**

`RuntimeTurnDO`：

```java
public class RuntimeTurnDO extends MutableRecordDO {
    // 去掉自有 createAt/updateAt 字段与 accessor（改用基类）
    private LocalDateTime leaseUntil;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    // …其余字段不变；getter/setter 类型改为 LocalDateTime
}
```

`RuntimeApprovalDO` 同样 `extends MutableRecordDO`，`decidedAt`/`expiresAt`/`createAt`/`updateAt` 处理同上。

- [ ] **Step 3: 改 Repository 边界转换**

写入：`LocalDateTime.ofInstant(instant, ZoneOffset.UTC)`  
读出：`ldt == null ? null : ldt.toInstant(ZoneOffset.UTC)`

- [ ] **Step 4: 跑 Runtime 相关 IT + PersistenceArch**

Run: `mvn -pl tioka-module-runtime-biz,tioka-server -am -Dtest=TurnRunAsInvalidIT,DispatchIT,LeaseReaperIT,PersistenceArchTest -DfailIfNoTests=false test`

Expected: PASS（按实际测试类名微调）

- [ ] **Step 5: Commit（仅用户授权时）**

```bash
git commit -m "$(cat <<'EOF'
fix(m0-p2): align runtime turn/approval DO with MutableRecordDO UTC LocalDateTime

EOF
)"
```

---

### Task 3: OBS-01 — `request_id` 列 + `AuditRequestContext`

**Files:**
- Create: `docs/contracts/db/V127__m1_audit_request_id.sql`
- Modify: `AuditEvent.java`（api record + Builder 增加 `requestId`）
- Modify: `AuditEventMapper.java` / `AuditEventRepository.java` / `AuditService.java` / `AuditController` CSV 头
- Create: `tioka-modules/tioka-module-system/tioka-module-system-biz/src/main/java/com/tioka/system/application/audit/AuditRequestContext.java`
- Create: `…/ClientIpResolver.java`
- Modify: `AuditedAspect.java`、认证/拒绝/出网等手写 `AuditEvent.builder()` 处
- Create: `tioka-server/src/test/java/com/tioka/server/gov/AuditContextIT.java`

**Interfaces:**
- Produces:
  - `AuditRequestContext.currentTraceId(): String`
  - `AuditRequestContext.currentRequestId(): String`
  - `ClientIpResolver.resolve(HttpServletRequest): String`
- DB: `audit_event.request_id VARCHAR(64)`

- [ ] **Step 1: 迁移**

```sql
-- V127__m1_audit_request_id.sql
-- M1 收口 OBS-01：审计关联请求号（ADR-072）
ALTER TABLE audit_event ADD COLUMN IF NOT EXISTS request_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_audit_request_id ON audit_event (request_id);
```

- [ ] **Step 2: 写失败 IT（上下文补齐）**

```java
@Test
void auditedWriteFillsTraceAndRequestId() throws Exception {
    // 调一个已有 @Audited 写接口（如创建 api key / 发布 agent 的最小路径）
    // 查库最近一条 audit_event
    assertThat(row.get("trace_id")).isNotBlank();
    assertThat(row.get("request_id")).isNotBlank();
}
```

- [ ] **Step 3: 实现 `AuditRequestContext`（MDC + 缺则生成）**

```java
public final class AuditRequestContext {
    public static final String MDC_TRACE = "traceId";
    public static final String MDC_REQUEST = "requestId";

    public static String currentTraceId() {
        String v = MDC.get(MDC_TRACE);
        if (v == null || v.isBlank()) {
            v = Ids.newId(); // 或 ULID；与工程 Ids 一致
            MDC.put(MDC_TRACE, v);
        }
        return v;
    }

    public static String currentRequestId() {
        String v = MDC.get(MDC_REQUEST);
        if (v == null || v.isBlank()) {
            v = Ids.newId();
            MDC.put(MDC_REQUEST, v);
        }
        return v;
    }
}
```

在 HTTP 入口 Filter（若已有鉴权 Filter）于请求开始 `MDC.put` 入站 `X-Request-Id`（若合法）否则生成；响应结束 `MDC.clear()`。

`AuditedAspect.build(...)` 增加：

```java
.traceId(AuditRequestContext.currentTraceId())
.requestId(AuditRequestContext.currentRequestId())
```

手写 builder（`AuthService`、`RestAccessDenied`、`DefaultToolGateway` 等）同样补齐。

- [ ] **Step 4: `ClientIpResolver` + 改 `AuthController.clientIp`**

```java
public String resolve(HttpServletRequest req) {
    // 仅当 remoteAddr 属于 tioka.audit.trusted-proxies 时才读 X-Forwarded-For 的左侧第 N 跳
    // 否则 return req.getRemoteAddr();
}
```

配置：

```yaml
tioka:
  audit:
    trusted-proxies: []   # CIDR 或精确 IP；空 = 永不信任 XFF
```

IT：伪造 `X-Forwarded-For: 1.2.3.4` 且 remote 非信任代理时，审计 `client_ip` ≠ `1.2.3.4`。

- [ ] **Step 5: 跑 `AuditContextIT` + 相关**

Expected: PASS

- [ ] **Step 6: Commit（仅用户授权时）**

```bash
git commit -m "$(cat <<'EOF'
feat(OBS-01): fill audit trace_id/request_id and trusted client IP

EOF
)"
```

---

### Task 4: OBS-01 — 失败语义 + 工具审计降级 + 停用物理删

**Files:**
- Modify: `AuditedAspect.java`（`catch (Throwable)` / 非 BizException 记 ERROR 再抛）
- Modify: `DefaultToolGateway.java`（`auditInvoke` 失败写降级文件）
- Create: `…/AuditDegradedSink.java`（追加写 `logs/audit-degraded.jsonl` 或 `tioka.audit.degraded-path`）
- Modify: `AuditForwarder.purgeExpired` → 默认 no-op
- Modify: `application.yml` 增加 `tioka.audit.physical-purge-enabled: false`
- Test: `AuditFailureSemanticsIT`、`ToolAuditDegradeIT`、`AuditPurgeDisabledIT`

- [ ] **Step 1: 红测 — 切面捕获 RuntimeException**

断言：被 `@Audited` 方法抛 `IllegalStateException` 后，`audit_event` 有 `outcome=ERROR`（独立事务）。

- [ ] **Step 2: 改 `AuditedAspect`**

```java
} catch (BizException ex) {
    ...
    throw ex;
} catch (RuntimeException ex) {
    audit.recordIndependent(build(audited, pjp, null, AuditOutcome.ERROR, ex.getMessage()));
    throw ex;
} catch (Error err) {
    // 尽量记录；失败则降级文件
    throw err;
}
```

- [ ] **Step 3: 工具审计降级**

`auditInvoke` 的 `catch` 改为调用 `AuditDegradedSink.append(eventJson)`，日志级别 ERROR，带文件路径；禁止只 `log.warn` 后返回。

- [ ] **Step 4: `purgeExpired`**

```java
@Scheduled(...)
public void purgeExpired() {
    if (!props.isPhysicalPurgeEnabled()) {
        log.debug("audit physical purge disabled (OBS-01 / GOV-09 pending)");
        return;
    }
    // 保留旧逻辑仅供显式打开（默认 false）；计划上生产不得打开直至 GOV-09
}
```

- [ ] **Step 5: 测试全绿后 Commit（仅用户授权时）**

```bash
git commit -m "$(cat <<'EOF'
fix(OBS-01): audit failure semantics, degrade sink, disable physical purge

EOF
)"
```

---

### Task 5: OBS-02 — 白名单税制（先红）

**Files:**
- Modify: `tioka-server/src/test/java/com/tioka/server/gov/AuditCoverageIT.java`
- Create: `WhitelistEntry` record 或 `enum WhitelistKind { PUBLIC_AUTH, READ_ONLY_POST, NOT_IMPLEMENTED, INBOUND_LEDGER }`

**Interfaces:**
- Produces: 覆盖测试只接受四类；「待各域补」条目导致测试失败

- [ ] **Step 1: 重构白名单为带类别结构**

```java
private static final List<WhitelistEntry> WHITELIST = List.of(
    new WhitelistEntry("/api/system/auth/login", WhitelistKind.PUBLIC_AUTH, "AuthService 写 auth.login"),
    new WhitelistEntry("/api/knowledge/bases/{id}/search", WhitelistKind.READ_ONLY_POST, "检索只读"),
    new WhitelistEntry("/api/ops/interpreter", WhitelistKind.NOT_IMPLEMENTED, "501"),
    new WhitelistEntry("/api/connector/im/feishu/text", WhitelistKind.INBOUND_LEDGER, "入站待 OBS-04")
    // 禁止再出现「待各域补」注释块中的路径
);
```

把原「待各域补」约 66 条**暂时移出**白名单（测试会失败，列出未审计映射）——这是刻意红灯。

- [ ] **Step 2: 跑 `AuditCoverageIT`，保存失败的 pattern 列表到 PR 描述草稿**

Run: `mvn -pl tioka-server -am -Dtest=AuditCoverageIT test`

Expected: FAIL，输出未覆盖写接口清单

- [ ] **Step 3: Commit 税制变更可与 Task 6 同 PR；若拆分则仅在用户授权时提交「test: require typed audit whitelist」**

---

### Task 6: OBS-02 — 按域挂 `@Audited`（清零）

**Files（按失败清单逐项，至少覆盖这些前缀）：**
- `tioka-module-agent-biz` — Agent 草稿/更新/校验等未挂审计的写方法
- `tioka-module-model-biz` — providers/bindings/routing-plans/default-plans
- `tioka-module-mcp-biz` — servers/tools/grants/trial
- `tioka-module-flow-biz` — drafts/publish/run
- `tioka-module-knowledge-biz` — bases/documents/datasources/eval
- `tioka-module-chat-biz` — conversations/messages/cards
- `tioka-module-runtime-biz` 或 server 门面 — threads/turns/interrupt/steer
- `tioka-module-orchestration-biz` — events/rules/runs/attentions
- `tioka-module-system-biz` — workspaces/users/groups/roles/quotas/acl
- `tioka-module-skill-biz` — import/publish
- uploads / ops 门面：能实现的挂审计；真正 501 留 `NOT_IMPLEMENTED`

**模式（每个写方法）：**

```java
@Audited(action = "model.binding.upsert", resourceType = "model_binding",
         resourceIdArg = "#id", include = {"name"})
public void upsert(...) { ... }
```

动作名：小写点分；与现有 `agent.publish` 风格一致。

- [ ] **Step 1: 按 `AuditCoverageIT` 失败列表分域补注解（或服务层手写 `audit.record`）**
- [ ] **Step 2: 每补完一个域，跑一次 `AuditCoverageIT`，确认该前缀消失**
- [ ] **Step 3: 全绿后确认 WHITELIST 仅四类且每条有原因字符串**
- [ ] **Step 4: `mvn verify -Pgate`（G0+G1 仍绿）**
- [ ] **Step 5: Commit（仅用户授权时）**

```bash
git commit -m "$(cat <<'EOF'
feat(OBS-02): clear audit coverage whitelist to ADR-072 four kinds

EOF
)"
```

---

### Task 7: W3 夜间门禁 + §12.5 演示走查

**Files:**
- Create: `docs/architecture/ops/m1-closure-walkthrough.md`（勾选清单）
- Modify: `docs/architecture/ops/oidc-keycloak.md`（若需补真实模型机密说明）
- Ops: 在 GitHub repo Settings → Secrets 配置 `TIOKA_REAL_MODEL_BASE_URL` / `API_KEY` / `MODEL`

- [ ] **Step 1: 配置机密后手动触发**

Run: `gh workflow run nightly-real-model.yml && gh run list --workflow=nightly-real-model.yml --limit 3`

Expected: 至少 1 次 `success`（或文档记录「机密未就绪、关闭条件降级为 workflow 可跑 + skip 日志」——仅当负责人书面同意）

- [ ] **Step 2: 填写走查 md**

```markdown
# M1 收口演示走查

- [ ] 企业账号登录（OIDC）
- [ ] 选已发布 Agent
- [ ] 多步 MCP 工具调用完成
- [ ] 另一标签页停止 Turn
- [ ] 管理后台可见用量与审计（含非空 trace_id/request_id）

日期：
执行人：
```

- [ ] **Step 3: Commit 文档（仅用户授权时）**

---

### Task 8: W4 宣告

**Files:**
- Modify: `docs/superpowers/specs/2026-11-07-m0-m1-closure-path-a-design.md` 状态 → `已完成`
- Modify: `docs/architecture/construction/M1-00-M1实施计划.md` 文首或第 12 节追加「收口完成记录」短段落（日期、PR 链接、遗留 CON-04/ENG-07）
- Optional: `docs/architecture/implementation/34-M0至M4推进规划与工作项.md` OBS-01/02 状态注记

- [ ] **Step 1: 写收口记录（含 PR 号与夜间 run 链接）**
- [ ] **Step 2: 确认遗留表仅 CON-04/ENG-07 → M2**
- [ ] **Step 3: Commit（仅用户授权时）**

---

## Spec 覆盖自检

| Spec 项 | Task |
| --- | --- |
| W0 UTC + TimeZoneIT | Task 1 |
| W0 RuntimeTurnDO Mutable/LocalDateTime | Task 2 |
| W1 trace/request/IP | Task 3 |
| W1 失败语义/降级/停物理删 | Task 4 |
| W2 四类白名单清零 | Task 5–6 |
| W3 夜间 + 演示 | Task 7 |
| W4 宣告 | Task 8 |
| 非目标 M2/depth-p0/CON-04 | Global Constraints |

## 执行切分建议（PR）

1. Tasks 1–2 → `fix/m0-p2-timezone-do`  
2. Tasks 3–4 → `feat/OBS-01-audit-context`  
3. Tasks 5–6 → `feat/OBS-02-audit-coverage`  
4. Tasks 7–8 → docs / ops（可并入 3）

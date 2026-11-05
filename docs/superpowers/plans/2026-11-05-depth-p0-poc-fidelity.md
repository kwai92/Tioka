# Depth P0 — 商业 PoC 保真主路径 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把黄金路径从「默认 mock 的小学生切片」抬到委员会定稿的 P0：关玩具默认、Grant 快照闭合、发布不可变、Session 钉版本、cancel/幂等/同轮冻结、薄事务校验、Chat 最小走查、文档话术降级——仍不等于产品 Exit 人签。

**Architecture:** 不推倒模块树。沿现有 `application → Repository → Mapper`；先修执行链不变量（AgentVersion 快照驱动 ToolGateway/MCP），再修 Runtime 行为测与 fidelity profile 门禁。完整 ToolGateway ArchUnit / Workdisk / Skill G-SK 属 **M-Gap**，本计划不偷渡。

**Tech Stack:** Java 17、Spring Boot、MyBatis-Plus、JUnit5、可选 WireMock、Vue3+TS（Chat 走查）

**Spec:** [2026-11-05-implementation-depth-gap-inventory.md](../specs/2026-11-05-implementation-depth-gap-inventory.md)

## Global Constraints

- 未过 R2 产品 Exit：**禁止**开 R6；P1/P2 主人力 ≤20%
- **禁止**把 X-04 Store / WorkspaceAware / TenantContext 重命名升入本计划
- **禁止**本计划做全量 `rt_turn` 事件溯源（RT-01 全量移出）
- ORM 仅 MyBatis-Plus；无 `tenantId` 列；`*-biz` 不依赖其他 `*-biz`
- 门禁：SKIP=0 **不得**冒充 fidelity PASS；slice 绿不得对外称 Exit
- 每个 Task 结束必须有可运行的失败→通过测试或脚本断言

---

## File map（预期触达）

| 区域 | 文件 |
| --- | --- |
| 文档 | `docs/architecture/implementation/33-…md`（已复位）；inventory（已定稿） |
| Agent 不可变 | `AgentRepository.java`、`AgentStore.java`、新建 `AgentVersionImmutabilityTest` |
| Session 钉版本 | `RuntimeSessionService.java` 创建会话处；Agent API 读 version |
| Grant 闭合 | `McpToolProvider.java`、`McpAdminService.java`、`DefaultToolGateway.java` |
| Runtime 行为 | `RuntimeSessionService.java`（`getTurn`/cancel/路由冻结） |
| 配置/门禁 | `application.yml`、`application-fidelity.yml`（新建）、`scripts/accept-fidelity.sh`、新建测试 |
| 事务/校验 | Agent 发布 Service；相关 Controller DTO |
| Chat | `tioka-ui/src/views/Chat.vue` + 走查清单 md |

---

### Task 0: 文档闸门确认（DOC-33-17 / X-07b）

**Files:**
- Verify: `docs/architecture/implementation/33-差距收口与后续推进计划.md` §17
- Verify: `docs/superpowers/specs/2026-11-05-implementation-depth-gap-inventory.md`
- Create: `docs/architecture/implementation/depth-p0-checklist.md`（对外禁用词 + Chat 走查 5 项）

- [ ] **Step 1: 确认 §17 已无「工程就绪/开 R6」**

Run: `rg -n '工程就绪|开 R6' docs/architecture/implementation/33-差距收口与后续推进计划.md`

Expected: §17 区域无「工程就绪」作为完成态；无「开 R6」为下一动作。

- [ ] **Step 2: 写入 depth-p0-checklist.md**

```markdown
# Depth P0 验收清单

## 禁用对外词
工程就绪 / 真 PoC Exit / 可卖 Exit（除非 w1-poc-gate-signoff 人签）

## Chat 走查（CH-01）五勾
- [ ] 可创建并切换 ≥2 会话
- [ ] 流式气泡使用 PartRenderer（非纯 `<pre>`）
- [ ] 故意错误（空消息/取消后发送）有可见错误或冲突提示
- [ ] 取消后界面不再持续追加 assistant 增量
- [ ] 侧栏不出现「env dump 当产品」作为主信息架构

## 门禁
- [ ] fidelity 测试无 key 时失败或明确 FAIL（非 exit 0 SKIP）
- [ ] Grant 越权单测绿
- [ ] 已发布 Version update 拒改单测绿
```

- [ ] **Step 3: Commit（若用户授权提交）**

```bash
git add docs/architecture/implementation/depth-p0-checklist.md
git commit -m "$(cat <<'EOF'
docs: add depth P0 acceptance checklist after committee review

EOF
)"
```

---

### Task 1: AG-01 已发布 Version 不可变

**Files:**
- Modify: `tioka-modules/tioka-module-agent/tioka-module-agent-biz/src/main/java/com/tioka/agent/infrastructure/AgentRepository.java`
- Modify: `tioka-modules/tioka-module-agent/tioka-module-agent-biz/src/main/java/com/tioka/agent/domain/AgentStore.java`（若 status 在 Store）
- Test: `tioka-modules/tioka-module-agent/tioka-module-agent-biz/src/test/java/com/tioka/agent/infrastructure/AgentVersionImmutabilityTest.java`

**Interfaces:**
- Consumes: `AgentVersionDO`（含发布状态字段，以现有 `status`/`published` 为准——实现前先读 DO）
- Produces: `upsertVersion` 对已发布行抛 `BizException(ErrorCode.CONFLICT)`；仅允许 insert 新版本

- [ ] **Step 1: 写失败测试**

```java
@Test
void publishedVersion_rejectsUpdateById() {
    AgentVersionDO published = /* insert with published status */;
    published.setSystemPrompt("mutated");
    assertThrows(BizException.class, () -> repository.upsertVersion(published));
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -pl tioka-modules/tioka-module-agent/tioka-module-agent-biz -Dtest=AgentVersionImmutabilityTest test`

Expected: FAIL（仍允许 update）

- [ ] **Step 3: 最小实现**

在 `AgentRepository.upsertVersion`：

```java
public void upsertVersion(AgentVersionDO row) {
    AgentVersionDO existing = versions.selectById(row.getId());
    if (existing == null) {
        versions.insert(row);
        return;
    }
    if (isPublished(existing)) {
        throw new BizException(ErrorCode.CONFLICT, "已发布版本不可变: " + row.getId());
    }
    versions.updateById(row);
}
```

`isPublished` 与现有发布状态枚举对齐（读 `AgentService` 发布逻辑，勿发明第二套状态）。

- [ ] **Step 4: 测试通过**

Run: 同上 `mvn … -Dtest=AgentVersionImmutabilityTest test` → PASS

- [ ] **Step 5: Commit（授权后）**

```bash
git commit -m "$(cat <<'EOF'
fix: reject updates to published agent versions

EOF
)"
```

---

### Task 2: AG-04 Session 钉死 AgentVersion

**Files:**
- Modify: `RuntimeSessionService` 创建 session 路径（读 `create` 方法）
- Modify: 任何「跟随 Agent 最新 version」的解析
- Test: `…/RuntimeSessionPinVersionTest.java`

**Interfaces:**
- Produces: `SessionState.agentVersionId` 在 create 时写入且 append/tool 全程只读该字段；中途发布新 version 不影响已有 session

- [ ] **Step 1: 失败测试** — 创建 session 钉 `v1`；发布 `v2`；append 时 `toolGateway`/`agentVersionId` 仍为 `v1`

- [ ] **Step 2: 实现** — 禁止 append 内 `resolveLatestVersion(agentId)`；若存在则删除

- [ ] **Step 3: 测试 PASS + commit**

---

### Task 3: RT-07 + AG-02 Grant 快照闭合（含旁路）

**Files:**
- Modify: `McpToolProvider.java`
- Modify: `McpAdminService.java`（`toolsForAgent` / `callToolByName`）
- Modify: `DefaultToolGateway.java`（删除 `listAll(null)` / `listAll("")` 旁路）
- Modify: AgentVersion 读取 `mcpGrantIds` 供工具解析（经 api，勿 biz→biz）
- Test: `McpGrantSnapshotTest`、扩展 `DefaultToolGatewayTest`

**现状缺陷（必须全修）：**
1. `listTools(agentVersionId)` 却按 `Grant.agentId` 过滤  
2. `mcpGrantIds` 未消费  
3. `DefaultToolGateway` null/"" 旁路  
4. `callToolByName` 扫全部 Grant + `mcp_echo` 兜底  

- [ ] **Step 1: 失败测试**

```java
@Test
void toolsOutsideVersionGrant_forbidden() {
    // version.mcpGrantIds = [gr_only_a]; tool B not in allowlist
    assertThrows(BizException.class, () -> gateway.call(ctx, "tool_b", Map.of()));
}

@Test
void changingGrant_doesNotAffectPublishedVersionTools() { /* … */ }

@Test
void gateway_rejectsNullContextBypass() {
    // listAll(null) 不得再放行未授权工具
}
```

- [ ] **Step 2: 实现要点**

```java
// McpToolProvider.listTools(agentVersionId):
// 1) 经 Agent api 取 version.mcpGrantIds
// 2) 只加载这些 grant 的 allowlist∩tools
// 3) 禁止用 agentVersionId 与 grant.agentId 直接 equals

// DefaultToolGateway.call:
boolean allowed = listTools(ctx).stream().anyMatch(t -> t.name().equals(toolName));
// 删除 || registry.listAll(null) || registry.listAll("")

// callToolByName: 增加 agentVersionId（或废弃无作用域重载）；mock=false 时禁止 mcp_echo fallback
```

- [ ] **Step 3: `mvn -pl tioka-server -am -Dtest=DefaultToolGatewayTest,McpGrantSnapshotTest test` → PASS**

- [ ] **Step 4: Commit**

```bash
git commit -m "$(cat <<'EOF'
fix: bind MCP tools to agent version grant snapshots

EOF
)"
```

---

### Task 4: MD-01 + RT-05 + MCP-01 fidelity profile 与门禁

**Files:**
- Create: `tioka-server/src/main/resources/application-fidelity.yml`
- Modify: `application.yml` 注释标明 local 默认 mock 仅为 slice  
- Modify: `scripts/accept-fidelity.sh`、`scripts/smoke-llm-live.sh`、`scripts/smoke-mock-off.sh`
- Create: `tioka-server/src/test/java/com/tioka/server/fidelity/FidelityMockOffIT.java`（WireMock 或 fixture）
- Modify: `McpAdminService.callToolByName` — `tioka.mcp.mock=false` 时禁止 echo fallback

`application-fidelity.yml` 最小内容：

```yaml
tioka:
  model:
    mock: false
  mcp:
    mock: false
```

- [ ] **Step 1: 测试 — 无凭证时 fidelity 断言失败**

```java
@ActiveProfiles("fidelity")
class FidelityMockOffIT {
  @Test
  void missingKey_doesNotPretendSuccess() {
    // 期望 DEPENDENCY/配置错误，而不是 mock 回声 200
  }
}
```

- [ ] **Step 2: 脚本 — `smoke-llm-live.sh`**

当 `TIOKA_FIDELITY_REQUIRED=1` 且无 key：`exit 1`（不是 0）。  
默认 slice CI 可不设该变量。

- [ ] **Step 3: MCP mock=false 去掉 echo fallback；用 WireMock tools/list+call 测通**

- [ ] **Step 4: Commit**

```bash
git commit -m "$(cat <<'EOF'
test: add fidelity profile gates that fail closed without secrets

EOF
)"
```

---

### Task 5: RT-02 幂等 + RT-03 cancel + RT-10 同轮冻结

**Files:**
- Modify: `RuntimeSessionService.java`（`getTurn`、`streamAssistant`、`append` 内 `modelRouter.select`）
- Test: `RuntimeTurnBehaviorTest.java`

- [ ] **Step 1: 幂等测试**

```java
@Test
void sameClientMsgId_returnsSameTurnPartsOnly() {
    TurnView t1 = api.append(sid, new AppendRequest("c1", "hi"));
    TurnView t2 = api.append(sid, new AppendRequest("c1", "hi"));
    assertEquals(t1.turnId(), t2.turnId());
    // t2.parts 不得等于「会话全部历史 parts」除非该 turn 即全部；应等于 t1.parts
    assertEquals(t1.parts(), t2.parts());
}
```

修复 `getTurn`：按 `turnId` 过滤 parts（若仍 blob，至少在 SessionState 侧按 turn 索引 `Map<String,List<PartView>>` 最小补丁——**不要**本 Task 上全量 rt_turn 表）。

- [ ] **Step 2: cancel 测试**

```java
@Test
void cancel_stopsPersistingNewParts() {
    // 启动长 mock 流或可控 stub；cancel 后 parts 数量不再增加
}
```

实现：`streamAssistant` 写 part 前检查 `s.cancelled`；LLM 客户端若支持取消则传递（最小：停写+停后续 tool）。

- [ ] **Step 3: 同轮冻结**

```java
@Test
void sameTurn_toolContinuation_reusesModelSelection() {
    // 第一次 select 后，tool 续写不得再次 select 出不同 modelRef
}
```

实现：在 `append` 内 `ModelSelection` 赋一次局部 final，tool 循环复用；写入 session 的 `frozenModelRef`（内存字段即可）。

- [ ] **Step 4: 测试 PASS + commit**

```bash
git commit -m "$(cat <<'EOF'
fix: correct turn idempotency, cancel, and same-turn model freeze

EOF
)"
```

---

### Task 6: X-01 / X-03 薄切片

**Files:**
- Modify: Agent 发布 application service — `@Transactional` 仅包裹「写 agent + version + grant 绑定」  
- Modify: 演示用写接口 Controller — 引入 request DTO + `@Valid @NotBlank`  
- Test: 事务回滚测（强制中间失败）+ 校验 400 测

- [ ] **Step 1–4:** 失败测 → 实现 → PASS → commit  

```bash
git commit -m "$(cat <<'EOF'
feat: add transactional agent publish and request validation

EOF
)"
```

**禁止**全仓铺 `@Transactional` / 全 API 大重构。

---

### Task 7: CH-01 Chat 最小产品走查

**Files:**
- Modify: `tioka-ui/src/views/Chat.vue`（多会话切换、错误提示、取消态）
- Update: `docs/architecture/implementation/depth-p0-checklist.md` 勾选

- [ ] **Step 1: 实现多会话列表 + 切换（Pinia 或现有 store）**  
- [ ] **Step 2: 空消息 / cancel 后发送的用户可见错误**  
- [ ] **Step 3: `npm run build` 绿**  
- [ ] **Step 4: 按 checklist 五勾人工走查（执行代理勾选并写日期）**  
- [ ] **Step 5: Commit**

```bash
git commit -m "$(cat <<'EOF'
feat(ui): harden Chat for minimal product walkthrough

EOF
)"
```

---

### Task 8: P0 门禁汇总与 33 进度回写

**Files:**
- Modify: `scripts/accept-fidelity.sh` 调用新 IT（`TIOKA_FIDELITY_REQUIRED=1`）  
- Modify: `33-…md` §17 R2 下「深度 P0」行 → 完成项勾选  
- Modify: inventory §7 状态  

- [ ] **Step 1: 跑**

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
mvn -pl tioka-server -am test
cd tioka-ui && npm run build
TIOKA_FIDELITY_REQUIRED=1 ./scripts/accept-fidelity.sh
```

Expected: slice 测试绿；fidelity 在无 key 时 **非假绿**；有 WireMock/key 时行为测绿。

- [ ] **Step 2: 回写文档：P0 完成 ≠ R2-21 产品签字**  
- [ ] **Step 3: Commit**

---

## Self-review（计划）

| Spec P0 项 | Task |
| --- | --- |
| DOC-33-17 / X-07b | T0 |
| AG-01 | T1 |
| AG-04 + RT-07 + AG-02 | T2–T3 |
| MD-01 + RT-05 + MCP-01 | T4 |
| RT-02/03/10 | T5 |
| X-01/X-03 薄 | T6 |
| CH-01 | T7 |
| 五门禁 | T4+T5+T3+T1+T8 |
| RT-01 全量 / M-Gap / X-04 | **故意不在计划内** |

占位符扫描：无 TBD；WireMock 与发布状态字段名需实现时对照现码，已注明「先读 DO」。

---

## 执行说明

用户已授权「个人不参与评审」；**本计划待明确「开始执行 P0」后再动业务代码**（提交仍需单独授权）。  

推荐：`subagent-driven-development` 按 Task 0→8 顺序；每 Task 后跑对应用例。

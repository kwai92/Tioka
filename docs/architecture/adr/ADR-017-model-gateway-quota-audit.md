# ADR-017 — 模型调用收口、配额与审计

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：MH-01、MH-02、MH-04、MH-05、GOV-02、GOV-03、CON-01
- 对应设计：设计 C
- 阅读版：[Tioka 推进规划与详细设计](https://claude.ai/artifact/V3wtabWCVSwAC3ewNerFNi)
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

覆盖 MH-01、MH-02、MH-04、MH-05、GOV-02、GOV-03、CON-01，并顺带解决 KNW-01 和 FLW-02。核心决定是：**全平台只有三个治理卡点**——`ModelGateway`（所有模型调用）、`RuntimeApi.startTurn`（所有执行）、`ToolGateway`（所有工具调用）。配额、限流、用量、审计都只在这三处实现一次。

**ModelGateway**（取代对外暴露的 `LlmClientApi`）

```java
public interface ModelGateway {
    ChatResult chat(ModelCallContext ctx, ChatRequest req, Consumer<StreamDelta> onDelta);
    EmbeddingResult embed(ModelCallContext ctx, EmbeddingRequest req);
    RerankResult rerank(ModelCallContext ctx, RerankRequest req);
}

record ModelCallContext(Principal principal, String sessionId, String turnId,
                        String agentId, String routingPlanId,
                        String purpose) {}   // chat / flow / embedding / rerank / decision
```

一次调用的内部顺序：

1. 选模：同一 `(sessionId, turnId)` 命中冻结则复用，否则按路由计划决策并写冻结（MH-01）。新用户消息即新 Turn，自然重新决策。
2. 配额预检：按空间和 Agent 检查本周期 token 余量，不足则抛 `QUOTA_EXCEEDED`。
3. 限流：按 binding 的 RPM/TPM 令牌桶（Redis Lua），短时排队而不是立即报错。
4. 调用：可重试错误（429、5xx、超时）指数退避重试 2 次；仍失败则切到路由计划中的下一个 binding，最多接力 2 次，冻结记录同步更新，并写一条 `FailoverEvent` 到 Turn 时间线（MH-02）。耗尽时显式失败。
5. 记账：写 `model_usage`，扣减配额计数（MH-05）。

`OpenAiCompatibleClient` 降为 model-biz 内部类。ArchUnit 规则：除 model-biz 外，任何模块不得依赖模型 HTTP 客户端；Flow 的 LLM/Agent/Embedding 节点和 Knowledge 的嵌入都改为调 `ModelGateway`（MH-04）。

**配额**（GOV-03）

| 指标 | 作用对象 | 卡点 | 实现 |
| --- | --- | --- | --- |
| 月度 token | 空间、Agent、用户 | ModelGateway 预检 + 记账 | Redis `INCRBY` 计数，60 秒落库一次 |
| 并发 Turn | 空间、用户 | `startTurn` 占用，Turn 终态释放 | Redis 有序集合存 turnId + 租约时间，过期自动清理，避免泄漏 |
| 工具调用频率 | 工具、空间 | ToolGateway | 令牌桶 |

```sql
CREATE TABLE gov_quota (
  scope_type VARCHAR(16), scope_id VARCHAR(64), metric VARCHAR(32),   -- tokens_month / concurrent_turns / tool_rpm
  limit_value BIGINT NOT NULL, used_value BIGINT NOT NULL DEFAULT 0, window_start TIMESTAMP,
  PRIMARY KEY (scope_type, scope_id, metric));                        -- 替代 sys_quota

CREATE TABLE model_turn_freeze (
  session_id VARCHAR(64), turn_id VARCHAR(32), binding_id VARCHAR(64) NOT NULL,
  reason VARCHAR(255), failover_count INT NOT NULL DEFAULT 0, created_at TIMESTAMP NOT NULL,
  PRIMARY KEY (session_id, turn_id));                                 -- 替代 model_session_freeze

CREATE TABLE model_usage (
  id VARCHAR(32) PRIMARY KEY, tenant_id VARCHAR(32), workspace_id VARCHAR(64), user_id VARCHAR(32),
  agent_id VARCHAR(64), session_id VARCHAR(64), turn_id VARCHAR(32), purpose VARCHAR(16),
  binding_id VARCHAR(64), model_ref VARCHAR(255), prompt_tokens INT, completion_tokens INT,
  cost_micros BIGINT, latency_ms INT, status VARCHAR(16), created_at TIMESTAMP NOT NULL); -- 替代 cost_event
```

**审计**（GOV-02、CON-01）

三个写入来源：应用服务上的 `@Audited(action = "agent.publish")` 切面覆盖所有写操作；`ToolGateway` 对每次工具调用（含被拒）写一条；Spring Security 的 401/403 处理器写拒绝记录。审计与业务写在同一事务内落表，再由异步任务转发到文件或 SIEM，保证业务成功则审计必有。

```java
public record AuditEvent(String id, Instant ts, String tenantId, String workspaceId,
                         String actorType, String actorId, String action,
                         String resourceType, String resourceId,
                         String outcome,          // SUCCESS / DENIED / ERROR
                         String reason, String traceId, String clientIp,
                         Map<String, Object> details) {}
```

```sql
CREATE TABLE audit_event (
  id VARCHAR(32) PRIMARY KEY, ts TIMESTAMP NOT NULL, tenant_id VARCHAR(32) NOT NULL,
  workspace_id VARCHAR(64), actor_type VARCHAR(16), actor_id VARCHAR(64), action VARCHAR(64) NOT NULL,
  resource_type VARCHAR(32), resource_id VARCHAR(64), outcome VARCHAR(16) NOT NULL, reason TEXT,
  trace_id VARCHAR(64), client_ip VARCHAR(64), details_json TEXT,
  prev_hash VARCHAR(64), hash VARCHAR(64));   -- 哈希链列现在预留，M4 启用（GOV-09）
CREATE INDEX idx_audit_ws_ts ON audit_event (workspace_id, ts);
```

现有 `QuotaService`、`FileAuditSink` 保留为实现细节，对外只暴露上述接口；`tioka-server` 的 `OpsFacadeController` 改为只依赖 `-api` 契约（ENG-06）。

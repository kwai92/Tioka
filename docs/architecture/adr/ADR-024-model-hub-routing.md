# ADR-024 — Model Hub 供应商、路由与决策

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：MH-03、MH-06、MH-07
- 对应设计：设计 J
- 阅读版：[Tioka 推进规划与详细设计](https://claude.ai/artifact/V3wtabWCVSwAC3ewNerFNi)
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

覆盖 MH-03、MH-06、MH-07 与 S10，是设计 C 中 `ModelGateway` 「选模」一步的展开。核心决定是：**路由计划也是类型化、可版本化的快照，决策逻辑是可插拔的 `RoutingDecider`**。现有关键词难度判断降级为内置的启发式决策器，不再冠名「决策模型」。

## 模型目录

| 实体 | 内容 |
| --- | --- |
| ProviderConnection | 协议（OpenAI 兼容、Anthropic、Azure OpenAI、Ollama 等）、Base URL、密钥引用、健康状态；出网走 EgressClient |
| ModelBinding | 某供应商上的一个可调用模型：用途（general / decision / embedding / rerank）、能力（工具调用、视觉、JSON、上下文长度）、单价、RPM/TPM 上限、启停 |
| LogicalModel | 可选的产品侧名称，把多个供应商的同一模型收拢展示 |

## 路由计划

```java
public record RoutingPlanSpec(
    int schemaVersion,
    RoutingMode mode,                       // FIXED / DIFFICULTY / BRANCH / FREE_FIRST
    Map<String, List<String>> groups,       // 组名 → 有序的 bindingId 候选
    Map<String, String> categoryToGroup,    // BRANCH 模式：任务类别 → 组
    DecisionConfig decision,                // deciderId、决策 binding、超时、超时时的兜底组
    EscalationPolicy escalation,            // 常规组耗尽是否升主力组；主力组耗尽即失败
    boolean preferPreviousInGroup) {}       // 同组时优先沿用上一模型

public interface RoutingDecider {            // 决策器 SPI
    String id();                              // heuristic / llm / http:{name}
    Decision decide(DecisionInput input);
}
record DecisionInput(String userText, String previousGroup, Double previousCompetence, Map<String, Object> hints) {}
record Decision(String category, Difficulty difficulty, double confidence, String reason) {}
```

内置三种决策器：启发式（现有 `DifficultyRouter` 搬入）、大模型决策（用决策 binding 以固定提示词输出 JSON，经 `ModelGateway` purpose=decision）、外部 HTTP（客户自有决策服务）。HiRoute 的 Jev 决策模型可作为外部 HTTP 决策器接入。

## 一次选模

1. 只在 Turn 开始时决策；同一 Turn 内的工具续写沿用冻结（设计 C）。上下文压缩后允许重新决策。
2. FIXED 模式不调决策器。其他模式调决策器得到类别和难度，映射到组；决策超时（默认 1.5 秒）或失败时走兜底组，并在时间线记录原因。
3. 组内按候选顺序过滤：未启用、熔断中、缺少本 Turn 所需能力（有工具则需工具调用，有图片则需视觉）的跳过。新组与上一 Turn 同组时优先沿用上一模型。
4. FREE\_FIRST：先试免费组，失败后按计划升到主力组或直接失败。
5. 每一步（决策、选中、沿用、接力）写一条路由事件，构成会话路由时间线。

## 限流、重试与冷却（MH-03）

binding 级 RPM/TPM 令牌桶放 Redis；取不到令牌时最多等待 2 秒，仍不足则视为该候选不可用、走下一个。一分钟内失败率超过 50%（至少 10 次调用）的 binding 进入冷却 60 秒。重试和接力次数见设计 C。

## 发布与生效

路由计划有草稿和不可变版本。发布校验：候选都存在且启用、需要决策器的模式已配决策 binding、阈值合法。**Agent 引用的是计划 ID，计划发布新版本对所有 Agent 立即生效**，这是集中路由的意义；时间线记录每次使用的计划版本，出问题可一键回滚。管理员可为调试在会话上钦死某个 binding，强制审计。

## 胜任度与成本（MH-07）

Turn 结束后可选地用决策 binding 给本轮回答打 0–1 分，结果存表并作为下一次决策的输入。M3 先只记录不参与决策，观察一段时间再开启。成本在写用量时按 binding 单价计算，每天汇总到日报表供看板和导出。

## 数据模型

```sql
ALTER TABLE model_provider ADD COLUMN tenant_id VARCHAR(32) NOT NULL;
ALTER TABLE model_provider ADD COLUMN protocol VARCHAR(32) NOT NULL;
ALTER TABLE model_provider ADD COLUMN health VARCHAR(16);
ALTER TABLE model_binding ADD COLUMN purpose VARCHAR(16) NOT NULL;          -- general / decision / embedding / rerank
ALTER TABLE model_binding ADD COLUMN capabilities_json TEXT;
ALTER TABLE model_binding ADD COLUMN price_in_micros BIGINT;                -- 每百万输入 token
ALTER TABLE model_binding ADD COLUMN price_out_micros BIGINT;
ALTER TABLE model_binding ADD COLUMN rpm_limit INT;
ALTER TABLE model_binding ADD COLUMN tpm_limit INT;
ALTER TABLE model_binding ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
-- model_routing_plan 只保留身份信息，内容移入版本表；decision_binding 并入 spec
CREATE TABLE model_routing_plan_version (id VARCHAR(32) PRIMARY KEY, plan_id VARCHAR(64) NOT NULL, version INT NOT NULL,
  spec_json TEXT NOT NULL, spec_hash VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL,
  published_by VARCHAR(32), published_at TIMESTAMPTZ, UNIQUE (plan_id, version));
CREATE TABLE model_route_event (id VARCHAR(32) PRIMARY KEY, session_id VARCHAR(64) NOT NULL, turn_id VARCHAR(32),
  plan_version_id VARCHAR(32), kind VARCHAR(16) NOT NULL,     -- DECIDE / SELECT / REUSE / FAILOVER / FAIL
  category VARCHAR(64), grp VARCHAR(64), binding_id VARCHAR(64), reason TEXT, latency_ms INT, at TIMESTAMPTZ NOT NULL);
CREATE TABLE model_competence (turn_id VARCHAR(32) PRIMARY KEY, binding_id VARCHAR(64), score DOUBLE PRECISION,
  at TIMESTAMPTZ NOT NULL);
CREATE TABLE model_usage_daily (day DATE, workspace_id VARCHAR(64), agent_id VARCHAR(64), binding_id VARCHAR(64),
  calls INT, prompt_tokens BIGINT, completion_tokens BIGINT, cost_micros BIGINT,
  PRIMARY KEY (day, workspace_id, agent_id, binding_id));
```

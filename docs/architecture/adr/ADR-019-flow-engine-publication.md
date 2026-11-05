# ADR-019 — Flow 执行引擎与发布

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：FLW-01〜07
- 对应设计：设计 E
- 阅读版：[Tioka 推进规划与详细设计](https://claude.ai/artifact/V3wtabWCVSwAC3ewNerFNi)
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

覆盖 FLW-01〜07。核心决定是：**把内存里按「波次」跑完的 DAG，改成状态落库、事件驱动的节点调度器**。这样汇合节点、人工节点挂起、Agent 节点长时间运行、进程重启后续跑，都用同一套机制解决。引擎复用 Runtime 的 worker 基础设施（Redis Stream 分发、租约、`run_as` 恢复身份）。

**节点状态与调度规则**

节点状态：`PENDING → READY → RUNNING → SUCCEEDED | FAILED | SKIPPED`，另有 `WAITING`（等审批或等 Agent Turn）。调度器在任一节点到达终态时重新计算下游：

- Branch 节点完成后，未选中的出边标记为「已跳过」；一个节点的入边全部被跳过时，它自己变成 SKIPPED 并继续向下传播。
- 汇合节点在所有入边都「已解决」（上游成功或被跳过）且至少一条来自成功节点时变成 READY。这就修复了现在「入度归不到 0，汇合节点永不执行」的问题（FLW-01）。节点可配 `joinMode`：`ALL`（默认）或 `ANY`（任一上游成功即执行）。
- 单个 Run 并发节点数上限 4；节点失败按配置重试，仍失败则 Run 失败，除非该节点配了 `continueOnError`。
- Loop 节点把循环体当作子图，每轮生成独立的 node run（`iteration` 字段区分），最大轮数必填。

**挂起与续跑**（FLW-03、FLW-04）

| 节点 | 执行方式 | 唤醒条件 |
| --- | --- | --- |
| HumanCard / Approval | 写一条 `runtime_approval`，节点进入 WAITING，释放 worker | 审批提交（Web 审批台或飞书卡片）调 `decideApproval` |
| Agent | 调 `RuntimeApi.startTurn`（origin=flow，originRef=节点运行 ID），节点进入 WAITING | 收到该 Turn 的 `turn.completed` 或 `turn.failed` |
| LLM / Embedding | 同步调 `ModelGateway`（purpose=flow） | — |
| Retrieve | 调 `KnowledgeSearchApi.search` | — |
| MCP / HTTP | 经 `ToolGateway`，统一鉴权与审计 | — |

等待不占线程，状态全在数据库，所以服务重启不会丢失等待中的 Run。Flow 里的人工节点和 Orchestration 的 Attention 共用同一张审批表和同一个审批台。

**类型化的图与节点 SPI**（FLW-05）

现在图和节点配置都是 `Map<String, Object>`。改为显式类型，发布时做完整校验（除 Loop 子图外无环、端口类型匹配、必填配置齐全、凭证引用存在）。

```java
public record FlowGraph(int schemaVersion, List<NodeSpec> nodes, List<EdgeSpec> edges) {}
public record NodeSpec(String id, String type, String typeVersion, JsonNode config,
                       JoinMode joinMode, RetryPolicy retry, boolean continueOnError) {}
public record EdgeSpec(String from, String fromPort, String to, String toPort, String when) {}

public interface NodeType {                      // 供 Studio 节点面板与校验使用
    String type();
    String version();
    NodeSchema schema();                         // 输入/输出端口 + 配置 JSON Schema
    NodeExecutor executor();
}
public interface NodeExecutor {
    NodeResult execute(NodeContext ctx, Map<String, Object> inputs);
}
public sealed interface NodeResult {
    record Completed(Map<String, Object> outputs) implements NodeResult {}
    record Waiting(String waitKey) implements NodeResult {}          // approvalId 或 turnId
    record Failed(String code, String message, boolean retryable) implements NodeResult {}
}
```

自定义节点分两步：M3 提供 Java 插件（从插件目录加载实现 `NodeType` 的 jar，需签名）和「远程节点」（按约定协议调用外部 HTTP 服务，任何语言都能实现）；Python/JS 脚本节点要等 M4 的沙箱（RT-11）。

**发布**（FLW-06）

一个已发布版本可以有多个发布形态，取代现有只支持工具形态的 `flow_tool_pub`。输入输出 Schema 从图的 Input/Output 节点自动生成。

| 形态 | 入口 | 调用者身份 |
| --- | --- | --- |
| API | `POST /api/flows/pub/{name}/runs`：同步（带超时）或异步（返回 runId + SSE 事件流） | API Key 对应的服务账号 |
| Agent 工具 | 在 ToolGateway 注册为 `flow.{name}`，Agent 配置后可调用 | 发起调用的 Turn 的 Principal |
| MCP 工具 | 由现有 MCP 出口服务暴露，OAuth 鉴权（CON-05） | MCP 客户端授权的用户 |

运行时参数覆盖只允许发布时勾选的节点配置项（如提示词、topK），其他配置一律以发布版本为准。用量和配额按调用者身份归集，满足 G3「用量计入该 Agent 配额」的验收。

**数据模型**

```sql
ALTER TABLE flow_draft ADD COLUMN tenant_id VARCHAR(32) NOT NULL;
ALTER TABLE flow_draft ADD COLUMN workspace_id VARCHAR(64) NOT NULL;
ALTER TABLE flow_draft ADD COLUMN owner_id VARCHAR(32) NOT NULL;
ALTER TABLE flow_draft ADD COLUMN revision INT NOT NULL DEFAULT 0;    -- 乐观锁，保存时带 If-Match（FLW-07）

-- flow_run 重建：去掉整块 trace_json，改为节点级记录
CREATE TABLE flow_run (
  id VARCHAR(32) PRIMARY KEY, version_id VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL,
  origin VARCHAR(16) NOT NULL,                 -- studio / api / tool / mcp / orch
  publication_id VARCHAR(32), run_as_json TEXT NOT NULL,
  input_json TEXT, output_json TEXT, error TEXT,
  created_at TIMESTAMP NOT NULL, ended_at TIMESTAMP);

CREATE TABLE flow_node_run (
  id VARCHAR(32) PRIMARY KEY, run_id VARCHAR(32) NOT NULL, node_id VARCHAR(64) NOT NULL,
  iteration INT NOT NULL DEFAULT 0, status VARCHAR(16) NOT NULL, attempt INT NOT NULL DEFAULT 0,
  wait_key VARCHAR(64),                        -- approvalId 或 turnId
  input_json TEXT, output_json TEXT, error TEXT,
  worker_id VARCHAR(64), lease_until TIMESTAMP, started_at TIMESTAMP, ended_at TIMESTAMP,
  UNIQUE (run_id, node_id, iteration));
CREATE INDEX idx_node_run_wait ON flow_node_run (wait_key);

CREATE TABLE flow_publication (
  id VARCHAR(32) PRIMARY KEY, version_id VARCHAR(64) NOT NULL, kind VARCHAR(8) NOT NULL,  -- API / TOOL / MCP
  name VARCHAR(128) NOT NULL, input_schema TEXT, output_schema TEXT,
  overridable_json TEXT, status VARCHAR(16) NOT NULL, workspace_id VARCHAR(64) NOT NULL,
  created_by VARCHAR(32), created_at TIMESTAMP NOT NULL,
  UNIQUE (workspace_id, kind, name));                -- 取代 flow_tool_pub
```

## 修订（2026-11-05）

Agent 工具形态的 Flow Run 携带调用方的委派授予（`flow_run.grant_id`），发布时计算 `requiredCapabilities`，见 [ADR-029](ADR-029-design-rulings.md) 第 47 条与 [ADR-039](ADR-039-delegation-grant.md)。

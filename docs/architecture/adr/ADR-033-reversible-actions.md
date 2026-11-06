# ADR-033 — 可撤销执行：动作契约、变更单与撤销

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：TRU-05〜08（新增）
- 对应设计：设计 Q（可信放权 D1）
- 方向文档：[Tioka 差异化能力方向：数字员工全生命周期](https://claude.ai/artifact/1HP9f4Rr9XmaiZqMMLRJjx)
- 相关：[ADR-032](ADR-032-agent-charter.md) 授权书、[ADR-034](ADR-034-autonomy-ladder.md) 自治阶梯、M1-04 CON-01、M2-03 CON-03
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

现有设计对 Agent 的写操作只有「拦」和「放」两种处理：审批卡片给人看的是工具名和原始参数，执行之后再出问题只能人工去业务系统里改回来。企业不敢放权，根子在这里。

本设计让每个写操作都走**预演 → 变更单 → 判定 → 提交 → 回执**，同一个 Turn 或编排 Run 里的写操作组成一个可整体撤销的组。负责人在飞书卡片上点「撤销本次」，系统按逆序补偿。

核心决定：

1. 工具在原有风险等级 `ToolRisk` 之外，再声明**可逆性 `Reversibility`**。两个维度正交：风险说明「错了有多严重」，可逆性说明「错了能不能收回」。
2. ToolGateway 的 `call` 拆成 **`prepare` 和 `commit`**。审批、自治判定、Cedar 求值都发生在两者之间，审批人看到的是结构化的变更单，不是原始参数。
3. 撤销通过 **outbox 驱动的补偿**完成，逐条幂等；无法撤销的部分明确告诉人，并生成待办，从不静默失败。

## 可逆性（TRU-05）

```java
public enum Reversibility {
    NONE,          // 只读，无副作用
    REVERSIBLE,    // 有对应的撤销接口，执行后状态可完全恢复（如改字段、加标签）
    COMPENSABLE,   // 不能删除痕迹，但有业务上的冲销动作（如红冲凭证、取消订单）
    IRREVERSIBLE   // 发出去就收不回（发邮件、对外付款、通知客户）
}
```

| 来源 | 默认值 | 覆盖方式 |
| --- | --- | --- |
| 内置工具 | 在 `ToolDescriptor` 里声明 | 代码评审 |
| 业务系统工具（`TransactionalTool` 实现） | 实现类声明 | 代码评审 |
| MCP 工具 | `readOnlyHint=true` → NONE；其余 → IRREVERSIBLE | 管理员在 MCP 页修改，需配置补偿工具后才能改为 COMPENSABLE |
| 未声明的写工具 | IRREVERSIBLE | — |

默认从严：未声明就当收不回来处理，这样新接入的工具不会在高自治等级下被自动执行（ADR-034 的判定表）。

## 连接器 SDK：事务型工具

业务系统工具实现 `TransactionalTool`，在 `ToolProvider` 之外多三个钩子：

```java
public interface TransactionalTool extends ToolProvider {
    /** 不改变外部状态，可重复调用。读取当前值，算出将要发生的变化。 */
    ChangeSet preview(ToolInvocation inv);
    /** 按已批准的变更单执行。idempotencyKey = change_set item id，重复调用返回同一回执。 */
    ApplyReceipt apply(ToolInvocation inv, ChangeSet approved, String idempotencyKey);
    /** REVERSIBLE、COMPENSABLE 必须实现。幂等；对已补偿的回执直接返回成功。 */
    CompensationResult compensate(ApplyReceipt receipt, String idempotencyKey);
}

public record ChangeSet(
    String summary,                      // 一句话，给卡片标题用：「核销 3 张应收单，合计 12,400.00 元」
    List<Change> changes,
    Totals totals,
    String fingerprint,                  // 对各 change 的 before 取哈希，提交前用于检测漂移
    boolean estimated,                   // true = 没有真实 before 值，只是按参数推断
    Instant validUntil) {}               // 默认 preview 后 30 分钟
public record Change(String objectType, String objectId, ChangeOp op,   // CREATE / UPDATE / DELETE / SEND / PAY
                     Map<String, Object> before, Map<String, Object> after) {}
public record Totals(int records, Long amountMicros, String currency, Set<String> objectTypes) {}
public record ApplyReceipt(String externalRef, Map<String, Object> compensationToken, Instant appliedAt) {}
public record CompensationResult(boolean ok, String message, String externalRef) {}
```

- **漂移检测**：`apply` 前实现方重新读取 before 值并计算指纹。指纹与已批准变更单不一致时返回 `STALE`，网关不执行，回到预演并重新判定。人批准的永远是将要真实发生的变化，不会是半小时前的旧状态。
- **不实现 `TransactionalTool` 的写工具**（大部分 MCP 工具）：网关按参数生成 `estimated=true` 的变更单，`changes` 为空、只有参数摘要。`estimated` 的变更单在 ADR-034 判定表里不能自动提交到 L3 以上。
- 业务 OpenAPI 转工具（CON-07，已延后）实现时，用 `x-tioka-preview`、`x-tioka-compensate` 扩展字段把预演和补偿指向另外的接口；在此之前客户用 SDK 写适配器。

## 工具调用管线（改 M1-04 CON-01 的顺序）

```java
public interface ToolGateway {
    List<ToolDescriptor> listTools(ToolCallContext ctx);
    /** 只读工具：等价于 prepare + commit。保留，供 READ 工具和内部调用使用。 */
    ToolResult call(ToolCallContext ctx, String toolName, Map<String, Object> args);
    /** 写工具：做完所有检查和预演，返回待提交的调用与判定，不改变外部状态。 */
    PreparedCall prepare(ToolCallContext ctx, String toolName, Map<String, Object> args);
    /** 按 prepare 的结果执行。审批通过后由 Runtime 调用。 */
    ToolResult commit(ToolCallContext ctx, String preparedId);
    List<Map<String, Object>> asOpenAiTools(ToolCallContext ctx);

    record PreparedCall(String preparedId, String actionClass, ChangeSet changeSet,
                        PolicyOutcome policy, GateDecision gate) {}
    enum PolicyOutcome { ALLOW, DENY, ESCALATE }               // Cedar（ADR-025 / ADR-032）
    enum GateDecision { AUTO_COMMIT, REQUIRE_APPROVAL, SHADOW_ONLY, DENIED }   // ADR-034
}
```

`prepare` 的顺序（1〜6 与 M1-04 相同）：

```text
1. 解析工具   2. 白名单   3. 授权（RBAC、MCP 授权、知识库 ACL）
4. 权限重载（60 秒）   5. 限流   6. 参数 schema 校验
7. 授权书：CharterApi.check → Forbidden / Unclassified / Suspended 直接 DENIED（ADR-032）
8. 预演：TransactionalTool.preview，或按参数生成 estimated 变更单；写 change_set item（status=PROPOSED）
9. 策略：Cedar 求值，resource 取变更单 totals 与 policyFields，context 带当日累计、来源、自治等级 → ALLOW / DENY / ESCALATE
10. 判定：AutonomyGate.decide(level, policy, risk, reversibility, estimated, origin) → GateDecision（ADR-034）
11. 爆炸半径：本 Turn / Run 内同一动作类别的累计 + 本次 totals 超过 perRunRecords 或单 Run 金额 → 至少 REQUIRE_APPROVAL
12. 审计 tool.prepare（含变更单摘要与命中的策略 @id）
```

`commit` 的顺序：

```text
1. 取 prepared；已提交 → 返回同一结果（幂等）；已过 validUntil → 重新 prepare
2. 出网：EgressGuard
3. apply(inv, changeSet, idempotencyKey=item id)；STALE → change_set item 标 STALE，返回模型「数据已变化，请重新确认」
4. 成功：change_set item → APPLIED，写 trust_action（含回执与补偿令牌、undo_deadline）；Redis 当日累计加上 totals
5. 审计 tool.invoke（沿用 ADR-029 第 8 条：调用返回后单独写）
```

Runtime 执行循环（M1-02 RT-07）对应改为：写工具先 `prepare`，`AUTO_COMMIT` 立即 `commit`；`REQUIRE_APPROVAL` 走原有挂起流程，审批卡片带变更单，审批通过后 `commit`；`SHADOW_ONLY` 不提交，把变更单作为工具结果返回给模型（带「仅记录，未执行」标记）；`DENIED` 返回拒绝原因。`ApprovalPolicy` SPI 不删除，由 ADR-034 的 `autonomy` 实现承接。

**审批时不允许改变更单**。审批人想改，只能改参数（RT-07 已有「带修改后的参数批准」），网关据此重新 `prepare`，生成新变更单，再按新变更单判定。这样落库的变更单与实际执行的永远一致。

## 变更单的呈现（TRU-06）

新增 PartType `change_set`（runtime_item 的一种），内容即 `ChangeSet` 加状态：

```text
PROPOSED → APPROVED → APPLIED → COMPENSATING → COMPENSATED
    │          │          └──────────────────▶ COMPENSATION_FAILED
    └→ REJECTED └→ STALE / FAILED
```

| 端 | 呈现 |
| --- | --- |
| 飞书审批卡片（改 M2-03 CON-03） | 标题用 `summary`；表格展示前 10 条变化（对象、字段、原值 → 新值，变化处高亮）；合计金额与记录数；可逆性标签（可撤销 / 可冲销 / 不可撤销，最后一种红色）；`estimated` 时显示「未读取当前值，以下为推断」；超过 10 条附「在 Tioka 中查看全部」 |
| 飞书回答卡片（M2-03 流式卡片） | Turn 结束后，若本 Turn 有 APPLIED 的可撤销动作且未过撤销期，卡片底部出现「撤销本次」按钮，按钮 value 同样带 HMAC 签名，接收人限定为可撤销人 |
| Web 审批台（M2-04 HCI-05） | 同卡片，另可展开全部变化和 JSON |
| 轨迹视图（M2-04 HCI-07） | 每个 change_set item 带状态徽标，可跳到对应审计记录 |

## 撤销（TRU-07）

**撤销单位**：一个 Turn，或一个编排 Run（Run 下多个 Turn 合并）。也支持撤销单个动作。

**谁可以撤销**：Turn 发起人、编排工单请求人、Agent 负责人、批准过该动作的审批人，以及有 `trust:undo` 权限的空间管理员。撤销本身写审计。

**撤销期**：按动作类别配置，默认 24 小时；过期后按钮消失，接口返回 `UNDO_WINDOW_CLOSED`。

**流程**：

```text
POST /api/trust/undo {scope: turn|run|action, id}
  事务内：选出该范围内 APPLIED 且未过撤销期的 trust_action，按 seq 倒序
          REVERSIBLE / COMPENSABLE → 状态 COMPENSATING，写 outbox tioka.trust.compensate.v1（每个动作一条，带顺序号）
          IRREVERSIBLE → 不动，记入「无法撤销」清单
  返回：将补偿的动作、无法撤销的动作
消费者 trust-compensator（按 scope id 分区，保证同一组顺序执行）：
  compensate(receipt, idempotencyKey = action id + ":undo")
  成功 → COMPENSATED；Redis 当日累计扣回；发 tioka.trust.action_undone.v1
  失败 → 重试 3 次（指数退避）→ COMPENSATION_FAILED，停止该组后续补偿，
         生成给负责人的 Attention（编排人审），写审计；不静默
存在无法撤销的动作 → 同样生成 Attention，列出需要人工处理的对象
```

**撤销的连带效果**（消费 `tioka.trust.action_undone.v1`）：

- ADR-034：该 Agent 该动作类别的自治等级降一级。
- ADR-035：账本写一条冲销记录，原任务不再计入节省。
- ADR-031：写一条负向反馈（`eval_feedback`，rating = -1，comment = 撤销原因），该 Turn 进入「待标注」队列，可回流为回归用例。

## 爆炸半径与熔断（TRU-08）

- 单 Run 累计：见管线第 11 步，额度来自授权书 `perRunRecords` 和单次 / 当日金额。
- 熔断：同一 Agent 1 小时内被撤销 3 次，或补偿失败 1 次，该 Agent 全部动作类别降到 L1（ADR-034），并通知负责人。阈值空间可配置。

## 数据模型

```sql
CREATE TABLE trust_action (id VARCHAR(32) PRIMARY KEY, agent_id VARCHAR(64) NOT NULL,
  agent_version_id VARCHAR(64) NOT NULL, thread_id VARCHAR(64) NOT NULL, turn_id VARCHAR(32) NOT NULL,
  orch_run_id VARCHAR(32), item_id VARCHAR(32) NOT NULL UNIQUE, seq INT NOT NULL,
  tool_name VARCHAR(255) NOT NULL, action_class VARCHAR(64) NOT NULL,
  risk VARCHAR(16) NOT NULL, reversibility VARCHAR(16) NOT NULL,
  status VARCHAR(24) NOT NULL,          -- PROPOSED / APPROVED / APPLIED / STALE / FAILED / COMPENSATING / COMPENSATED / COMPENSATION_FAILED / SHADOW
  gate VARCHAR(24) NOT NULL,            -- AUTO_COMMIT / REQUIRE_APPROVAL / SHADOW_ONLY
  change_set_json TEXT NOT NULL, fingerprint VARCHAR(64), estimated BOOLEAN NOT NULL,
  records INT NOT NULL DEFAULT 0, amount_micros BIGINT, approval_id VARCHAR(32),
  receipt_json TEXT, undo_deadline TIMESTAMPTZ, undone_by VARCHAR(64), undo_reason TEXT,
  create_at TIMESTAMPTZ NOT NULL DEFAULT now(), update_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX idx_trust_action_turn ON trust_action (turn_id, seq);
CREATE INDEX idx_trust_action_run ON trust_action (orch_run_id, seq);
CREATE INDEX idx_trust_action_agent ON trust_action (agent_id, action_class, create_at);
ALTER TABLE mcp_tool ADD COLUMN reversibility VARCHAR(16) NOT NULL DEFAULT 'IRREVERSIBLE';
ALTER TABLE mcp_tool ADD COLUMN compensate_tool VARCHAR(255);     -- COMPENSABLE 时必填
-- runtime_item.type 增加 change_set；runtime_approval.kind 不变（仍为 tool），card_json 带变更单
```

ID 前缀：`act` 动作。事件：`tioka.trust.compensate.v1`、`tioka.trust.action_undone.v1`、`tioka.trust.compensation_failed.v1`。

## 测试桩

闸门测试需要一个有状态的业务系统。`tioka-testkit` 增加 `FakeErp`：内存中的订单与应收单，实现 `TransactionalTool` 的全部钩子，并能注入「补偿失败」「before 值被他人修改」两种故障。

## 工作项

| 编号 | 工作项 | 阶段 | 优先级 | 估算（人日） | 依赖 | 验收 |
| --- | --- | --- | --- | --- | --- | --- |
| TRU-05 | 可逆性声明、`TransactionalTool` SDK、`prepare` / `commit` 拆分、change_set item、漂移检测、FakeErp | M2 | P1 | 12 | CON-01、RT-07 | 指纹不一致时不执行并回到预演 |
| TRU-06 | 变更单卡片（飞书审批卡片、审批台、轨迹视图） | M2 | P1 | 8（后端 3、前端 5） | TRU-05、CON-03、HCI-05 | 审批卡片展示变化表与可逆性标签 |
| TRU-07 | 撤销：补偿消费者、撤销接口与卡片按钮、无法撤销转人工、连带事件 | M3 | P0 | 11 | TRU-05、ORC-02 | G3：撤销一次 5 步写操作，FakeErp 状态与执行前快照完全一致；含不可撤销动作时生成 Attention |
| TRU-08 | 单 Run 爆炸半径与撤销熔断 | M3 | P1 | 4 | TRU-07、TRU-02 | 1 小时内第 3 次撤销后该 Agent 全部降到 L1 |

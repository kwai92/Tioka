# ADR-039 — 委派授予与权限收窄

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：MAS-01、MAS-02、DAT-04〜06（新增）
- 对应设计：设计 Q（多智能体与运行时权限）
- 阅读版：[Tioka 多智能体自主协作与权限管控设计](https://claude.ai/artifact/Qy9taJVV8cz2B7VBGewkJA)
- 相关：[ADR-015](ADR-015-identity-tenant-rbac.md) Principal、[ADR-025](ADR-025-governance.md) Cedar、[ADR-032](ADR-032-agent-charter.md) 授权书、[ADR-033](ADR-033-reversible-actions.md) 工具调用管线、[ADR-038](ADR-038-multi-agent-team.md) 团队、[ADR-040](ADR-040-dataset-policy-data-label.md) 数据权限
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

多智能体的权限风险只有一个根源：权限大的 Agent 被权限小的人或 Agent 指使，或者被数据里的文字指使（提示注入）。核心决定是：**权限只随委派收窄，永不放大**。权限只来自配置（快照、授权书、空间策略）和人的审批；模型输出、工具结果、Skill 文本都不改变授予。

ADR-032 回答「这个 Agent 平时能做什么、做多大」；本篇回答「一条委派链上，这一次能做什么、能看什么」。本篇不新增策略引擎，Cedar 仍是唯一的允许/拒绝/转审批求值点。

## 三个问题分开回答

| 问题 | 载体 | 在链上怎么变 |
| --- | --- | --- |
| 以谁的名义 | 链根 Principal（`run_as`）；SERVICE 模式另带 `on_behalf_of`（代表哪位经办人） | **全链不变**。子 Agent 不能中途改名义；确需换名义，只能经编排 `emit` 开新工单，生成新的 `orch_mandate` |
| 经过哪些 Agent | `DelegationChain`：根 → 主管 → 子 → 孙 | 每委派一次追加一节，深度 ≤ 3 |
| 这一次能做什么 | `DelegationGrant`，挂在 thread 上 | 只能取交集 |

`on_behalf_of` 时根权限 = 服务账号角色 ∩ 经办人权限。这让编排触发的 Run 不会因服务账号权限宽而检索到经办人无权看的数据（商业租赁合同场景提出的缺口）。`run_as_json` 增加 `onBehalfOf` 字段，两者都只存身份，按定案 6 每次工具调用前重新加载权限。

## DelegationGrant（MAS-01）

```java
public record DelegationGrant(
    String grantId, String parentGrantId,          // 根授予 parent 为空
    String rootPrincipalRef, String onBehalfOf,
    List<ChainHop> chain,                          // agentVersionId + taskId
    Set<String> tools,                             // 工具名（内置、MCP、flow.*、业务插件）
    Set<String> actionClasses,                     // 可用的写动作类别
    Set<String> skillVersionIds,
    Map<String, Set<String>> resources,            // kb / dataset / mcp / flow → ID 集合
    Map<String, List<String>> scope,               // 参数取值范围，如 {"contractId":["HT-2026-031"]}
    DataLabel maxDataLabel,                        // 能读的最高数据标签（ADR-040）
    Set<String> egressHosts,
    Budget budget, Instant expiresAt,
    GrantStatus status,                            // ACTIVE / REAPED / REVOKED
    String hash) {}

public interface GrantService {
    DelegationGrant root(Principal runAs, String onBehalfOf, AgentSnapshot agent, MandateScope mandate);
    DelegationGrant narrow(DelegationGrant parent, AgentSnapshot child, TaskNarrow narrow);  // 不是子集则抛 GRANT_NOT_NARROWER
    void reap(String grantId);
    DelegationGrant get(String grantId);           // Redis 缓存 60 秒，失效事件即时清除
}
```

**根授予**在 `startTurn` 时签发：工具、Skill、资源取自 Agent 版本快照；动作类别取自授权书；scope 取自 `orch_mandate.scope_json`（编排）或空（对话）；`maxDataLabel` 取 Agent 的 `dataClearance` 与空间上限的较小值。

**签发规则**（委派时与子 thread 同事务写入）：

```text
子.tools          = 父.tools ∩ 子快照.tools ∩ narrow.tools
子.actionClasses  = 父.actionClasses ∩ 父授权书.delegable ∩ 子授权书.actionClasses
子.skillVersionIds= 父.skillVersionIds ∩ 子快照.skills
子.resources      = 父.resources ∩ 子快照绑定 ∩ narrow.resources
子.scope          = 父.scope ∧ narrow.scope          -- 只能加条件
子.maxDataLabel   = min(父, 子快照.dataClearance)
子.egressHosts    = 父 ∩ 子沙箱白名单
子.budget         ≤ 父剩余额度，划拨即冻结
子.expiresAt      ≤ 父.expiresAt
```

`narrow` 只接受集合、区间、枚举三种可证明收窄的形式；结果不是输入的子集就拒绝签发并写审计。父授予失效（REAPED、REVOKED）时，所有后代授予同时失效。

## 有效权限

把 ADR-032 TRU-02 从单个 Agent 推广到链：

```text
有效权限(调用) = 根身份权限（含 on_behalf_of 交集）     -- ADR-032，增补 on_behalf_of
              ∩ 执行者授权书（完整）                  -- ADR-032，不变
              ∩ 链上每个祖先的禁区与可下放类别           -- 本 ADR
              ∩ 委派授予                            -- 本 ADR
              ∩ 空间策略（Cedar）                    -- ADR-025，不变
```

祖先只看「禁区 + 可下放」：主管常常自己只读、把写操作派给专家，若要求主管授权书也含该写类别，等于给主管开写权限，反而扩大攻击面。

## 对 ADR-032 的两处增补（MAS-02）

1. `Charter` 增加字段 `List<String> delegable`：可交给下属的动作类别，缺省为空，即不配置就不能向下放任何写操作。发布校验：`delegable` ⊆ 本授权书 `actionClasses` 的编码集合，或为团队成员授权书中的类别（团队 Agent 自身不执行这些类别时也可声明）。
2. `CharterCompiler` 编译**禁区**时，条件由 `context.agentVersion == X` 改为 `context.chain.contains(X)`，祖先禁区约束整条链；**额度、范围类**策略仍只对执行者（`context.agentVersion`）生效。

Cedar context 新增三个属性：`chain`（链上 agentVersionId 集合）、`depth`、`onBehalfOf`。空间管理员可写链级策略，例如孙子层级的不可撤销操作一律转审批：

```cedar
@id("space:escalate-irreversible-at-depth-2")
@effect("ESCALATE")
forbid (principal, action, resource)
when { context.depth >= 2 && resource.reversibility == "IRREVERSIBLE" };
```

## 工具调用管线的改动

在 ADR-033 `prepare` 的 12 步中插入一步，并扩充第 9 步：

```text
2.5 授予：grant.status == ACTIVE 且未过期；toolName ∈ grant.tools；
    参数中的资源 ID ∈ grant.resources（知识库按 M1-04 规则剔除无权 ID，其余资源无权即 DENIED）；
    scope 声明的参数取值在范围内；写工具的动作类别 ∈ grant.actionClasses
9   Cedar context 加 chain、depth、onBehalfOf
```

拒绝统一返回 `ToolResult(false, "无权调用工具 {name}：{原因}")`，不终止 Turn。`listTools` 也按授予过滤，模型看不到授予外的工具。

## Skills、MCP、Workflow 的三道门

| 门 | Skill | MCP 工具 | Workflow（Flow 发布） |
| --- | --- | --- | --- |
| 发布时（AST-02 扩展） | 已签名；清单声明的工具、主机、路径、最高数据标签 ⊆ Agent 的 ToolSet、沙箱策略与 `dataClearance` | 发布者对 Server 有 USE；引用快照号 + 白名单；非只读工具必须归入动作类别（ADR-032 已定） | 发布时计算 `requiredCapabilities` = 全部节点的工具、知识库、数据集、Agent 的并集；绑定到 Agent 时要求 ⊆ Agent 绑定 |
| 委派时 | `skillVersionIds` 取交集；子 Agent 不继承父的 always Skill 文本 | 工具名取交集；代用户令牌仍属链根用户 | Flow 中的 Agent 节点计一层委派，占深度与预算 |
| 调用时 | `skill.load` 只能加载授予内版本；脚本只在沙箱内跑，出网 = Skill 主机 ∩ 沙箱白名单 ∩ `egressHosts` | 全管线；结果按 Server 配置的密级打标签（ADR-040） | `flow.{name}` 以**调用方授予**运行：Flow Run 携带 `grant_id`，内部每次模型与工具调用都用它；`requiredCapabilities` 超出授予时启动即拒（DAT-04） |

要点：

- **Skill 不能带来工具**。清单是上限声明，不是授权；SKILL.md 里写「调用 X」不会让 X 出现在工具列表里。
- **Flow 不能成为提权通道**。ADR-019 的 API 形态仍以 API Key 的服务账号运行（那就是根授予）；Agent 工具形态改为透传调用方授予，而不只是 Principal。
- **MCP 自述不可信**。工具描述和结果都作为不可信数据进上下文；快照刷新后已发布 Agent 仍用旧快照（ADR-021 已定）。

## 对外系统看到的身份

- DELEGATED：MCP 与业务插件用链根用户在 TokenVault 中的令牌（FND-08），子 Agent 没有自己的凭证，外部系统的行级权限天然生效。
- SERVICE：用服务账号凭证，外部系统看不到细分，行级权限必须由 Tioka 的数据集策略补上（ADR-040）。
- Tioka 自身对外暴露的 MCP 与 Agents API 下游：可用 Keycloak Token Exchange（RFC 8693）换出带 `act` 声明的缩权令牌，表明「用户 A 经 Agent X」（DAT-06）。

## 审计（MAS-01）

`audit_event` 增加：`root_principal`、`on_behalf_of`、`chain_json`、`grant_id`、`data_label`。拒绝记录写明执行点、步骤与命中的策略 `@id`。哈希链与外送（GOV-09）不变。

管理端提供权限回答页（DAT-05）：输入「用户 + Agent（+ 链）+ 工具或数据集」，给出有效权限及每一层的裁剪原因。

## 数据模型

```sql
CREATE TABLE rt_grant (id VARCHAR(32) PRIMARY KEY, parent_id VARCHAR(32), root_principal VARCHAR(64) NOT NULL,
  on_behalf_of VARCHAR(64), chain_json TEXT NOT NULL, depth INT NOT NULL, body_json TEXT NOT NULL,
  hash VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL,          -- ACTIVE / REAPED / REVOKED
  expires_at TIMESTAMPTZ NOT NULL, create_at TIMESTAMPTZ NOT NULL DEFAULT now(), ended_at TIMESTAMPTZ);
CREATE INDEX idx_rt_grant_parent ON rt_grant (parent_id);
ALTER TABLE runtime_session ADD COLUMN grant_id VARCHAR(32);
ALTER TABLE flow_run ADD COLUMN grant_id VARCHAR(32);
ALTER TABLE audit_event ADD COLUMN root_principal VARCHAR(64);
ALTER TABLE audit_event ADD COLUMN on_behalf_of VARCHAR(64);
ALTER TABLE audit_event ADD COLUMN chain_json TEXT;
ALTER TABLE audit_event ADD COLUMN grant_id VARCHAR(32);
ALTER TABLE audit_event ADD COLUMN data_label VARCHAR(16);
-- run_as_json 增加 onBehalfOf；Charter 增加 delegable（在 agent_version.spec_json 内）
```

ID 前缀：`grt` 授予。新增错误码：`GRANT_NOT_NARROWER`（签发失败）、`GRANT_INACTIVE`（授予已失效）。

## 工作项

| 编号 | 工作项 | 阶段 | 优先级 | 人日 | 依赖 | 验收 |
| --- | --- | --- | --- | --- | --- | --- |
| MAS-01 | DelegationGrant、`GrantService`、ToolGateway 第 2.5 步、`on_behalf_of`、审计链字段 | M3 | P0 | 5 | RT-12、GOV-07 | G3：子 Agent 调用父有、自己没有的工具被拒并记录链 |
| MAS-02 | 授权书 `delegable`、Cedar context 加链、祖先禁区生效 | M3 | P0 | 2 | TRU-02 | G3：主管禁区内的工具，孙子 Agent 也调不了 |
| DAT-04 | Flow 透传授予与 `requiredCapabilities` | M3 | P1 | 3 | FLW-06、MAS-01 | 超授予的 Flow 启动即拒 |
| DAT-05 | 权限回答页 | M4 | P2 | 3 | MAS-01 | — |
| DAT-06 | Token Exchange 缩权令牌（`act` 声明） | M4 | P2 | 3 | CON-05 | — |

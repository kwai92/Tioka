# ADR-023 — Agent 与 Skill 资产管理

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：AST-01〜06
- 对应设计：设计 I
- 阅读版：[Tioka 推进规划与详细设计](https://claude.ai/artifact/V3wtabWCVSwAC3ewNerFNi)
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

覆盖 S11、S13，新增工作项 AST-01〜06。核心决定是：**Agent 的一切配置收成一个类型化的 `AgentSpec` 文档，发布即冻结为不可变快照**。现在散落在 `agent_version`、`mcp_grant`、`agent_sandbox`、`agent_share` 里的绑定关系合并进快照或 ACL，Runtime 只认快照，不再临时查各张绑定表。

## AgentSpec（AST-01）

```java
public record AgentSpec(
    int schemaVersion,
    Profile profile,                 // 名称、描述、图标、标签、类型（对话 / 任务 / 只读助手）
    Instructions instructions,       // 分段提示词与变量占位
    ModelPolicy model,               // 默认路由计划、场景路由、是否允许用户覆盖
    ToolSet tools,                   // 内置工具、MCP（serverId + snapshotId + 工具白名单）、Flow 发布、业务操作
    List<SkillRef> skills,           // skillVersionId + 模式（manual / auto / always）
    KnowledgeBinding knowledge,      // kbIds + 使用方式（作为工具 / 每轮自动检索）
    SandboxPolicy sandbox,           // read / edit / full、网络白名单、工作目录
    ApprovalPolicy approval,         // 哪些工具需要审批、审批人
    SubAgentPolicy subAgents,        // 可委派的 Agent 与深度（设计 M）
    Limits limits) {}                // maxSteps、每轮 token 上限
```

快照里只存引用 ID，不存密钥。草稿可反复修改（乐观锁），发布时生成新版本号和内容哈希。

## 发布校验（AST-02）

发布前必须全部通过，任一失败则不能发布：

1. 引用的路由计划已发布；MCP 快照存在且 Server 未熔断；Flow 发布与知识库存在。
2. **发布者本人对每一个引用都有 USE 权限**，防止借 Agent 绕过权限。
3. Skill 版本已签名（租户策略要求时），且 Skill 声明的权限是 Agent 沙箱策略的子集。
4. 空间开启了发布审批时，版本进入 `PENDING_APPROVAL`，审批通过才生效（设计 K）。

回滚 = 把已发布指针指向旧版本；diff = 两个快照的 JSON 差异。发布、停用、回滚都发 `tioka.agent.*.v1` 事件，ToolGateway 和 Chat 缓存据此刷新。

## 共享、克隆与市场（AST-03、AST-04）

- 共享统一走 `sys_resource_acl`：USE 可对话使用，EDIT 可改草稿，OWNER 可发布和授权。跨空间共享默认关闭。
- 克隆 = 把某个版本的快照复制成自己名下的新草稿，发布时重新校验权限。
- 市场与模板共用一张上架表，上架走资产审批。模板只能克隆，不能直接使用。

## Skill（AST-05、AST-06）

| 项 | 定案 |
| --- | --- |
| 制品 | 每个版本是一个不可变 zip，存对象存储，记录 sha256；版本号用 semver，同一包内不可重复 |
| 导入 | zip 上传或 Git 地址（经 EgressClient 拉取）；导入时解析 SKILL.md 头部、校验必填字段、扫描脚本中的高危操作 |
| 签名 | 租户可配「只允许已签名」；签名用租户密钥或官方密钥对 sha256 签名 |
| 权限声明 | 所需工具、网络主机、文件路径。运行时取 Skill 声明与 Agent 沙箱策略的交集 |
| always 模式 | SKILL.md 全文注入系统提示 |
| auto 模式 | 系统提示只列名称和描述；模型需要时调内置工具 `skill.load` 加载全文与附属文件 |
| manual 模式 | 用户在界面上为当前会话勾选启用，启用后按 always 处理 |
| 脚本 | Skill 自带脚本只能在沙箱中执行（设计 M），沙箱未就绪前含脚本的 Skill 不能启用 |

## 新增工作项

| ID | 工作项 | 里程碑 | 优先级 |
| --- | --- | --- | --- |
| AST-01 | AgentSpec 类型化、草稿与版本，绑定表合并进快照 | M1 | P0 |
| AST-02 | 发布校验（引用存在、发布者权限、Skill 权限交集） | M1 | P0 |
| AST-03 | 共享与克隆改走 ACL | M3 | P2 |
| AST-04 | 市场与模板（上架审批） | M4 | P2 |
| AST-05 | Skill 不可变版本、签名、权限声明 | M3 | P1 |
| AST-06 | Skill auto 模式与 `skill.load` 工具 | M3 | P1 |

AST-01 和 AST-02 定为 P0，是因为 G1 验收「选已发布 Agent」就要求快照完整可信。

## 数据模型

```sql
ALTER TABLE agent_agent ADD COLUMN tenant_id VARCHAR(32) NOT NULL;
ALTER TABLE agent_agent ADD COLUMN owner_id VARCHAR(32) NOT NULL;
ALTER TABLE agent_agent ADD COLUMN draft_spec_json TEXT NOT NULL;
ALTER TABLE agent_agent ADD COLUMN draft_revision INT NOT NULL DEFAULT 0;
-- agent_version 重建
CREATE TABLE agent_version (id VARCHAR(32) PRIMARY KEY, agent_id VARCHAR(64) NOT NULL, version INT NOT NULL,
  spec_json TEXT NOT NULL, spec_hash VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL,                     -- PENDING_APPROVAL / PUBLISHED / REJECTED / RETIRED
  published_by VARCHAR(32), published_at TIMESTAMPTZ, UNIQUE (agent_id, version));
-- 废弃：agent_share、agent_sandbox、mcp_grant（并入快照或 sys_resource_acl）
CREATE TABLE asset_listing (id VARCHAR(32) PRIMARY KEY, kind VARCHAR(16) NOT NULL,   -- AGENT / TEMPLATE / SKILL / FLOW
  asset_id VARCHAR(64) NOT NULL, version_id VARCHAR(64) NOT NULL, scope VARCHAR(16) NOT NULL,  -- WORKSPACE / TENANT
  status VARCHAR(16) NOT NULL, listed_by VARCHAR(32), listed_at TIMESTAMPTZ);   -- 取代 agent_marketplace
CREATE TABLE skill_version (id VARCHAR(32) PRIMARY KEY, package_id VARCHAR(64) NOT NULL, semver VARCHAR(32) NOT NULL,
  artifact_ref VARCHAR(512) NOT NULL, sha256 VARCHAR(64) NOT NULL, manifest_json TEXT NOT NULL,
  signature TEXT, signer VARCHAR(64), status VARCHAR(16) NOT NULL, created_by VARCHAR(32),
  created_at TIMESTAMPTZ NOT NULL, UNIQUE (package_id, semver));
```

## 修订：Prompt 模板（2026-11-06）

见 [ADR-041](ADR-041-config-versioning-observability.md) 第 4 节与 ADR-029 第 62 条：

- 新增 Prompt 模板资产（AST-07），有变量定义和版本。`instructions` 的每一段可以是内联文本，也可以引用模板；Agent 发布时把引用钉成固定版本。
- AST-02 发布校验增加一项：模板版本已发布，且变量取值满足定义。
- CHT-06 的提示词库基于个人级、空间级模板实现。Prompt 的发布评测（AST-09）与 EVO-06 共用同一套机制。
- （2026-11-06，ADR-042）`ModelPolicy` 增加 `params`（模型参数覆盖），只能设置候选 binding 声明为可覆盖的参数；AST-02 增加参数合法性检查。

## 修订：Agent 架构调研落地（2026-11-06）

来由：[调研 01](../research/01-Agent与多Agent架构调研.md)、[调研 02](../research/02-Agent执行策略与执行器插件调研.md)；负责人同意按调研建议设计，见 ADR-029 第 66 条。

- **`profile.type` 增加 ROUTER**（[ADR-044](ADR-044-entry-intent-routing.md)），对应新增 `router` 段；TEAM 见 ADR-038。
- **新增 `execution` 段**（[ADR-046](ADR-046-execution-strategy.md)）：执行策略、策略参数、执行器、是否允许运行时覆盖。M3（EXE-01）只允许 `REACT` + `NATIVE`，其余取值发布校验拒绝；M4 开放 `PLAN_EXECUTE`、`REFLECT`；外部执行器（[ADR-047](ADR-047-external-executor.md)）在 M4 之后。
- **ToolSet 增加「常用工具」可选字段**，供工具检索（ADR-016 修订 RT-13）使用。
- **通用助手模板（AST-10，M4 之后）**：平台预置一个 TEAM 型模板 Agent，内置联网检索、文件、代码沙箱、Skill 库与临时子 Agent 模板，作为入口路由的兜底目标；只能克隆后使用。

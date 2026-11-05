# S11 — Agent 资产管理（详细设计）

> 模块：`tioka-module-agent`（建议独立；亦可先落在 system 资产包）  
> 关联：S10 路由、S12 MCP、S13 Skill、S1 Runtime、S9 RBAC、S3 Chat  
> 目标：企业内 **创建 / 版本 / 挂载 / 发布 / 授权 / 观测** Agent 的完整管理面

---

## 1. 定位与边界

**Agent** = 可复用的智能体资产：人设与指令、默认路由、工具集（内置/MCP/Flow/Skill）、沙箱与审批策略、子 Agent、空间权限。

| 边界内 | 边界外 |
| --- | --- |
| Agent 元数据、版本、挂载关系、启停、模板、分享 | Turn 执行循环（S1） |
| 与模型路由/MCP/Skill/Flow 的**绑定配置** | MCP/Skill/Flow 自身协议实现 |
| 管理 API 与配置台 | 纯会话消息存储（S3） |

---

## 2. 完整能力地图

| 能力簇 | 能力项 | 波次 |
| --- | --- | --- |
| **基本档案** | 名称、描述、图标、标签、类型（对话/任务/只读助手） | W1 |
| **指令** | SYSTEM / SOUL / AGENT 多段；变量占位；长度与敏感扫描 | W1 |
| **模型** | 绑定默认 RoutingPlan；可选多场景计划；允许用户覆盖开关 | W1 |
| **工具** | 内置工具开关；MCP Server 勾选+工具级过滤；Flow-as-Tool；业务 Adapter Tool | W1–W2 |
| **Skill** | 绑定 Skill 包；manual/auto/always；冲突优先级 | W2 |
| **沙箱/权限档** | read/edit/full；工作目录；网络策略；审批升级 Orch | W2 |
| **SubAgent** | 可委派的子 Agent 列表与深度限制 | W3 |
| **版本** | draft/published；灰度；回滚；变更 diff | W1–W2 |
| **模板与市场** | 官方/租户模板；复制创建；上架审批 | W3–W4 |
| **分享** | 空间内/跨空间（显式）；只读使用 vs 可克隆 | W3 |
| **启停** | enabled；依赖校验失败禁止启用 | W1 |
| **观测** | 调用量、成本、失败率、绑定路由实际命中 | W2 |
| **RBAC** | agent:read/write/publish/bind；资源 ACL | W1 |

### 2.1 Agent 与「多模型」关系（管理态）

管理界面呈现：

1. **默认智能路由**（必选，生产）：一条已发布 RoutingPlan（内含多 Binding 候选）。  
2. **场景路由**（可选）：编码/写作等额外计划。  
3. **调试：固定 Binding**（仅管理员）：绕过路由直连某部署（强审计）。

禁止：在 Agent 上散落多个互无的「主模型」字段而不进路由计划（易与 S10 冲突）。

---

## 3. 领域模型

| 实体 | 说明 |
| --- | --- |
| **Agent** | 逻辑资产 ID、空间、状态 |
| **AgentVersion** | 不可变发布快照（指令、绑定、策略 JSON） |
| **AgentDraft** | 可编辑草稿 |
| **AgentToolPolicy** | 工具允许列表/拒绝列表 |
| **AgentMcpBinding** | serverId + toolAllowlist |
| **AgentSkillBinding** | skillId + mode |
| **AgentRouteBinding** | planId + scene + isDefault |
| **AgentSandboxPolicy** | 权限档与路径 |
| **AgentTemplate** | 模板来源 |

---

## 4. 组件结构

```
agent-biz
├── catalog/     Agent CRUD、搜索、标签
├── version/     草稿、发布、回滚、diff
├── binding/     route/mcp/skill/flow/tool
├── policy/      sandbox、approval
├── template/    模板与克隆
├── share/       授权
└── validate/    启用前依赖检查（路由存在、MCP 健康等）
```

---

## 5. 生命周期

```
DRAFT → validate → PUBLISHED(enabled)
                 → PUBLISHED(disabled)
新编辑 → 新 DRAFT → PUBLISH → 版本+1
会话创建时锁定 AgentVersion（可配：跟随最新 / 钉死版本）
```

---

## 6. 管理 API

| API | 方法要点 |
| --- | --- |
| AgentApi | create/update/get/list/delete |
| AgentVersionApi | publish、rollback、diff、getSnapshot |
| AgentBindingApi | setRoutes、setMcp、setSkills、setTools |
| AgentPolicyApi | sandbox、approval |
| AgentTemplateApi | list、instantiate |
| AgentValidateApi | dry-run 依赖检查 |

发布请求体需包含完整快照哈希，防止丢绑定。  
**Skill**：快照必须含 `skillBindings`（见 ADR-014 D2）；仅 MCP grants 而无 Skill 绑定不得视为 Skill 能力已交付。

---

## 7. MQ

`tioka-agent`：`agent.published` `agent.disabled` `binding.changed`  
消费者：Chat 缓存、Runtime **ToolGateway** 刷新、观测。

---

## 8. 数据模型

`ag_agent` `ag_version` `ag_draft`  
`ag_route_binding` `ag_mcp_binding` `ag_skill_binding` `ag_tool_policy`  
`ag_sandbox_policy` `ag_share`

---

## 9. 安全

- 发布需 `agent:publish`；绑定 MCP 需同时有该 MCP 的使用权。  
- 快照中不存明文 Key（只存 bindingId）。  
- 跨空间分享默认关。

---

## 10. 依赖

| 依赖 | 用途 |
| --- | --- |
| S10 | RoutingPlan |
| S12 | McpServer |
| S13 | SkillPackage |
| S5/S14 | Flow 发布为 Tool |
| S9 | RBAC/Workspace |
| S1 | 运行时按 Version 装载 |

---

## 11–14. 失败 / 观测 / 测试 / 波次

启用时若默认路由停用 → 拒绝 enable。  
测试：发布快照完整性、无权限不能绑 MCP、会话钉版本不漂。  
波次：W1 档案+路由+发布；W2 MCP/Skill/沙箱；W3 模板分享 SubAgent；W4 市场。  
Skill 执行链与工作盘安装验收：**M-Gap / ADR-014**（纠正「仅 ZIP 入库」缺口）。

# Design: Agent / MCP / Skill 名词对齐（闭环 #4）

- 日期：2026-11-07
- 状态：Accepted（已完成）
- 前置：system、runtime、chat 闭环；策略 B

## 决策

| 项 | 定案 |
| --- | --- |
| Agent / Draft / Version | 配置资产三态；Version = Publication（不可变 Spec） |
| AgentSnapshot | 读模型 API 名保留；≠ 独立表；≠ MCP Server Snapshot |
| McpBinding | Spec `tools.mcp[]`；运行时信封键 **`mcp`**（废止 `mcpGrants`） |
| 双读 | 读侧短暂兼容旧键 `mcpGrants`；写侧只写 `mcp` |
| SkillPackage / SkillVersion | M1：`skillVersionId` ≡ `skill_package.id`；AST-05 再建表 |
| 表前缀 | 冻结 `agent_*` / `mcp_*` / `skill_*`；S 文档 `ag_*`/`sk_*` 作废 |
| 死代码 | 删除指向已 DROP 表的 Sandbox/Share DO·Mapper·Repository；Share API stub 保留 |
| 排除 | TenantContext；`skill_version` / `mcp_server_snapshot` / `asset_listing` 表迁移；FlowPublication 详表（S14） |

## 验收

- 47-GL § Agent/MCP/Skill 就位；禁用表含 `mcpGrants`
- 写路径无 `mcpGrants`；读路径双读
- AgentPublish / ToolGateway / G1Permission 相关 IT 绿

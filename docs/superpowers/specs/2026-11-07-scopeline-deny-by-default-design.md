# Design: ScopeLine deny-by-default（exempt 反转）

- 日期：2026-11-07
- 状态：Accepted（方案 A；负责人确认推进）
- 前置：B1 白名单扩表 + SYSTEM 有 workspace 仍过滤

## 1. 目标

带 `workspace_id` 的表**默认**行级过滤；仅显式 exempt 旁路。漏登无列表 → SQL 失败（偏安全）。

## 2. 行为

`ScopeLineHandler.ignoreTable`：

1. 无 workspace 的 `SYSTEM` → 旁路  
2. 表名 ∈ `workspace-scope-exempt-tables` → 旁路  
3. 其余 → 不过滤忽略（强制加 `workspace_id`）

## 3. 配置

- 废弃 `workspace-scoped-tables`
- 新增 `tioka.persistence.workspace-scope-exempt-tables`
- 默认 = 迁移盘点中**无** `workspace_id` 的表 + 鉴权例外 `sys_member` / `sys_api_key`
- 有列且须过滤：`agent_agent`、`kb_base`、`kb_chunk`、`chat_conversation`、`flow_draft`、`mcp_server`、`skill_package`、`orch_run`、`runtime_thread`、`runtime_approval`、`model_usage`、`audit_event`

## 4. ArchUnit

1. `@TableName` DO 无 `workspaceId`（且非 `WorkspaceAwareDO`）→ 必须在 exempt  
2. 有 `workspaceId` / `WorkspaceAwareDO` → **禁止**在 exempt，**例外** `sys_member`、`sys_api_key`

## 5. 非目标

V132 / V142 / V143；TenantContext；业务补列。

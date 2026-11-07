# Design: Runtime 域名词对齐（闭环 #2）

- 日期：2026-11-07
- 状态：Accepted（执行中）
- 前置：system 试点（`2026-11-07-coding-conventions-glossary-design.md`）；推广策略 B

## 决策

| 项 | 定案 |
| --- | --- |
| 权威模型 | ADR-016：Thread / Turn / Item / Step |
| MQ | 新 tag `thread.*` / `item.*`；旧 `TAG_SESSION_*` / `TAG_PART_*` **@Deprecated** 且 **alias 新字符串**（一步切 tag，保留旧符号名一期） |
| 物理表 | `runtime_session` → **`runtime_thread`**；子表 `session_id` → **`thread_id`**；`chat_conversation.runtime_session_id` → **`runtime_thread_id`** |
| Java | `RuntimeSession*` → `RuntimeThread*`；`SessionLock` → `ThreadLock` |
| 排除 | `TenantContext`；TurnDO→MutableRecordDO 另开 |

## 交付

1. 47-GL § Runtime；S0 摘要与 S1 领域模型改 Thread
2. Flyway `V128__rename_runtime_session_to_thread.sql` + contracts 镜像
3. 代码与 IT 同步；相关 IT 绿

## 验收

- 生产/测试 Java 无新引用旧 MQ 符号（旧符号仅定义处 `@Deprecated`）
- `@TableName` / SQL / 白名单均为 `runtime_thread`
- runtime 核心 IT + 依赖会话表的路径绿

# Design: Chat 域名词对齐（闭环 #3）

- 日期：2026-11-07
- 状态：Accepted（已完成）
- 前置：system、runtime 闭环；策略 B

## 决策

| 项 | 定案 |
| --- | --- |
| Conversation | 产品会话壳；经 `runtime_thread_id` 绑 Thread（当前 1:1；Branch 未落地） |
| Message（产品） | 用户可见对话轮次；持久化映射为 Runtime **Item**；`GET .../messages` 返回 Item 投影 |
| Part | 展示部件枚举（卡片等）；≠ Item |
| Memory stub | `chat_memory` KV 非正式长期记忆；正式记忆见 ADR-050 |
| JSON | Conversation 只暴露 `threadId`；去掉 `runtimeThreadId` 别名 |
| 删除 | 会话主数据用逻辑删（`removeById`） |
| MQ | 保留 `message.*` 作为 Chat 产品事件；Javadoc 标明 ≠ Runtime `item.*` |
| 排除 | 本闭环不实现 Branch/`chat_branch`；不改表名 `chat_conversation` |

## 验收

- 47-GL § Chat 就位；S3 领域摘要指向 47-GL
- 无 Conversation JSON `runtimeThreadId`；删除走逻辑删
- chat 相关 IT 绿

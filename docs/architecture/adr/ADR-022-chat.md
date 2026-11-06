# ADR-022 — Chat 对话

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：CHT-02〜07
- 对应设计：设计 H
- 阅读版：[Tioka 推进规划与详细设计](https://claude.ai/artifact/V3wtabWCVSwAC3ewNerFNi)
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

覆盖 CHT-02〜07 与 S3。核心决定是：**消息内容只存在 Runtime 的 item 里，Chat 只管会话组织和分支关系**。当前 `chat_message` 与 Runtime parts 双份存储的问题由此消除；分叉、编辑、重新生成都归结为 Runtime 的 thread fork。

## 会话、分支与消息

- 一个会话由若干分支组成，每个分支对应一个 Runtime thread，会话记录当前激活的分支。
- **fork 不复制数据**：新 thread 记录 `parent_thread_id` 和 `fork_seq`，读取时拼接父 thread 中 `seq ≤ fork_seq` 的 item 与自身 item。
- **编辑用户消息** = 在该消息之前 fork，再以新文本开 Turn；**重新生成** = 在该回答对应的用户消息之后 fork，重跑同一输入；**继续** = 在同一 thread 上开新 Turn。每次操作都产生新分支，前端按分支树展示「第 2/3 版」。
- **发送**：`POST /api/chat/conversations/{id}/messages`，请求带 `clientMsgId`、文本、附件引用，返回 turnId 与事件流地址（CHT-02）。停止即 `interrupt`。
- **Agent 版本**：会话创建时按 Agent 配置选择「钦死版本」或「跟随最新」，每个 Turn 记录实际使用的版本。

## 附件与文件对话

上传走预签名直传对象存储，前端确认后生成 `file_ref`（仓库 ADR-014 D4）；发送时 Runtime 把文件物化到会话工作盘。图片直接作为多模态输入；文档在 20 页以内抽取文本放入上下文，超过则索引进一个会话专属的隐藏知识库（设计 D 的流水线），由 Agent 用检索工具读取。会话删除时一并清理。

## 长期记忆（CHT-05）

- **抽取**：Turn 完成后异步调一次模型（purpose=memory），从本轮对话中提取值得记住的用户偏好或事实，与已有记忆去重合并。
- **注入**：Turn 开始时按与当前问题的向量相似度取前若干条，放入系统提示的独立段落，上限 1000 token。
- **控制**：用户可以查看、编辑、删除单条记忆，或整体关闭；临时（无痕）会话不抽取也不注入。管理员可按空间关闭记忆功能。

## 其他能力

| 能力 | 定案 |
| --- | --- |
| 搜索 | 会话标题和文本 item 写入搜索表（由 `item.completed` 事件异步维护），用 PostgreSQL 全文索引；只搜本人可见的会话 |
| 文件夹、置顶、归档 | 会话上的属性字段，文件夹是个人级树 |
| 预设与切换模型 | 预设 = Agent + 可选路由计划覆盖；只有 Agent 允许用户覆盖时才能切换（S11 的规则） |
| 分享链接 | 分享的是某个分支到某个 seq 的只读快照；可见范围默认本空间，「知道链接即可看」需租户策略允许；可设过期、可撤销 |
| 提示词库 | 个人和空间两级，支持变量占位；空间级需 `prompt:publish` 权限 |
| 导入导出 | 导出 JSON（含全部分支）和 Markdown（当前分支）；导入只生成只读历史，不重跑 |
| 联网搜索 | 内置工具 `web.search` 与 `web.fetch`，由 Connector 的搜索适配器实现（公有云搜索 API 或私有化 SearXNG），结果带引用 |
| Artifacts | 作为 artifact 类型的 item 存储，同一 artifact 的后续修改记为新版本；产物中心按 item 索引 |
| 代码解释器 | 见设计 M |

## Agents API（CHT-07）

对外提供 OpenAI 兼容的 `POST /v1/chat/completions`，`model` 写 `agent:{agentId}`，用 API Key 鉴权。不带会话 ID 时为无状态调用（临时 thread，结束即归档）；带 `X-Tioka-Conversation` 头时追加到该会话。工具执行在服务端完成，对调用方只返回最终回答（流式时返回文本增量）。

## 数据模型

```sql
ALTER TABLE chat_conversation ADD COLUMN tenant_id VARCHAR(32) NOT NULL;
ALTER TABLE chat_conversation ADD COLUMN workspace_id VARCHAR(64) NOT NULL;
ALTER TABLE chat_conversation ADD COLUMN owner_id VARCHAR(32) NOT NULL;
ALTER TABLE chat_conversation ADD COLUMN agent_id VARCHAR(64) NOT NULL;
ALTER TABLE chat_conversation ADD COLUMN version_policy VARCHAR(8) NOT NULL;  -- PINNED / LATEST
ALTER TABLE chat_conversation ADD COLUMN active_branch_id VARCHAR(32);
ALTER TABLE chat_conversation ADD COLUMN folder_id VARCHAR(32);
ALTER TABLE chat_conversation ADD COLUMN pinned BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE chat_conversation ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE chat_conversation ADD COLUMN temporary BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE chat_conversation ADD COLUMN origin VARCHAR(8) NOT NULL DEFAULT 'web';  -- web / im / api
-- chat_conversation.runtime_session_id 废弃，改由分支表关联 thread；chat_message 废弃
CREATE TABLE chat_branch (id VARCHAR(32) PRIMARY KEY, conversation_id VARCHAR(64) NOT NULL, thread_id VARCHAR(64) NOT NULL,
  parent_branch_id VARCHAR(32), fork_seq BIGINT, created_at TIMESTAMPTZ NOT NULL);
CREATE TABLE chat_turn_ref (turn_id VARCHAR(32) PRIMARY KEY, conversation_id VARCHAR(64) NOT NULL,
  branch_id VARCHAR(32) NOT NULL, agent_version_id VARCHAR(64), feedback VARCHAR(8), feedback_note TEXT);
CREATE TABLE chat_memory_item (id VARCHAR(32) PRIMARY KEY, tenant_id VARCHAR(32) NOT NULL, user_id VARCHAR(32) NOT NULL,
  content TEXT NOT NULL, embedding VECTOR(1024), source_turn_id VARCHAR(32), enabled BOOLEAN NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL);                 -- 取代 chat_memory
CREATE TABLE chat_share (id VARCHAR(32) PRIMARY KEY, conversation_id VARCHAR(64) NOT NULL, branch_id VARCHAR(32) NOT NULL,
  upto_seq BIGINT NOT NULL, token_hash VARCHAR(128) NOT NULL, visibility VARCHAR(16) NOT NULL,
  expires_at TIMESTAMPTZ, revoked BOOLEAN NOT NULL DEFAULT FALSE, created_by VARCHAR(32));
CREATE TABLE chat_prompt (id VARCHAR(32) PRIMARY KEY, workspace_id VARCHAR(64), owner_id VARCHAR(32) NOT NULL,
  scope VARCHAR(16) NOT NULL, title VARCHAR(255) NOT NULL, body TEXT NOT NULL, variables_json TEXT);
CREATE TABLE chat_folder (id VARCHAR(32) PRIMARY KEY, owner_id VARCHAR(32) NOT NULL, parent_id VARCHAR(32), name VARCHAR(255));
ALTER TABLE runtime_session ADD COLUMN parent_thread_id VARCHAR(64);   -- 配合 fork（RT 侧）
ALTER TABLE runtime_session ADD COLUMN fork_seq BIGINT;
```

## 修订：Agent 架构调研落地（2026-11-06）

来由：[调研 01](../research/01-Agent与多Agent架构调研.md)、[调研 02](../research/02-Agent执行策略与执行器插件调研.md)；负责人同意按调研建议设计，见 ADR-029 第 67 条。

- **入口路由**：会话可选择 ROUTER 型 Agent（[ADR-044](ADR-044-entry-intent-routing.md)）。会话表增加「当前目标」与「粘滞到期时间」；交接只在 Turn 边界发生，每个 Turn 记录实际执行的 Agent 版本（与上文「Agent 版本」规则一致）。用户在会话头部手动改选目标时记为路由负反馈。

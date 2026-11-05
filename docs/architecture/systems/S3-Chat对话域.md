# S3 — Chat 对话域（完整能力）

> 对标：LibreChat 全功能面（会话壳 + Agent 产品）  
> Runtime 能力由 S1 提供；本域拥有对话产品语义与持久化。

---

## 1. 定位与边界

对话领域后端 + Agent 配置消费。负责会话体验数据、Part、附件、分支、记忆索引、Agent 选用；执行仍调 S1。

---

## 2. 完整能力地图

| 能力簇 | 具体能力 | 对标 LibreChat 等 | 波次 |
| --- | --- | --- | --- |
| **会话** | 多会话、搜索、置顶、归档、文件夹/项目空间 | 侧栏会话 | W1 |
| **消息** | 流式、编辑、重新生成、继续、停止 | 消息操作 | W1 |
| **分支** | fork、分支树、对比、回滚到消息点 | fork/branch | W2 |
| **模型** | 多端点、多模型、预设 Preset、中途切换 | endpoints/presets | W1–W2 |
| **Agent** | 自定义 Agent、指令、工具集、文件、分享、市场 | Agents | W2–W4 |
| **Skills** | 绑定/启用 Skill 包 | Skills | W2–W3 |
| **MCP** | 会话/Agent 级 MCP 工具 | MCP | W2 |
| **工具 UI** | 工具轨迹、权限提示、批准按钮 | tool activity | W1–W2 |
| **代码解释器** | 跑码、产物下载、会话 workspace | code interpreter | W3–W5 |
| **联网搜索** | 搜索+抓取+重排引用 | web search | W3 |
| **多模态** | 图片理解、语音输入转写、文件解析后对话 | multimodal/files | W2–W3 |
| **Artifacts** | 代码/HTML/图表产物预览与迭代 | artifacts | W3–W5 |
| **交互卡片** | 单选/多选/表单/向导/表格勾选 | Generative UI | W2 |
| **审批卡** | 高风险挂起，跳转 Orch | autoloop ask | W2 |
| **记忆** | 用户长期记忆、偏好、可开关与遗忘 | memory | W4–W5 |
| **提示词库** | 提示词分享、团队库 | prompts | W3 |
| **分享** | 只读链接、权限、过期 | share | W3–W4 |
| **导入导出** | 会话导出 JSON/Markdown | — | W3 |
| **SSO/多租户** | 企业登录、空间隔离 | auth | W1–W2 |
| **临时聊** | 无痕模式 | — | W4 |

---

## 3–14. 结构摘要

领域：Conversation、Message、Part、Card、Attachment、Preset、AgentBinding、MemoryItem、ShareLink、Artifact。  
组件：conversation/message/card/agentbind/memory/share/artifact/bridge。  
API：Conversation/Message/Card/Preset/Agent/Memory/Share/Artifact/Stream。  
MQ：`tioka-chat` 覆盖会话消息卡片产物记忆。  

| 波次 | 内容 |
| --- | --- |
| W1 | 会话流式模型预设基础 Part |
| W2 | Agent/MCP/Skills/双卡片/分支/多模态输入 |
| W3 | 搜索/解释器起步/Artifacts/提示词/导出 |
| W4–W5 | 记忆/市场/分享完善/无痕 |
| **M-Gap** | 预签上传 + confirm + `file_ref` 物化管道（ADR-014 D4；与 S1 WorkspaceFs 联动） |

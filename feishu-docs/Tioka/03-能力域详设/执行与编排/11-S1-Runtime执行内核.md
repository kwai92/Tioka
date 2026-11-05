# S1 — Runtime 执行内核（完整能力）

> 模块：`tioka-module-runtime`  
> 对标：Codex harness / DeepSeek harness / Agents 运行时能力并集  
> **范围声明**：下列能力均为产品范围；W 波次仅排序。

---

## 1. 定位与边界

唯一「模型 ↔ 工具 ↔ 策略」执行引擎。Chat/Flow/Orch/Knowledge-Agent 模式全部复用本内核。  
不负责业务路由（S2）、UI（S7）、出网 SDK（S6）。

---

## 2. 完整能力地图

### 2.1 对标能力清单

| 能力簇 | 具体能力 | 对标来源 | 波次 |
| --- | --- | --- | --- |
| **模型执行** | 按 S10 返回的 modelRef 调用；流式/非流式；vision/audio；结构化输出；thinking 通道 | harness LLM + **S10 路由** | W1–W3 |
| **禁止** | 业务域或 Runtime 私连供应商绕过 S10（调试开关除外且审计） | HiRoute 统一入口思想 | W1 |
| **Loop** | Turn/Step；串行/有限并行 tool；取消；Steer；自动续写；失败重试策略 | Codex/dsh | W1 |
| **会话** | 创建/恢复/fork/归档；跨进程持久化；只读回放；会话标题；分支对比 | harness session | W1–W2 |
| **上下文** | 分段 system prompt；动态注入；压缩/摘要；附件 offload；窗口策略可配 | Agents API compaction | W2–W5 |
| **工具** | JSON Schema 工具；并行调用；结果截断；幂等键；工具权限档 | harness tools | W1 |
| **MCP** | 多 Server；工具过滤；OAuth/头认证；资源/prompts（若协议需要） | MCP + LibreChat | W2–W3 |
| **Skills** | SKILL.md 包；手动/自动/always；热加载；权限声明 | LibreChat/dsh skills | W2–W3 |
| **沙箱** | 命令/文件沙箱；工作区隔离；网络策略；Docker/微虚机后端可插拔 | AgentRun/dsh sandbox | W2–W5 |
| **代码执行** | 多语言解释器；上传下载产物；会话级 workspace | LibreChat code interpreter | W3–W5 |
| **审批策略** | 工具级 ask；路径白名单；一次性授权；升级到 Orch Attention | Codex approval + autoloop | W2 |
| **SubAgent** | 子 Agent 委派；隔离上下文；结果回收；深度限制 | harness subagent | W3–W5 |
| **Agent Team** | 任务板、角色、协作策略 | dsh Agent Teams | W5 |
| **后台 Job** | 长命令转后台；通知完成；可查询/停止 | dsh jobs | W3 |
| **终端/PTY** | 交互终端工具（策略控制） | harness terminals | W5 |
| **自动化入口** | 无头执行、SDK、ACP/JSON-RPC、CI 调用 | dsh profiles / Codex exec | W2–W4 |
| **用量** | token/cached/cost；按租户/Agent 聚合 | autoloop usage | W1–W2 |
| **扩展 SPI** | LlmProvider / ToolProvider / SandboxProvider / Compactor | dsh seams | W1 |

### 2.2 明确仍属范围但不做「外购运行时」

不嵌入 Codex CLI / dsh 进程；用自研实现上述能力面。

---

## 3. 领域模型

Session、Turn、Step、SessionEvent、ToolDescriptor、ToolCall、AgentPreset、SkillPackage、SubAgentRef、SandboxSession、UsageRecord、ApprovalDecision。

---

## 4. 组件与包结构

```
runtime-biz
├── session/ loop/ llm/ prompt/
├── tools/ mcp/ skills/
├── policy/ sandbox/ interpreter/
├── subagent/ team/ jobs/ terminal/
├── compact/ usage/
└── spi/  (Llm/Tool/Sandbox/Compactor/AutomationGateway)
```

---

## 5. 状态机

Session：`CREATED→RUNNING↔WAITING_TOOL↔WAITING_APPROVAL→COMPLETED|CANCELLED|FAILED`  
SubAgent：独立 Session，父 Turn 等待 `agent_handoff` 结果。  
Sandbox：按 Session 或 ToolCall 生命周期创建销毁。

---

## 6. API 契约（完整面）

| API | 能力 |
| --- | --- |
| RuntimeSessionApi | CRUD、append、steer、cancel、fork、resume |
| RuntimeStreamApi | SSE/WS：delta、tool、approval、usage |
| RuntimeToolApi | list/invoke（管理面调试） |
| RuntimeSkillApi | install/enable/list |
| RuntimeMcpApi | bind server、refresh tools |
| RuntimeSandboxApi | 策略查询、强制销毁 |
| RuntimeSubAgentApi | 列表/取消子任务 |
| RuntimeAutomationApi | 无头 run、ACP bridge |

---

## 7. MQ

`tioka-runtime`：`session.*` `turn.*` `part.*` `tool.*` `approval.*` `subagent.*` `usage.reported` `sandbox.*`

---

## 8. 数据模型

`rt_session` `rt_event` `rt_tool_call` `rt_usage` `rt_skill` `rt_mcp_binding` `rt_sandbox` `rt_subagent` `rt_approval`

事件日志可重建投影；大附件外置对象存储。

---

## 9. 安全

租户隔离；工具权限档；密钥不进 prompt；沙箱网络默认拒绝；审批审计；输出脱敏钩子。  
**凭证与工具入口**：执行面经 **ToolGateway** 调工具；`SecretStore.get` 白名单与 CredentialBroker 见 **ADR-014 D3/D5**。

---

## 9.1 会话工作盘与文件多模态（ADR-014）

- 每 Session 隔离 **POSIX Workdisk**（`uploads/` `skills/` `scratch/` `artifacts/out/`）；OSS 仅交换与 `publish_artifact`。  
- SPI：`WorkspaceFs`；Skill 由 SkillLoader 只读安装；附件默认 `file_ref` 路径入上下文（禁止默认 Base64 入模）。  
- 权威：`ADR-014` + `docs/superpowers/specs/2026-11-05-agentcore-alignment-gap-design.md`；验收里程碑 **M-Gap**。

---

## 10. 依赖

被 Chat/Flow/Orch 调用；通过 SPI 使用 Knowledge/Flow/MCP/Sandbox 实现；禁止依赖 connector-biz。  
工具经 ToolGateway；文件经 WorkspaceFs；禁止 Runtime 直连 SecretStore.get（ADR-014）。

---

## 11. 失败与幂等

append/clientMsgId 幂等；工具超时写失败结果；审批超时策略可配；子 Agent 失败上抛父 Turn。

---

## 12. 可观测

Turn/Tool/LLM span；token 成本；沙箱生命周期；SubAgent 树可视化数据。

---

## 13. 测试

契约：多模型 mock、工具并行、MCP、审批、fork、压缩、子 Agent、解释器、取消泄漏检测。

---

## 14. 交付波次

| 波次 | 交付本域能力 |
| --- | --- |
| W1 | Loop+会话+工具+流式+用量+SPI |
| W2 | MCP+Skills+审批+自动化入口+压缩起步 |
| W3 | 解释器+SubAgent+Job+多模态 |
| W5 | Team+强沙箱+PTY+高级压缩 |
| **M-Gap** | WorkspaceFs + Skill 安装注入 + ToolGateway 收口 + 文件多模态管道 + SecretStore ArchUnit（ADR-014；可与 W 波次穿插） |

# ADR-014 — AgentCore 对齐补强包（工作盘 / Skill / ToolGateway / 多模态 / 凭证）

- **状态**：Accepted（2026-11-05）
- **日期**：2026-11-05
- **触发**：对照《AgentCore + Codex 构建生产级 Agent》与 Tioka 设计/实现核对
- **选型确认**：① 工作盘方案 **A**（POSIX Session Workdisk + OSS 交换/产物）；③ 工具面方案 **C**（ToolGateway 语义网关）
- **关联详设**：S1、S3、S9、S11、S12、S13；TA/DA；spec `docs/superpowers/specs/2026-11-05-agentcore-alignment-gap-design.md`
- **实施里程碑**：M-Gap（见 `implementation/31-W0至W5实施计划.md` 附录）

---

## 1. 背景

文章揭示的生产级硬模式与 Tioka 现状存在系统性缺口：

| 硬模式 | Tioka 设计 | Tioka 实现（核对时） |
| --- | --- | --- |
| OSS 交换 + POSIX 会话工作盘 | 仅 OSS/MinIO 叙事 | 无 Session 工作盘；产物多为 stub |
| Skill ≠ MCP；工作区映射 + 渐进读 | S13 同向 | ZIP 入库；AgentVersion 无 Skill；Runtime 不注入 |
| 模型只连单一受控工具面 | ToolRegistry SPI | 进程内聚合够用；无 Gateway 语义 |
| 文件驱动多模态 | Part 有 file_ref | 无 upload→物化→路径入上下文管道 |
| 凭证只留控制面 | SecretStore + ref | 同 JVM 执行面可 `get`；无 lease/代签 |

本 ADR **一次性收口五项决策**，避免散落补丁互相打架。不改变 ADR-001（自研 Runtime，不嵌入 Codex CLI）。

---

## 2. 决策总览

| # | 主题 | 决议 |
| --- | --- | --- |
| D1 | 存储分层 | **方案 A**：Session Workdisk（POSIX）+ OSS/MinIO 对象交换与产物发布 |
| D2 | Skill 执行链 | AgentVersion 快照绑定 → 工作区只读安装 → 索引/always 注入 → 按需读 SKILL.md；**禁止**当 MCP Tool |
| D3 | 工具面 | **方案 C**：统一 **ToolGateway**（list/call + grant + HITL + 审计）；MCP Aggregator 协议出口可后置 |
| D4 | 多模态 | **文件驱动**为主路径；禁止 Base64/内嵌二进制作默认入模方式 |
| D5 | 凭证边界 | 明文密钥仅允许白名单组件解密；演进 CredentialBroker（lease / 代签） |

**依赖序（强制）**：D1 → D2 / D4 → D3 深化 → D5 加固（D5 的 ArchUnit 白名单可与 D1 并行起步）。

---

## 3. D1 — Session Workdisk + OSS（方案 A）

### 3.1 决策

1. 每个 Runtime **Session**（可细到 Run）拥有隔离工作盘根：  
   `/{workdirRoot}/{workspaceId}/{sessionId}/`（私有化可换 NFS/PVC/客户共享盘；默认本地目录）。
2. **逻辑路径**（Runtime / Skill / 工具只认相对路径，禁止绝对路径出沙箱）：  
   - `uploads/` — 用户附件物化  
   - `skills/` — 已安装 Skill 包（只读）  
   - `scratch/` — Agent 过程文件  
   - `artifacts/out/` — 待发布产物
3. **OSS/MinIO**（经 S9 `ObjectStorageAdapter`）仅用于：  
   - 上传交换（预签 URL）  
   - Skill 制品持久化  
   - `publish_artifact` 后的对外交付与下载票证  
   **禁止**将 OSS FUSE/对象存储直接当作 Agent 工作目录。
4. 新增 SPI：`WorkspaceFs`（resolve / materializeFromObject / publishArtifact / gc），归属 foundation/runtime-api；实现可替换。

### 3.2 后果

- DA 增加「操作工作盘 vs 对象内容」分层说明。  
- 多副本部署需会话粘性或共享盘；compose 默认单节点本地盘即可。  
- 必须有路径穿越防护与 TTL GC。

### 3.3 非目标（本 ADR）

- 不绑定阿里云 AgenticFS/NAS 专有 API。  
- 不做完整容器级微虚机沙箱（仍按 S1 波次）。

---

## 4. D2 — Skill 执行链

### 4.1 决策

1. `AgentVersion` 发布快照必须包含 `skillBindings[]`：`skillVersionId` + `mode(manual|auto|always)`。  
2. Session 创建（或首次 Turn）时，按快照将已发布 Skill 制品**只读安装**到 `skills/<name>/`（物理路径由 WorkspaceFs 映射，Skill 内只用相对路径）。  
3. Runtime 组装提示词：  
   - `always` → 注入 system 段（可截断，完整内容仍落盘）  
   - `auto` → 仅注入名称+描述索引，由模型经文件读工具按需打开 `SKILL.md`  
   - `manual` → 用户/HCI 点选后才安装或可见  
4. Skill **不得**注册为 MCP Tool；不得因 Skill 扩大 Agent 工具/网络/FS 权限（与 SandboxPolicy **求交**）。  
5. 管理面已有 ZIP/`SKILL.md` 校验保留；签名强制仍按 S13 波次，本包不强制上线。

### 4.2 后果

- 纠正「计划勾选已完成但代码无绑定」的偏差：以本 ADR 为验收权威。  
- S11 绑定 API、S13 加载语义、S1 prompt 组装必须联动改。

### 4.3 非目标

- Skill 市场、热更新跟随策略的完整企业流程（沿用 S13 波次）。

---

## 5. D3 — ToolGateway（方案 C）

### 5.1 决策

1. 引入 **ToolGateway** 作为执行面唯一工具入口：  
   - `listTools(agentVersionId|sessionId)`  
   - `callTool(..., args)` → 结果或 `WAITING_APPROVAL`  
2. Gateway 职责：Grant/allowlist 快照、重名策略、HITL 升级 Orch Attention、调用审计、结果截断。  
3. 现有 `ToolProvider`（builtin / MCP / KB / …）降为 Gateway **后端**；`RuntimeSessionService` 与未来外部 Harness **不得**绕过 Gateway 直调 Provider。  
4. **不**在本包强制实现「每 Session 对外 MCP Aggregator HTTP Server」；若未来外挂 Codex CLI，再在 Gateway 上增加 MCP 协议适配出口（另开 ADR 或本包修订）。

### 5.2 后果

- 工具鉴权语义集中，避免 LLM loop 与 MCP 管理面各写一套。  
- ArchUnit/契约测试应断言 Runtime 只依赖 Gateway API。

### 5.3 非目标

- 完整 MCP Server 出口（S12 W4）与本包解耦。

---

## 6. D4 — 文件驱动多模态

### 6.1 决策

1. 默认管道：  
   前端申请预签 → 直传 OSS → 服务端校验大小/类型/ETag/归属 → WorkspaceFs 物化到 `uploads/<uploadId>/` → 写入 `Part(file_ref)`（路径引用）→ Agent 用 vision 模型或读文件/`view_image` 类工具理解。  
2. **禁止**将附件 Base64/二进制默认塞进模型消息；原生多模态 Content Block 仅作可选加速，且仍须完成归属校验与物化（可并行保留路径引用）。  
3. 产出经 `publish_artifact` → OSS + `Part(artifact)` + 短期下载票证；AG-UI 非必需，继续用现有 Part SSE。

### 6.2 后果

- Chat/Infra 增加 uploads API；S3 附件与 S1 工作盘打通。  
- S10 模型能力标签（vision）与工具选择联动。

---

## 7. D5 — 凭证边界

### 7.1 决策

1. **硬规则**：密钥明文不得写入 prompt、Part/事件正文、工作盘、普通日志、沙箱环境变量。  
2. **现阶段（单体）**：`SecretStore.get` 仅允许白名单包调用：  
   - Model `LlmClient` 实现  
   - MCP Transport  
   - ObjectStorage / 预签签发  
   以 ArchUnit 强制；其余模块只持有 `secretRef` / 未来 `leaseId`。  
3. **演进**：引入 `CredentialBroker`——路由/选模返回 `leaseId`；出站由 Broker 换票或代理 HTTP；执行 Worker（若拆分）**不**注入 SecretStore。  
4. 不强制实现云厂商 AgentIdentity / STS；私有化以 SecretStore + 短 TTL lease 等价满足「最小权限、可轮转、可审计（绑定 sessionId/runId）」。

### 7.2 后果

- 现有 `ModelSelection.apiKeyRef` 传给 Runtime 的模式需收敛为「Runtime 调 LlmClient，由 Client/Broker 解密」。  
- 拆进程前完成 Broker，避免密钥随 Worker 扩散。

---

## 8. 验收（M-Gap 最小闭环）

| ID | 验收标准 |
| --- | --- |
| G-FS | 同 Session 两轮 append，第二轮能读到第一轮写入的 `scratch/` 文件 |
| G-SK | 发布含 Skill 的 AgentVersion → 会话 `skills/<name>/SKILL.md` 可读；auto 模式 prompt 仅含索引 |
| G-UP | 上传图片 → 物化 → `file_ref` Part → 问答；token/日志无 Base64 附件体 |
| G-TG | 未授权 MCP 工具经 Gateway 不可见/不可调；越权拒绝可审计 |
| G-SEC | ArchUnit：非白名单包调用 `SecretStore.get` 失败 |

**本里程碑明确不做**：完整 MCP Aggregator 协议出口、云 STS、真容器沙箱、Skill 市场强制签名。

---

## 9. 文档与代码落地清单

| 产物 | 动作 |
| --- | --- |
| 本 ADR | 决策权威 |
| `docs/superpowers/specs/2026-11-05-agentcore-alignment-gap-design.md` | 接口/目录/时序详设 |
| `feishu-docs/.../04-决策记录.md` | 摘要索引 |
| S1 / S9 / S11 / S13 / DA / TA | 交叉引用本 ADR |
| `31-W0至W5实施计划.md` 附录 M-Gap | 任务级 backlog |

---

## 10. 状态与修订

- Accepted：五项决议与依赖序生效。  
- 若改为「纯 OSS 工作盘」或「强制每 Session MCP Aggregator」，须 **修订本 ADR** 并重评 M-Gap 验收，不得静默改实现。

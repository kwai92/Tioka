# Tioka × AgentCore 对齐补强 — 设计说明

> 日期：2026-11-05  
> 状态：Accepted（随 ADR-014）  
> 权威决策：[ADR-014](../../architecture/adr/ADR-014-agentcore-alignment-package.md)  
> 选型：工作盘 **A**；工具面 **C（ToolGateway）**

本文把 ADR-014 五项落到可实现的接口、目录约定与时序；**不写业务代码**。实现以 M-Gap 验收为准。

---

## 1. 目标与非目标

### 目标

1. Session 级 POSIX 工作盘 + OSS 交换/产物分层。  
2. Skill 从「只入库」到「可安装、可注入、可渐进读」。  
3. 工具调用统一经 ToolGateway。  
4. 附件默认走文件驱动多模态管道。  
5. 密钥解密面收敛 + CredentialBroker 演进路径。

### 非目标

- 嵌入 Codex CLI / AgentCore 云运行时（ADR-001）。  
- 阿里云 AgenticFS/AgentIdentity/STS 专有绑定。  
- 本阶段完整对外 MCP Aggregator Server、容器微虚机沙箱、Skill 市场强制签名。

---

## 2. 组件关系

```
UI / Chat
  │ 预签上传、SSE Part
  ▼
Chat/Infra ──ObjectStorage──► OSS/MinIO（交换层）
  │
  ▼
Runtime Session
  ├── WorkspaceFs ──► Session Workdisk（POSIX）
  ├── SkillLoader ──► skills/ 只读安装 + prompt 索引
  ├── ToolGateway ──► ToolProvider…（MCP/KB/builtin…）
  │                      └── HITL → Orch Attention
  └── LlmClient ◄── CredentialBroker / SecretStore（白名单）
```

---

## 3. D1 — WorkspaceFs

### 3.1 SPI（建议落在 `runtime-api` 或 `system-api`）

```text
WorkspaceFs
  Path root(sessionId)
  Path resolve(sessionId, logicalPath) throws PathEscape
  void ensureLayout(sessionId)          // uploads|skills|scratch|artifacts/out
  void materializeFromObject(sessionId, objectKey, logicalDest)
  PublishedArtifact publishArtifact(sessionId, logicalSrc, contentType)
  void gc(sessionId, policy)
```

### 3.2 目录约定

```text
{workdirRoot}/{workspaceId}/{sessionId}/
  uploads/<uploadId>/<filename>
  skills/<skillName>/SKILL.md
  skills/<skillName>/...
  scratch/...
  artifacts/out/<name>
```

- `resolve` 必须规范化并拒绝 `..` 逃逸。  
- Skill 目录默认只读（OS 权限或 Gateway 文件工具策略）。  
- 配置项：`tioka.workspace.root`（默认 `./data/workspaces`）；生产可指 PVC。

### 3.3 与 OSS 分工

| 数据 | 存储 |
| --- | --- |
| 用户上传原稿 | OSS + DB 元数据（attachment） |
| 会话过程文件 | Workdisk |
| Skill 不可变制品 | OSS（或本地制品仓）+ DB |
| 对外产物 | publish 后 OSS + Part(artifact) |

### 3.4 时序：附件物化

```text
Client → PresignApi → OSS PUT
Client → ConfirmUpload(etag, conversationId)
Chat/Runtime → WorkspaceFs.materialize → uploads/...
Runtime → append Part(file_ref={logicalPath})
```

---

## 4. D2 — Skill 执行链

### 4.1 数据

`AgentVersion` 快照增加：

```json
"skillBindings": [
  { "skillVersionId": "skv_...", "name": "pptx", "mode": "auto" }
]
```

`skill_package` / `skill_version` 保持制品哈希与存储位置；安装时按 version 钉死。

### 4.2 SkillLoader（runtime-biz）

1. `installForSession(sessionId, AgentVersionView)`  
2. 从制品存储解压/拷贝到 `skills/<name>/`  
3. `buildPromptFragments(mode)` → always 正文片段 / auto 索引行  

### 4.3 与工具关系

- 不向 ToolGateway 注册「skill_xxx」工具。  
- 使用既有或新增 **受控文件读** 工具读取 `skills/**`（路径仍经 WorkspaceFs.resolve）。  
- Skill manifest 声明的权限 ⊆ AgentSandboxPolicy。

---

## 5. D3 — ToolGateway

### 5.1 API（runtime-api）

```text
ToolGateway
  List<ToolDescriptor> list(SessionToolContext ctx)
  ToolResult call(SessionToolContext ctx, name, args)
```

`SessionToolContext`：`sessionId`, `agentVersionId`, `workspaceId`, `principalId`。

### 5.2 调用管线

```text
list/call
  → 加载 AgentVersion 工具/MCP Grant 快照
  → allowlist / denylist
  → （可选）SandboxPolicy.assertAllowed
  → 若需审批 → Orch.openAttention → WAITING_APPROVAL
  → 路由到 ToolProvider.invoke
  → 审计 + 截断结果
```

### 5.3 迁移

1. 将 `RuntimeSessionService` 中 `toolRegistry.*` 替换为 `toolGateway.*`。  
2. `ToolRegistry` 可内化为 Gateway 默认实现的 Provider 聚合器，对外不再作为权威入口。  
3. 未来 MCP Aggregator：在 Gateway 上增加 `asMcpServer()` 适配，不改 Provider。

---

## 6. D4 — 多模态管道

### 6.1 API

| API | 说明 |
| --- | --- |
| `POST /api/uploads/presign` | 返回短期 PUT URL + uploadId |
| `POST /api/uploads/{id}/confirm` | 校验 etag/size/mime/归属 |
| Runtime/Chat | confirm 后 materialize + `file_ref` Part |

### 6.2 Part 约定

```json
{
  "type": "file_ref",
  "text": "",
  "attrs": {
    "uploadId": "up_...",
    "logicalPath": "uploads/up_xxx/shot.png",
    "mime": "image/png",
    "bytes": 12345
  }
}
```

模型侧：路径 + 简短说明；理解靠 vision binding 或 `view_image` 工具（读 Workdisk 文件）。

### 6.3 产出

`publish_artifact(logicalSrc)` → OSS objectKey + 下载票证 TTL → `Part(artifact)`。

---

## 7. D5 — 凭证

### 7.1 阶段 0（与 M-Gap 同步）

ArchUnit：仅下列包可依赖 `SecretStore.get`：

- `..model..` 的 LlmClient 实现  
- `..mcp..` 的 Transport  
- `..infra..` / `..system..` 的 ObjectStorage 预签  

Runtime、Chat、Agent、Skill **禁止** `get`。

### 7.2 阶段 1 — CredentialBroker

```text
CredentialBroker
  LeaseId issue(scope, sessionId, ttl)
  // 仅出站组件：
  String resolve(LeaseId)          // 短时
  HttpResponse proxy(LeaseId, req) // 可选代签
```

`ModelRouter.select` 返回 `leaseId`（或由 LlmClient 内部 issue），**不再**把可解析 ref 散落到 Session 状态机之外的日志。

---

## 8. 失败与安全

| 场景 | 行为 |
| --- | --- |
| 路径逃逸 | 400 + 审计 |
| 附件非本用户/会话 | 403 |
| Skill 未发布/签名策略拒绝 | Session 创建失败或跳过并告警（按策略） |
| 工具未授权 | Gateway 返回不可见或调用拒绝 |
| 工作盘满 / GC | 配额拒绝新 materialize；可配清理 |

---

## 9. 测试矩阵（契约级）

| 用例 | 期望 |
| --- | --- |
| 工作盘跨 Turn 复用 | scratch 文件仍在 |
| Skill auto | prompt 无全文；读 skills/.../SKILL.md 成功 |
| Skill 越权声明 | 被 Sandbox 求交拒绝 |
| 上传图问答 | 无 Base64 入 LLM mock 请求体 |
| 未 Grant MCP | list 不含该工具 |
| ArchUnit SecretStore | 违规包编译/测试失败 |

---

## 10. 实施切片建议（对应 31 附录）

1. WorkspaceFs 本地实现 + ensureLayout + 路径测试  
2. uploads presign/confirm + materialize + file_ref  
3. AgentVersion.skillBindings + SkillLoader + prompt  
4. ToolGateway 包裹现有 Registry + 授权测试  
5. SecretStore ArchUnit 白名单  
6. （可选后续）CredentialBroker；MCP Aggregator 出口另议  

---

## 11. 修订

与 ADR-014 冲突时以 ADR 为准；接口签名可在实现 PR 中微调，但分层与禁止项不可无 ADR 修订而削弱。

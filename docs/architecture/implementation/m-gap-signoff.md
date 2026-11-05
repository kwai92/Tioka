# M-Gap 验收签字（ADR-014）

> 日期：2026-11-05  
> 关联：[ADR-014](../adr/ADR-014-agentcore-alignment-package.md) · [31 附录 A0](31-W0至W5实施计划.md)

## 验收项

| ID | 标准 | 证据 | 状态 |
| --- | --- | --- | --- |
| G-FS | 同 Session 两轮可复用 `scratch/` | `LocalWorkspaceFsTest` + `WorkspaceSessionScratchTest` | PASS |
| G-SK | Skill 安装到工作盘；auto 仅索引 | `SkillInstallServiceTest`；AgentVersion.`skillBindings` | PASS |
| G-UP | 上传物化 `file_ref`；无 Base64 入 Part attrs | `UploadServiceTest`；`/api/uploads/*` | PASS |
| G-TG | 未授权工具拒绝；经 ToolGateway | `DefaultToolGatewayTest`；Runtime 改调 Gateway | PASS |
| G-SEC | `SecretStore.get` ArchUnit 白名单 | `SecretStoreArchTest` | PASS |

## 本里程碑明确未做

完整 MCP Aggregator HTTP 出口、云 STS/AgentIdentity、真容器沙箱、Skill 市场强制签名、CredentialBroker 全量。

## 签字

| 角色 | 结果 | 日期 |
| --- | --- | --- |
| 工程 | **SLICE SIGNED**（单测 + GateRegression 绿） | 2026-11-05 |
| 产品 Exit | _可选后续人工走查上传/Skill 演示_ | — |

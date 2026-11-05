# Tioka 架构文档（仓库镜像）

与飞书 / `feishu-docs/Tioka` **同构**。权威源以 `feishu-docs/Tioka/` 为准，改完后 push 飞书并同步本目录。

## 层级

| 层 | 飞书/本地 | 本镜像 |
| --- | --- | --- |
| 管理文档 | `01-管理文档/` | （一般只留在 feishu-docs；关键 ADR 可摘） |
| 企业架构 | `02-企业架构/` | [enterprise/](enterprise/) |
| 能力域详设 | `03-能力域详设/` | [systems/](systems/)（可按子组查阅） |
| 实施交付 | `04-实施交付/` | [implementation/](implementation/) |

另见：[module-dependencies.md](module-dependencies.md) · [feishu-doc-sync.md](feishu-doc-sync.md) · [adr/](adr/)

### 近期 ADR

| ADR | 主题 |
| --- | --- |
| [ADR-014](adr/ADR-014-agentcore-alignment-package.md) | AgentCore 对齐补强包（工作盘 A / ToolGateway C / Skill / 多模态 / 凭证） |

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

ADR-015〜035 是 M0〜M4 的权威设计（ADR-032〜035 为可信放权，见下表），推进计划见 [34-M0至M4推进规划与工作项](implementation/34-M0至M4推进规划与工作项.md)，契约见 [docs/contracts](../contracts/README.md)。M0〜M2 的施工设计（逐工作项的改动清单、关键代码、迁移、接口、测试与完成标准）见 [construction/00-施工总纲](construction/00-施工总纲.md)。

| ADR | 主题 |
| --- | --- |
| [ADR-014](adr/ADR-014-agentcore-alignment-package.md) | AgentCore 对齐补强包（工作盘 A / ToolGateway C / Skill / 多模态 / 凭证） |
| [ADR-015](adr/ADR-015-identity-tenant-rbac.md) | 身份、租户与权限（覆盖 FND-01〜05、FND-07、GOV-01、CHT-01） |
| [ADR-016](adr/ADR-016-runtime-kernel.md) | Runtime 内核（thread / turn / item）（覆盖 RT-01〜08、HCI-02） |
| [ADR-017](adr/ADR-017-model-gateway-quota-audit.md) | 模型调用收口、配额与审计（覆盖 MH-01、MH-02、MH-04、MH-05、GOV-02、GOV-03、CON-01） |
| [ADR-018](adr/ADR-018-knowledge-ingest-retrieval.md) | Knowledge 入库、检索与数据源（覆盖 KNW-01〜08） |
| [ADR-019](adr/ADR-019-flow-engine-publication.md) | Flow 执行引擎与发布（覆盖 FLW-01〜07） |
| [ADR-020](adr/ADR-020-orchestration.md) | Orchestration 编排（覆盖 ORC-01〜07） |
| [ADR-021](adr/ADR-021-connector-im-mcp.md) | Connector、IM 与 MCP 管理（覆盖 CON-02〜07） |
| [ADR-022](adr/ADR-022-chat.md) | Chat 对话（覆盖 CHT-02〜07） |
| [ADR-023](adr/ADR-023-agent-skill-assets.md) | Agent 与 Skill 资产管理（覆盖 AST-01〜06） |
| [ADR-024](adr/ADR-024-model-hub-routing.md) | Model Hub 供应商、路由与决策（覆盖 MH-03、MH-06、MH-07） |
| [ADR-025](adr/ADR-025-governance.md) | Governance 余项（覆盖 GOV-06〜11） |
| [ADR-026](adr/ADR-026-foundation.md) | Foundation 余项（覆盖 FND-08〜11） |
| [ADR-027](adr/ADR-027-runtime-sandbox-subagent.md) | Runtime 沙箱、子 Agent、上下文压缩与后台 Job（覆盖 RT-09〜12） |
| [ADR-028](adr/ADR-028-hci-frontend.md) | HCI 前端架构与各工作台（覆盖 HCI-03〜08） |
| [ADR-029](adr/ADR-029-design-rulings.md) | 设计定案（42 条，全局） |
| [ADR-030](adr/ADR-030-anti-drift-process.md) | 防偏差机制与设计变更流程（ENG-08〜10） |
| [ADR-031](adr/ADR-031-agent-eval-experience.md) | Agent 观测、评测与经验自进化，对标阿里云 AgentLoop（EVO-01〜06） |
| [ADR-032](adr/ADR-032-agent-charter.md) | 可信放权 D0：数字员工档案与岗位授权书（TRU-01〜04） |
| [ADR-033](adr/ADR-033-reversible-actions.md) | 可信放权 D1：可撤销执行，动作契约、变更单与撤销（TRU-05〜08） |
| [ADR-034](adr/ADR-034-autonomy-ladder.md) | 可信放权 D2：自治阶梯与影子上岗（TRU-09〜12） |
| [ADR-035](adr/ADR-035-value-ledger.md) | 可信放权 D3：价值账本与数字员工经营报表（TRU-13〜15） |

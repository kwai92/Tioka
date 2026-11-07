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

2026-11-05 的差距分析、预期目标、推进规划、差异化方向、合同场景与多智能体设计原文归档在 [design-notes/](design-notes/README.md)，仅作背景，执行以 ADR、34 号计划和施工设计为准。

联网调研报告（如 Agent、多 Agent、意图路由、SuperAgent、A2A 调研）放在 [research/](research/README.md)，同样仅作背景。

### 近期 ADR

ADR-015〜040 是 M0〜M4 的权威设计（ADR-032〜035 为可信放权，ADR-038〜040 为多智能体与运行时权限，见下表；ADR-036、ADR-037 为业务本体与结构化输出，来自商业租赁合同场景），推进计划见 [34-M0至M4推进规划与工作项](implementation/34-M0至M4推进规划与工作项.md)，契约见 [docs/contracts](../contracts/README.md)。M0〜M2 的施工设计（逐工作项的改动清单、关键代码、迁移、接口、测试与完成标准）见 [construction/00-施工总纲](construction/00-施工总纲.md)。

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
| [ADR-036](adr/ADR-036-business-ontology-domain-pack.md) | 业务本体与领域包：文档层、合同模型投影、读模型工具、`rule.evaluate`、@model.build（DOC-01〜03、ONT-01〜09） |
| [ADR-037](adr/ADR-037-structured-outputs.md) | 结构化输出：Schema 登记表、AgentSpec 输出契约、`data` item、字段级评测（SCH-01〜05） |
| [ADR-038](adr/ADR-038-multi-agent-team.md) | 多智能体团队：规划、分派、协商、汇总，子 Agent 生命周期（MAS-03〜07） |
| [ADR-039](adr/ADR-039-delegation-grant.md) | 委派授予与权限收窄，Skills / MCP / Workflow 的三道门（MAS-01、MAS-02、DAT-04〜06） |
| [ADR-040](adr/ADR-040-dataset-policy-data-label.md) | 数据集行列策略与数据标签（DAT-01〜03） |
| [ADR-041](adr/ADR-041-config-versioning-observability.md) | 配置版本化、运行状态、告警与统计 |
| [ADR-042](adr/ADR-042-provider-credentials-model-params.md) | 供应商凭证与模型参数 |
| [ADR-043](adr/ADR-043-flow-orchestration-detail.md) | Flow 工作流编排详细设计（FLW-09〜19） |
| [ADR-044](adr/ADR-044-entry-intent-routing.md) | 入口意图路由与受控交接（RTE-01〜03） |
| [ADR-045](adr/ADR-045-a2a-interop.md) | A2A 互通：概念映射与安全边界（实现在 M4 之后） |
| [ADR-046](adr/ADR-046-execution-strategy.md) | Agent 执行策略：REACT、PLAN_EXECUTE、REFLECT 等（EXE-01〜04） |
| [ADR-047](adr/ADR-047-external-executor.md) | 外部执行器，DSH 式插件执行（实现在 M4 之后） |
| [ADR-048](adr/ADR-048-context-engineering.md) | 上下文工程：四区组装、有效预算与分级削减、子 Agent 隔离、上下文清单（CTX-01〜03） |
| [ADR-049](adr/ADR-049-prompt-caching.md) | 提示缓存：缓存友好规则、供应商适配与命中率监控（CCH-01、CCH-02） |
| [ADR-050](adr/ADR-050-memory-system.md) | 记忆体系：分层、作用域、读写路径、权限与治理（MEM-01〜05，取代 CHT-05） |
| [ADR-051](adr/ADR-051-foundation-platform.md) | Foundation 基础平台：定位、架构与应用接入（BAS，详设见 [S15](systems/S15-Foundation基础平台系统.md)） |
| [ADR-052](adr/ADR-052-org-account-authz-center.md) | 组织、账号与权限中心 |
| [ADR-053](adr/ADR-053-foundation-common-services.md) | Foundation 公共服务、安全合规与部署 |
| [ADR-054](adr/ADR-054-engineering-base-subsystem-microfrontend.md) | Java 工程化底座、子系统授权模式与微前端 |
| [ADR-055](adr/ADR-055-knowledge-ingest-chunking.md) | 知识入库：解析输入契约、结构化切块与上下文增强（KNW-11、KNW-20） |
| [ADR-056](adr/ADR-056-retrieval-qa-evaluation.md) | 知识检索、问答模式与评测：模型与维度、九步检索、AUTO/TOOL/BOTH、检索轨迹、生成侧评测（KNW-12〜15） |
| [ADR-057](adr/ADR-057-external-knowledge-providers.md) | 外部知识库产品的适配与对接：KnowledgeProvider SPI、检索联邦、内置适配器、反向暴露（KNW-16〜18） |
| [ADR-060](adr/ADR-060-document-parsing-service.md) | 文档解析服务与解析输出契约 `tioka.parse/v1`（PRS-00〜02；KNW-09 作废） |
| [ADR-061](adr/ADR-061-parse-engine-spi-external-ocr.md) | 解析引擎 SPI 与外购 OCR 产品的适配与对接（PRS-03〜05、PRS-10、PRS-12） |
| [ADR-062](adr/ADR-062-parse-quality-special-elements-security.md) | 解析质量评测、合同特殊元素与解析安全（PRS-06〜08、PRS-11） |
| [ADR-065](adr/ADR-065-guardrail-pipeline-input-safety.md) | 护栏管线、内容信任分级与输入安全：五检查点、污点、隔离读取、检测、敏感数据与假名化（GRD-00〜04、GRD-06；RT-14 作废） |
| [ADR-066](adr/ADR-066-structured-output-reliability.md) | 结构化输出可靠性：交付契约、生成方言、严格档、能力记录、八步流水线、降级兜底、Schema 演进（SCH-06〜12） |
| [ADR-067](adr/ADR-067-output-safety-guardrail-eval.md) | 输出内容安全、生成内容标识与护栏评测（GRD-05、GRD-08、GRD-10） |

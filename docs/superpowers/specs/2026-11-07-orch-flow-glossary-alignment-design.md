# Design: Orch / Flow 名词对齐（闭环 #6）

- 日期：2026-11-07
- 状态：Accepted（已完成；V132/V143 表迁登记遗留）
- 前置：system→model；策略 B

## 决策

| 项 | 定案 |
| --- | --- |
| Orch 权威 | ADR-020 / M2-02；目标表 V132（contracts）；**现行** M0 claim/attention 为过渡 |
| Flow 身份 | **ADR-043**：Flow ≈ `flow_draft` 头；S14 `flow_def` 不进 GL 权威 |
| FlowPublication | 类名权威；物理表 `flow_publication`（V129 RENAME；V143 重建另波） |
| Attention | 产品语义；目标持久化 `runtime_approval`；现行 `orch_attention` 登记遗留 |
| 双 OrchApi | `api.engine.OrchApi` = 目标；根包 `OrchApi` = `@Deprecated` 过渡 |
| FlowDefinition | 废止作独立实体；= Flow / FlowDraft |
| 排除 | TenantContext；本闭环不执行 V132/V143 全量迁表（登记 §4） |

## 验收

- 47-GL § Orch / § Flow 就位；禁用表含 orch_attention / flow_tool_pub（作目标废止）
- Java：`FlowPublicationDO` 类名；旧 OrchApi Deprecated
- 现有 FlowLlmNodeIT / Orch 相关可跑 IT 绿

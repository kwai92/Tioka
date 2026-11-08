# Design: Model Hub 名词对齐（闭环 #5）

- 日期：2026-11-07
- 状态：Accepted（已完成）
- 前置：system、runtime、chat、agent/mcp/skill；策略 B

## 决策

| 项 | 定案 |
| --- | --- |
| ModelBinding | 权威名；废止领域/API 裸 `Binding`（表已是 `model_binding`） |
| ProviderConnection | 文档/GL 权威；本闭环 **类名/表名** 仍 `Provider` / `model_provider`（P1 再改） |
| LogicalModel | 现行权威；ModelAlias 仅 GL 标注为 ADR-041 目标 |
| kind vs purpose | 本闭环保持 binding.`kind`；`ModelPurpose` 为调用维度；不改列 |
| Deployment | 禁止作独立实体；= ModelBinding |
| 表前缀 | 冻结 `model_*`；废止 S10 `mdl_*` |
| 排除 | TenantContext；`decision_binding` 删表；`cfg_version` / `model_alias` Flyway |

## 验收

- 47-GL § Model Hub 就位；禁用裸 Binding
- Java/API 无对外裸 `Binding*` 类型（Cooldown 键可仍含 bindingId）
- model 相关 IT 绿（TurnFreeze / Cooldown / Failover / Usage 等）

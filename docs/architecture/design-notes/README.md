# 设计材料归档（2026-11-05）

这里收的是 2026-11-05 在 Claude Docs 里写的分析和设计材料，原样转成 Markdown 入仓，方便团队查阅和追溯来由。

**这些文件是背景和历史，不是执行依据。** 实现时以下面三处为准，冲突时以它们为准：

1. [ADR](../adr/)：ADR-015〜040，以及 [ADR-029 设计定案](../adr/ADR-029-design-rulings.md)（第 1〜59 条）。
2. [34 号计划](../implementation/34-M0至M4推进规划与工作项.md)：M0〜M4 推进规划与工作项（33 号计划已被它取代）。
3. [施工设计](../construction/00-施工总纲.md)：M0〜M3 逐工作项的改动清单、迁移、接口与测试。

| 文件 | 原文 | 状态 | 后续落地 |
| --- | --- | --- | --- |
| [01 代码现状与目标架构差距分析](01-代码现状与目标架构差距分析.md) | [Claude Docs](https://claude.ai/artifact/SAJrvKthNMeZJjsPeqJ1sm) | 背景 | P0/P1 建议进入 ADR-015〜031 与 34 号计划 |
| [02 预期目标与对标调研](02-预期目标与对标调研.md) | [Claude Docs](https://claude.ai/artifact/2BjtRBWUmacrsMfd5jtMjA) | 背景 | 里程碑由 34 号计划的 M0〜M4 与 G0〜G4 取代 |
| [03 推进规划与详细设计 A–E](03-推进规划与详细设计-A至E.md) | [Claude Docs](https://claude.ai/artifact/V3wtabWCVSwAC3ewNerFNi) | 历史 | ADR-015〜031、34 号计划、施工设计 |
| [04 推进规划与详细设计 F–N](04-推进规划与详细设计-F至N.md) | 同上 | 历史 | 同上 |
| [05 差异化能力方向](05-差异化能力方向-数字员工全生命周期.md) | [Claude Docs](https://claude.ai/artifact/1HP9f4Rr9XmaiZqMMLRJjx) | 背景 | 选定 D0〜D3：ADR-032〜035、TRU-01〜15、M2-05、ADR-029 第 36〜42 条 |
| [06 商业租赁合同本体与风险审查设计](06-商业租赁合同本体与风险审查设计.md) | [Claude Docs](https://claude.ai/artifact/PxdZR5xfAkyauCJ7AUQfAd) | 背景 | ADR-036、ADR-037、M3-03、M3-04、ADR-029 第 50〜59 条 |
| [07 多智能体自主协作与权限管控设计](07-多智能体自主协作与权限管控设计.md) | [Claude Docs](https://claude.ai/artifact/Qy9taJVV8cz2B7VBGewkJA) | 背景 | ADR-038〜040、M3-01、M3-02、ADR-029 第 43〜49 条 |

几点说明：

- 排期以 ADR-029 第 59 条为准：M3 为 W19–W40，M4 为 W41–W52。03、04 里的 W19–W30 / W31–W42 是旧排期。
- 05 里的「授权书（Mandate）」在 ADR 里定名为 Charter（ADR-029 第 36 条）。
- 原文里的嵌入图没有导出，文中用一行引用注明了图名；需要看图请打开原文链接。
- 这些文件今后不再更新。新的结论请写进 ADR 或按 [ADR-030](../adr/ADR-030-anti-drift-process.md) 的变更流程修订 34 号计划和施工设计。

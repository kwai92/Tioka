# ADR-030 — 防偏差机制与设计变更流程

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-05
- 覆盖工作项：ENG-08〜10
- 阅读版：[Tioka 推进规划与详细设计](https://claude.ai/artifact/V3wtabWCVSwAC3ewNerFNi)
- 约束：与 [ADR-029](ADR-029-design-rulings.md) 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

文字设计本身拦不住偏差。建议把设计变成**仓库里的契约文件和 CI 里会失败的检查**，靠机器守，而不是靠人记。

1. **设计入库，只留一份权威**。设计 A–E 和上表定案转成 `docs/architecture/adr/` 下的 ADR，每份设计一篇、定案逐条编号。本文和飞书版只用于阅读。之后改设计只能通过修改 ADR 的 PR。
2. **契约先行**。每份设计开工前，先提一个只含契约的 PR：`-api` 模块的接口与 record、Flyway 迁移脚本、事件 JSON Schema、错误码。契约合并后，实现 PR 不得修改契约文件；用 CODEOWNERS 让契约目录必须由你审批。
3. **可执行的架构守护**。在 CI 里用 ArchUnit 强制：
   - 除 model-biz 外，任何模块不得依赖模型 HTTP 客户端（守住设计 C）。
   - 所有 Controller 方法必须带 `@RequirePermission` 或 `@PublicApi`（守住设计 A）。
   - `tioka-server` 只依赖各域 `-api`；应用层禁止 `synchronized` 与 `new ObjectMapper()`（守住设计 B 和 ENG-05）。
   - Chat、Flow、Orchestration 只能通过 `RuntimeApi` 执行 Agent（守住「一个内核」）。
4. **契约测试套件**。把状态机写成表驱动的测试：Turn 状态转换、幂等、租约失效；审批的创建、提交、过期；Flow 调度的分支、汇合、跳过传播、Loop。实现怎么改都要过这些用例。
5. **验收用例先写**。每个阶段开始时先把闸门（G0–G4）的端到端用例写好并让它们处于失败状态，阶段结束以它们变绿为准。
6. **可追溯与对账**。PR 模板必填工作项编号和对应 ADR；与设计不一致的 PR 必须同时修改 ADR。每两周做一次设计对账，抽查已合并代码是否符合 ADR。

这六条里，第 1、2、3 条应在 M0 第一周完成，否则 M0 的大规模改动会在没有契约的情况下开始。建议新增工作项 ENG-08（ADR 入库）、ENG-09（M0 契约 PR）、ENG-10（ArchUnit 守护规则），均为 M0、P0。

## 落实情况（2026-11-05）

- 第 1 条：设计 A–N 与定案已入库为 ADR-015〜030（ENG-08）。阅读版文档与飞书版只用于阅读，以本目录为准。
- 第 2 条：M0 契约见 [docs/contracts](../../contracts/README.md)（ENG-09）；`.github/CODEOWNERS` 已把契约目录交给架构负责人审批，`.github/pull_request_template.md` 要求填写工作项编号与 ADR。
- 第 3 条：ArchUnit 守护规则（ENG-10）在 M0 第一周的实现 PR 中落地。
- 第 4 条：已有 `TurnStatusContractTest`、`RuntimeEventSchemaContractTest` 两组契约测试。

# W0-17 契约冻结检查点

- 日期：2026-11-05
- 状态：FROZEN v0（进入 W1 前生效）

## 冻结内容

1. **Part Schema v0**：`tioka-module-runtime-api/src/main/resources/schema/part-v0.json` + `PartType` 枚举（18 类）。
2. **MQ Topic/Tag**：各域 `*MqTopics` 常量类；biz 禁止硬编码字符串。
3. **Foundation SPI**：`IdPAdapter` / `ImChannelAdapter` / `BizSystemAdapter` / `AuditSink` / `SecretStore`。
4. **空 API 方法清单**：W1 起按 S1/S3/S10–S12 填充；变更须递增 schema/`apiVersion`。

## 变更纪律

- 破坏性变更 → 新版本字段或并行 schema，禁止静默改 wire 名。
- 未通过本冻结点，不得合并 Runtime 业务实现 PR（见 30 §8）。

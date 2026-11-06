# ADR-053 — Foundation 公共服务、安全合规与部署

- 状态：Accepted（设计定案，待实现）
- 日期：2026-11-06
- 覆盖工作项：BAS-09、BAS-10、BAS-16〜18、BAS-21、BAS-FE-06、BAS-FE-09
- 详细设计：[S15](../systems/S15-Foundation基础平台系统.md) 第 5、11〜14 节
- 相关：[ADR-025](ADR-025-governance.md)（GOV-02、GOV-09）、[ADR-026](ADR-026-foundation.md)、ADR-029 第 56、57、69 条
- 约束：与 ADR-029 定案冲突时以定案为准；修改本 ADR 走 [ADR-030](ADR-030-anti-drift-process.md) 的变更流程。

## 决定

1. **公共服务清单**：字典、参数（P0）；审计与登录日志（P0）；消息中心，含站内信、公告、待办聚合与邮件、飞书渠道（P1）；文件服务、工作日历、编号规则（P2）。字典与参数归属全局或某应用，变更发事件，敏感参数只写不读。
2. **若依能力的取舍**按 S15 第 5 节：不做多租户、定时任务中心、代码生成、服务监控、工作流；三方登录改由 Keycloak 身份代理实现。
3. **审计**：Foundation 自身管理操作全量记录并按 GOV-09 哈希链封印（独立一条链）；各应用经 starter 上报操作日志，集中查询。Tioka 的 GOV-02 审计仍在 Tioka，只镜像管理类操作。登录日志来自 Keycloak 事件。审计与登录日志在线保留不少于 180 天。
4. **消息中心与 Tioka 通知**：渠道接口沿用第 56 条的 `NotificationChannel`。Tioka 的 NTF 照 M3 计划做；改为调用 Foundation 消息中心放在 M4 之后，不计入本期。待办深链规则同第 57 条。
5. **安全默认值**按等保三级常见检查项：密码策略、失败锁定、会话超时、敏感字段加密与脱敏、三员互斥；主密钥来自 `SecretStoreAdapter`，生产缺主密钥时启动失败。
6. **事件**经 outbox 投 RocketMQ 主题 `fdn-events`，可同时投 Webhook（HMAC）；事件体只带 ID、版本、变化类型。
7. **部署**：Foundation 服务（至少 2 副本）、管理台与门户静态资源、Keycloak（至少 2 副本）；PostgreSQL、Redis、RocketMQ、MinIO 与 Tioka 共用（不同库、键与主题加 `fdn` 前缀）；Flyway 号段独立；Compose 与 Helm、离线包随 FND-11 一起交付；首次启动初始化 realm、内置应用与三员角色、停用状态的应急管理员。

## 备选

| 备选 | 不选原因 |
| --- | --- |
| 引入 XXL-Job 等调度中心 | Tioka 已有 scheduler 角色与 Redis 锁，再加一套调度增加运维面 |
| 审计全部集中到 Foundation（含 Tioka 的工具调用审计） | Tioka 审计量大且与 Turn、traceId 强关联，集中会让 Foundation 进入热路径 |
| 消息中心本期就替换 Tioka 通知 | 会改动 M3 已排的 NTF 工作，收益可以延后获得 |

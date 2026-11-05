# ADR-008 — 中间件默认选型

- 状态：Accepted（W0-13）
- 日期：2026-11-05

## 决策

| 组件 | 默认 | 说明 |
| --- | --- | --- |
| MQ | RocketMQ 5.x | Topic 常量仅在 `*-api`；客户可换 Kafka Adapter |
| 向量库 | pgvector（PostgreSQL 扩展） | W3 起步；可换 Milvus Adapter |
| 缓存 | Redis 7 | 会话/限流/租约 |
| 对象存储 | 本地 / MinIO（S3 兼容） | 私有化默认 MinIO |

## 后果

- `deploy/compose.yml` 提供本地 PG + Redis + RocketMQ。
- 生产强制外置；禁止捆绑仅内网不可达的公有云依赖。

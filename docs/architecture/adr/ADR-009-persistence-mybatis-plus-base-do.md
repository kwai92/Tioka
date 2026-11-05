# ADR-009：JdbcTemplate PoC 收口为 MyBatis-Plus + BaseDO

- 状态：Accepted
- 日期：2026-11-05
- 关联：`41-TA`、`42-DA`、`44-EA`；spec `docs/superpowers/specs/2026-11-05-persistence-guardrails-mybatis-plus-design.md`

## 决策

1. 业务持久化统一 **MyBatis-Plus**；禁止生产代码使用 `JdbcTemplate` / `org.springframework.jdbc`。
2. 行级审计与逻辑删统一在 `BaseDO`：`createAt`/`updateAt`/`createBy`/`updateBy`/`deleted`（列 `create_at`/`update_at`/`create_by`/`update_by`/`deleted`）。
3. **不**引入 `tenantId` 列（多租户另开 ADR）。
4. 例外：`ImmutableSnapshotDO`（发布快照）、`AppendOnlyDO`（追加日志/票据）。
5. 护栏：`.cursor/rules` + `PersistenceArchTest`（ArchUnit）。

## 后果

- 存量 JDBC 一次性迁完，无白名单（2026-11-05 已落地；生产 `org.springframework.jdbc` 引用 = 0）。
- `42-DA` 字段字面与本文对齐（`created_at`→`create_at`；暂无 tenant 列）。
- 护栏：`PersistenceArchTest` + `.cursor/rules/tioka-architecture.mdc` / `tioka-persistence-biz.mdc`。

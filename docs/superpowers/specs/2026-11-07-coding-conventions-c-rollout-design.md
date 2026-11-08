# Design: 编码规约 C 档全域落地

- 日期：2026-11-07
- 状态：Accepted（已落地：SQL→XML + ArchUnit；ItemWrite / UsageRecordCommand / ChunkInsertCommand / AuditEvent*；已知魔法值常量化；相关 IT 绿）
- 策略：先立硬规 → 注解 SQL 全迁 XML → 随模块清魔法值/长参 → ArchUnit 守门

## 硬规则（写入 48-CS）

1. **魔法值**：业务分支禁止未命名字面量；TTL/分页/限额进常量或 `@ConfigurationProperties`
2. **长参**：同一方法业务入参 ≥3 → Command/Query/DTO；禁止一长串基础类型
3. **SQL**：自定义 SQL 仅 `classpath*:/mapper/**/*.xml`；禁止 Mapper 接口新增 `@Select/@Insert/@Update/@Delete`；存量迁完后 ArchUnit 禁注解 SQL
4. **中间件优先**：缓存/锁/限流/延迟/事务/分页优先 Spring / Redis / MP；自造协议须 ADR

## 验收

- 48-CS / Cursor rules 含上述四条
- `tioka-modules/**/mapper/*Mapper.java` 无 `@Select|@Insert|@Update|@Delete`
- 各 biz 存在对应 `resources/mapper/*.xml`
- PersistenceArchTest（或等价）禁止注解 SQL
- 相关 IT 绿；排除 TenantContext 重命名

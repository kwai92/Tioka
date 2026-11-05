package com.tioka.system.api.audit;

/**
 * 审计结果。SUCCESS 与业务同事务写入；DENIED 与 ERROR 用独立事务（REQUIRES_NEW），
 * 不随业务回滚（ADR-029 第 8 条）。
 */
public enum AuditOutcome {
    SUCCESS, DENIED, ERROR
}

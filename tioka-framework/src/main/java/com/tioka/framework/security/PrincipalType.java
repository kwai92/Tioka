package com.tioka.framework.security;

/** 主体类型（ADR-015）。 */
public enum PrincipalType {
    /** 真人用户。 */
    USER,
    /** 服务账号（API Key、编排规则的 run_as）。 */
    SERVICE,
    /** 平台内部任务，仅限系统级维护操作。 */
    SYSTEM
}

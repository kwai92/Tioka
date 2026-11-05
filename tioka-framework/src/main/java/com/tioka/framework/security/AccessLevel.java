package com.tioka.framework.security;

/** 资源级访问等级，按声明顺序递增（ADR-015）。 */
public enum AccessLevel {
    VIEW, USE, EDIT, OWNER;

    public boolean covers(AccessLevel required) {
        return this.ordinal() >= required.ordinal();
    }
}

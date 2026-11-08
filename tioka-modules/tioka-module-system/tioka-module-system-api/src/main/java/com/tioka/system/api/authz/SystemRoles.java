package com.tioka.system.api.authz;

/** 内置角色码常量（禁止业务魔法字符串；47-GL / 48-CS）。 */
public final class SystemRoles {
    public static final String SYSTEM_ADMIN = "system_admin";
    public static final String WORKSPACE_ADMIN = "workspace_admin";
    public static final String BUILDER = "builder";
    public static final String MEMBER = "member";
    public static final String VIEWER = "viewer";

    private SystemRoles() {}
}

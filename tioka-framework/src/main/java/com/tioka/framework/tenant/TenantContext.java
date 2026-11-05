package com.tioka.framework.tenant;

public final class TenantContext {
    private static final ThreadLocal<Long> TENANT = new ThreadLocal<>();
    private static final ThreadLocal<Long> USER = new ThreadLocal<>();
    private static final ThreadLocal<Long> WORKSPACE = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Long tenantId, Long userId, Long workspaceId) {
        TENANT.set(tenantId);
        USER.set(userId);
        WORKSPACE.set(workspaceId);
    }

    public static Long getTenantId() { return TENANT.get(); }
    public static Long getUserId() { return USER.get(); }
    public static Long getWorkspaceId() { return WORKSPACE.get(); }

    public static void clear() {
        TENANT.remove();
        USER.remove();
        WORKSPACE.remove();
    }
}

package com.tioka.framework.tenant;

import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.framework.security.TiokaAuthentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

/**
 * 兼容门面：委托 SecurityContext 中的 {@link Principal}（ADR-015；ADR-029 第 60 条无 tenant）。
 * 新代码请注入 {@link PrincipalHolder}。
 */
public final class TenantContext {
    private TenantContext() {}

    /** @deprecated 使用 {@link PrincipalHolder#runAs} */
    @Deprecated
    public static void set(Long tenantId, Long userId, Long workspaceId) {
        String uid = userId == null ? "0" : String.valueOf(userId);
        String ws = workspaceId == null ? null : String.valueOf(workspaceId);
        Principal p = new Principal(uid, ws, PrincipalType.USER, Set.of(), Set.of(), "legacy");
        SecurityContextHolder.getContext().setAuthentication(new TiokaAuthentication(p));
    }

    /** @deprecated 无租户模型，恒为 null */
    @Deprecated
    public static Long getTenantId() {
        return null;
    }

    public static Long getUserId() {
        Principal p = principalOrNull();
        if (p == null) {
            return null;
        }
        try {
            return Long.parseLong(p.userId());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Long getWorkspaceId() {
        Principal p = principalOrNull();
        if (p == null || p.workspaceId() == null) {
            return null;
        }
        // 空间 ID 现为字符串（如 ws_default），不再保证可解析为 Long
        return null;
    }

    public static String getWorkspaceIdString() {
        Principal p = principalOrNull();
        return p == null ? null : p.workspaceId();
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }

    private static Principal principalOrNull() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof TiokaAuthentication ta) {
            return ta.getPrincipal();
        }
        return null;
    }
}

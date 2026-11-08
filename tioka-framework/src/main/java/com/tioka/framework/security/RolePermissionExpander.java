package com.tioka.framework.security;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 将角色权限表达中的通配（{@code *}、{@code agent:*}、{@code *:read}）展开为具体权限点（FND-04）。
 */
public final class RolePermissionExpander {

    private RolePermissionExpander() {}

    public static Set<String> expand(Collection<String> rawPermissions) {
        Set<String> out = new LinkedHashSet<>();
        if (rawPermissions == null || rawPermissions.isEmpty()) {
            return out;
        }
        for (String raw : rawPermissions) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String p = raw.trim();
            if (Permissions.ALL.equals(p) || "*".equals(p)) {
                out.add(Permissions.ALL);
                continue;
            }
            if (p.endsWith(":*")) {
                String type = p.substring(0, p.length() - 2);
                for (String concrete : Permissions.ALL_CONCRETE) {
                    if (concrete.startsWith(type + ":")) {
                        out.add(concrete);
                    }
                }
                continue;
            }
            if (p.startsWith("*:")) {
                String action = p.substring(2);
                for (String concrete : Permissions.ALL_CONCRETE) {
                    int idx = concrete.indexOf(':');
                    if (idx > 0 && concrete.substring(idx + 1).equals(action)) {
                        out.add(concrete);
                    }
                }
                continue;
            }
            out.add(p);
        }
        return out;
    }
}

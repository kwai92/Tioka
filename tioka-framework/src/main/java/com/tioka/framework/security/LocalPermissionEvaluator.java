package com.tioka.framework.security;

/**
 * 本地模式的权限判定：逐字沿用改造前 {@code PermissionAspect} 的行为。
 *
 * <p>直接读当前主体自带的权限集并调用 {@link Principal#hasPermission(String)}，
 * 含 {@code *} 超管与已展开的具体权限点语义。此处刻意不引入任何展开、规范化或映射，
 * 保证 {@code local} 模式零行为变化。
 */
public final class LocalPermissionEvaluator implements PermissionEvaluator {

    @Override
    public String firstMissing(Principal principal, String[] required) {
        for (String permission : required) {
            if (!principal.hasPermission(permission)) {
                return permission;
            }
        }
        return null;
    }
}

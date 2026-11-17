package com.tioka.framework.security;

/**
 * 功能权限判定端口（FND-04；能力归属清单「身份上下文适配」）。
 *
 * <p>把「权限从哪里来」从 {@link RequirePermission} 的切面里抽出来：
 * {@link LocalPermissionEvaluator} 用应用自建身份，{@link FoundationPermissionEvaluator}
 * 用 Foundation 中台授权快照。两者由 {@code tioka.foundation.mode} 条件装配，不并存。
 *
 * <p>为避免切面里散落判定逻辑，本接口直接返回「第一个缺失的权限码」，
 * 便于调用方沿用既有的拒绝审计与 403 语义。
 */
public interface PermissionEvaluator {

    /**
     * 返回第一个缺失的权限码；全部具备时返回 {@code null}。
     *
     * @param principal 当前主体，非 null
     * @param required  注解声明的权限码，非 null
     */
    String firstMissing(Principal principal, String[] required);
}

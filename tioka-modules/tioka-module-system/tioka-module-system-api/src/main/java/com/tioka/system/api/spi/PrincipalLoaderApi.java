package com.tioka.system.api.spi;

import com.tioka.framework.security.Principal;

/**
 * 按 userId + workspaceId 重建 Principal（Turn runAs 重载等）。
 */
public interface PrincipalLoaderApi {
    Principal load(long userId, String workspaceId);

    /**
     * MAS-01（ADR-039）：SERVICE 模式带经办人时的有效权限 = 服务账号权限 ∩ 经办人权限，
     * 并把 {@code onBehalfOf} 写入 Principal（全链不变）。经办人为空时等价 {@link #load}。
     * 实现侧（system-biz PrincipalLoader）做真实交集；此默认实现仅附着身份（测试桩用）。
     */
    default Principal loadEffective(long userId, String workspaceId, String onBehalfOf) {
        Principal p = load(userId, workspaceId);
        if (onBehalfOf == null || onBehalfOf.isBlank()) {
            return p;
        }
        return new Principal(p.userId(), p.workspaceId(), p.type(), p.roles(), p.permissions(),
                p.authMethod(), onBehalfOf);
    }
}

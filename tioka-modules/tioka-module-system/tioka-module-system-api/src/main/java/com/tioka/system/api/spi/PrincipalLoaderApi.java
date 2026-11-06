package com.tioka.system.api.spi;

import com.tioka.framework.security.Principal;

/**
 * 按 userId + workspaceId 重建 Principal（Turn runAs 重载等）。
 */
public interface PrincipalLoaderApi {
    Principal load(long userId, String workspaceId);
}

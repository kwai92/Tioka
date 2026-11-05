package com.tioka.framework.security;

import java.util.Set;

/**
 * 资源级权限校验（ADR-015）。数据源为 {@code sys_resource_acl} 与资源 owner。
 *
 * <p>列表查询必须用 {@link #visibleIds} 把可见范围下推为 SQL 条件，不得召回后再过滤（ADR-018）。
 */
public interface ResourceGuard {

    /** 当前主体对资源不足 {@code level} 时抛出 {@code FORBIDDEN}，并写 DENIED 审计。 */
    void check(String resourceType, String resourceId, AccessLevel level);

    boolean allows(String resourceType, String resourceId, AccessLevel level);

    /** 当前主体在当前空间内至少具备 {@code level} 的资源 ID。 */
    Set<String> visibleIds(String resourceType, AccessLevel level);
}

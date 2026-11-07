package com.tioka.framework.security;

import java.util.Set;

/**
 * 资源级权限校验（ADR-015 / FND-04）。数据源为 {@code sys_resource_acl}、资源归属与 visibility。
 *
 * <p>列表查询必须用 {@link #filter} 把可见范围下推为 SQL 条件，不得召回后再过滤（ADR-018）。
 */
public interface ResourceGuard {

    /** 当前主体对资源不足 {@code level} 时抛出 {@code FORBIDDEN}（跨空间抛 {@code NOT_FOUND}），并写 DENIED 审计。 */
    void check(String resourceType, String resourceId, AccessLevel level);

    boolean allows(String resourceType, String resourceId, AccessLevel level);

    /** 当前主体在当前空间内至少具备 {@code level} 的资源 ID（仅用于 ≤1000 的小集合）。 */
    Set<String> visibleIds(String resourceType, AccessLevel level);

    /** 返回可直接拼入 MyBatis-Plus Wrapper 的可见性条件。 */
    VisibilityFilter filter(String resourceType, AccessLevel level);
}

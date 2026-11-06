package com.tioka.framework.security;

import java.util.Map;
import java.util.Objects;

/**
 * 资源可见性 SQL 条件下推（FND-04 / M1-01）。
 *
 * @param all    当前主体对全部资源具备要求等级（如 {@code *}）
 * @param sql    可拼入 MyBatis-Plus Wrapper 的条件片段；{@code all=true} 时为空
 * @param params 命名参数
 */
public record VisibilityFilter(boolean all, String sql, Map<String, Object> params) {
    public VisibilityFilter {
        params = params == null ? Map.of() : Map.copyOf(params);
        if (all) {
            sql = sql == null ? "" : sql;
        } else {
            Objects.requireNonNull(sql, "sql");
        }
    }

    public static VisibilityFilter allowAll() {
        return new VisibilityFilter(true, "", Map.of());
    }

    public static VisibilityFilter of(String sql, Map<String, Object> params) {
        return new VisibilityFilter(false, sql, params);
    }
}

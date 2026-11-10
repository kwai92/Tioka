package com.tioka.connector.api.dataset;

import java.util.List;
import java.util.Map;

import java.util.List;

/**
 * 编译后的查询计划（M3-02 PlannedQuery）。
 *
 * <pre>
 * record PlannedQuery(String whereSql, List&lt;Object&gt; params,      // JDBC：外层 WHERE，参数化
 *                     Map&lt;String, String&gt; restParams,              // REST：可下推的过滤
 *                     Predicate&lt;Map&lt;String, Object&gt;&gt; postFilter,   // REST：不可下推部分，插件内执行
 *                     List&lt;String&gt; selectColumns) {}
 * </pre>
 *
 * <p>行谓词全部参数化绑定（DatasetGatewayIT#sqlInjectionInFilter 口径）。
 */
public record PlannedQuery(
        String whereSql,
        List<Object> params,
        Map<String, String> restParams,
        java.util.function.Predicate<Map<String, Object>> postFilter,
        List<String> selectColumns,
        boolean postFilterNeeded,
        Map<String, ColumnSpec.Mask> masks
) {
    public PlannedQuery {
        params = params == null ? List.of() : List.copyOf(params);
        restParams = restParams == null ? Map.of() : Map.copyOf(restParams);
        selectColumns = selectColumns == null ? List.of() : List.copyOf(selectColumns);
        masks = masks == null ? Map.of() : Map.copyOf(masks);
    }
}

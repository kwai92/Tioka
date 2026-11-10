package com.tioka.connector.api.dataset;

import java.util.List;

/**
 * 查询请求（DAT-01 / M3-02）：{@code data.query(dataset, filter, columns, limit)} 的参数面。
 *
 * @param filter  CEL 子集表达式（row.* 列引用；principal 在编译期代入常量）
 * @param columns 请求的列；null/空 = 全列
 * @param limit   ≤1000
 */
public record DatasetQuery(String filter, java.util.List<String> columns, int limit) {
    public static final int MAX_LIMIT = 1000;

    public DatasetQuery {
        columns = columns == null ? List.of() : List.copyOf(columns);
        if (limit <= 0) {
            limit = 100;
        }
        if (limit > MAX_LIMIT) {
            limit = MAX_LIMIT;
        }
    }
}

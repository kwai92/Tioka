package com.tioka.connector.api.dataset;

import java.util.List;
import java.util.Map;

/**
 * 查询结果（DAT-01 / M3-02）：掩码与打标都已完成，模型拿到的就是掩码后的值。
 *
 * @param label         结果数据标签 = 出现列的列密级最高值（ADR-040 标签来源）
 * @param maskedColumns 被掩码的列名（审计：只记列名不记内容）
 * @param postFiltered  REST 类不可下推条件在插件内后过滤的事实标记
 */
public record DatasetResult(
        List<Map<String, Object>> rows,
        com.tioka.framework.label.DataLabel label,
        List<String> maskedColumns,
        boolean postFiltered
) {
    public DatasetResult {
        rows = rows == null ? List.of() : List.copyOf(rows);
        maskedColumns = maskedColumns == null ? List.of() : List.copyOf(maskedColumns);
    }
}

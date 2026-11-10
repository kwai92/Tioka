package com.tioka.connector.api.dataset;

import java.util.List;

/**
 * 数据集描述（DAT-01 / M3-02 DatasetDescriptor）：由连接器实现经 {@code DatasetSource.describe()}
 * 提供；持久化形态为 data_dataset.columns_json。
 */
public record DatasetDescriptor(
        String datasetId,
        String name,
        List<ColumnSpec> columns
) {
    public DatasetDescriptor {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}

package com.tioka.connector.api.dataset.spi;

import com.tioka.connector.api.dataset.DatasetDescriptor;
import com.tioka.connector.api.dataset.PlannedQuery;
import com.tioka.connector.api.dataset.DatasetQuery;

import java.util.List;
import java.util.Map;

/**
 * 数据集源 SPI（DAT-01 / M3-02）：连接器实现 describe() 与 execute(PlannedQuery)。
 * JDBC 与 REST 两个内置实现落在 connector-biz
 * （ArchUnit {@code onlyConnectorTouchesJdbcDatasource}：除 connector 外不得依赖本实现包）。
 */
public interface DatasetSource {

    /** 数据集标识（连接器类型:实例:对象名）。 */
    String datasetId();

    /** 列规格描述（登记数据集时产生 columns_json；describe 用于运行期一致性校验）。 */
    DatasetDescriptor describe();

    /**
     * 执行编译后的查询计划：JDBC 实现 {@code SELECT {cols} FROM ({source.sql}) t WHERE {whereSql} LIMIT ?}；
     * REST 实现按 restParams 下推 + postFilter 插件内过滤。
     */
    List<Map<String, Object>> execute(PlannedQuery plan, DatasetQuery query);
}

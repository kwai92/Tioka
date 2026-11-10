package com.tioka.connector.application.dataset.jdbc;

import com.tioka.connector.api.dataset.spi.DatasetSource;
import com.tioka.connector.application.dataset.DatasetStore;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;

/**
 * JDBC 数据集源工厂（DAT-01）：按数据集登记（data_dataset.source_json kind=jdbc）
 * 动态构建 {@link JdbcDatasetSource}。注册为 bean 供 DatasetGateway 兜底解析；
 * 静态注册的 REST 插件源仍走 Spring 注入的 {@code List<DatasetSource>}。
 */
@Component
public class JdbcDatasetSourceFactory {

    private final DataSource dataSource;

    public JdbcDatasetSourceFactory(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public DatasetSource create(DatasetStore.Registration reg) {
        Map<String, Object> source = reg.source();
        Object sql = source == null ? null : source.get("sql");
        return new JdbcDatasetSource(reg.id(),
                sql == null ? "" : String.valueOf(sql),
                reg.descriptor(), dataSource);
    }
}

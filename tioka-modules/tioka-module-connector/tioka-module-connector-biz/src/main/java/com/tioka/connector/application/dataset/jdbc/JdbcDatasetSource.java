package com.tioka.connector.application.dataset.jdbc;

import com.tioka.connector.api.dataset.DatasetDescriptor;
import com.tioka.connector.api.dataset.DatasetQuery;
import com.tioka.connector.api.dataset.PlannedQuery;
import com.tioka.connector.api.dataset.spi.DatasetSource;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * JDBC 数据集执行器（DAT-01 / M3-02）：{@code SELECT {cols} FROM ({source.sql}) t WHERE {whereSql} LIMIT ?}。
 * 只读校验（M3-02 L47 口径）：source.sql 拒绝含 INSERT/UPDATE/DELETE/DDL 的语句且禁分号多语句，
 * 连接用只读账号。仅 connector-biz 可依赖（ArchUnit onlyConnectorTouchesJdbcDatasource）。
 *
 * <p>实现走 JDK {@link java.sql.PreparedStatement}（不依赖 spring-jdbc——
 * PersistenceArchTest#noSpringJdbcInProductionCode 禁止；连接由 {@link DataSource} 提供，
 * Connection 按查询短持有并关闭，连接池语义不变）。
 */
public final class JdbcDatasetSource implements DatasetSource {

    private static final Pattern FORBIDDEN = Pattern.compile(
            "(?i)\\b(insert|update|delete|merge|truncate|alter|create|drop|grant|revoke|copy|vacuum|call|do)\\b");
    private static final Pattern COMMENT = Pattern.compile("(?s)(--[^\\n]*|/\\*.*?\\*/)");

    private final String datasetId;
    private final String sourceSql;
    private final DatasetDescriptor descriptor;
    private final DataSource dataSource;

    public JdbcDatasetSource(String datasetId, String sourceSql,
                             DatasetDescriptor descriptor, DataSource dataSource) {
        this.datasetId = datasetId;
        this.sourceSql = sourceSql;
        this.descriptor = descriptor;
        this.dataSource = dataSource;
    }

    @Override
    public String datasetId() {
        return datasetId;
    }

    @Override
    public DatasetDescriptor describe() {
        return descriptor;
    }

    /** 登记 / 查询前校验 source.sql 只读（SELECT-only + 禁分号多语句 + 禁注释夹带）。 */
    public static void validateReadOnly(String sourceSql) {
        if (sourceSql == null || sourceSql.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "source.sql 为空");
        }
        String stripped = COMMENT.matcher(sourceSql).replaceAll(" ").trim();
        if (stripped.contains(";")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "source.sql 禁止分号多语句");
        }
        if (!stripped.toUpperCase(Locale.ROOT).startsWith("SELECT")
                && !stripped.toUpperCase(Locale.ROOT).startsWith("WITH")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "source.sql 仅允许 SELECT/WITH 只读查询");
        }
        if (FORBIDDEN.matcher(stripped).find()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "source.sql 含写操作关键字，拒绝只读校验");
        }
    }

    @Override
    public List<Map<String, Object>> execute(PlannedQuery plan, DatasetQuery query) {
        validateReadOnly(sourceSql);
        StringBuilder sql = new StringBuilder("SELECT ");
        List<String> cols = plan.selectColumns();
        if (cols.isEmpty()) {
            sql.append("*");
        } else {
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append('"').append(cols.get(i)).append('"');
            }
        }
        sql.append(" FROM (").append(sourceSql).append(") t");
        List<Object> params = new ArrayList<>();
        if (plan.whereSql() != null && !plan.whereSql().isBlank()) {
            sql.append(" WHERE ").append(plan.whereSql());
            params.addAll(plan.params());
        }
        sql.append(" LIMIT ?");
        params.add(query.limit());

        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    Object v = params.get(i);
                    if (v == null) {
                        ps.setObject(i + 1, null);
                    } else if (v instanceof Integer n) {
                        ps.setInt(i + 1, n);
                    } else if (v instanceof Long n) {
                        ps.setLong(i + 1, n);
                    } else if (v instanceof Boolean b) {
                        ps.setBoolean(i + 1, b);
                    } else if (v instanceof Double d) {
                        ps.setDouble(i + 1, d);
                    } else {
                        ps.setString(i + 1, String.valueOf(v));
                    }
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<Map<String, Object>> out = new ArrayList<>();
                    while (rs.next()) {
                        ResultSetMetaData md = rs.getMetaData();
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            row.put(md.getColumnLabel(i), valueOf(rs.getObject(i)));
                        }
                        out.add(row);
                    }
                    return out;
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "数据集查询失败: " + e.getMessage());
        }
    }

    /** JDBC 类型 → JSON 友好值（时间戳等经 String 归一）。 */
    private static Object valueOf(Object v) {
        if (v == null
                || v instanceof String || v instanceof Number || v instanceof Boolean) {
            return v;
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toInstant().toString();
        }
        if (v instanceof java.sql.Date d) {
            return d.toString();
        }
        if (v instanceof java.time.OffsetDateTime t) {
            return t.toString();
        }
        if (v instanceof java.time.LocalDateTime t) {
            return t.toString();
        }
        if (v instanceof java.time.LocalDate d) {
            return d.toString();
        }
        return String.valueOf(v);
    }
}

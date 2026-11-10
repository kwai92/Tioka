package com.tioka.ontology.infrastructure;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL {@code text[]} / {@code varchar[]} 数组列 ↔ {@code List<String>}（doc_ids /
 * acl_principals / topics）。经 {@link Connection#createArrayOf} 写入，避免数组字面量手工转义。
 */
@MappedTypes(List.class)
public class PgTextArrayTypeHandler extends BaseTypeHandler<List<String>> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter, JdbcType jdbcType)
            throws SQLException {
        Connection connection = ps.getConnection();
        Array array = connection.createArrayOf("text", parameter.toArray(new String[0]));
        try {
            ps.setArray(i, array);
        } finally {
            array.free();
        }
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toList(rs.getArray(columnName));
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toList(rs.getArray(columnIndex));
    }

    @Override
    public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toList(cs.getArray(columnIndex));
    }

    private static List<String> toList(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        try {
            Object value = array.getArray();
            if (value instanceof String[] strings) {
                return List.of(strings);
            }
            if (value instanceof Object[] objects) {
                List<String> out = new ArrayList<>(objects.length);
                for (Object o : objects) {
                    out.add(o == null ? null : o.toString());
                }
                return out;
            }
            return List.of();
        } finally {
            array.free();
        }
    }
}

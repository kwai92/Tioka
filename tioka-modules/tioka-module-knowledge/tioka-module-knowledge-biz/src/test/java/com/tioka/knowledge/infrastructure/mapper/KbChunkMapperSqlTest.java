package com.tioka.knowledge.infrastructure.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** H2 跳过 pgvector SQL 执行；断言 Mapper 近邻 SQL 字符串契约。 */
class KbChunkMapperSqlTest {

    @Test
    void searchNearestSqlUsesPgvectorOperator() throws Exception {
        Method m = KbChunkMapper.class.getMethod("searchNearest", String.class, String.class, int.class);
        Select select = m.getAnnotation(Select.class);
        String sql = String.join("\n", select.value());
        assertTrue(sql.contains("embedding_vec"));
        assertTrue(sql.contains("<=>"));
        assertTrue(sql.contains("CAST(#{vectorLiteral} AS vector)"));
        assertTrue(sql.contains("LIMIT #{k}"));
    }
}

package com.tioka.knowledge.infrastructure.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

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

    @Test
    void searchVisibleSqlPushesAclAndWorkspace() {
        String sql = new KbChunkSqlProvider().searchVisible(Map.of(
                "kbIds", java.util.List.of("kb1", "kb2"),
                "vfAll", false,
                "vfSql", "b.id IN (SELECT resource_id FROM sys_resource_acl WHERE 1=1)",
                "workspaceId", "ws",
                "vectorLiteral", "[0,1]",
                "k", 5));
        assertTrue(sql.contains("JOIN kb_base b"));
        assertTrue(sql.contains("c.workspace_id = #{workspaceId}"));
        assertTrue(sql.contains("<=>"));
        assertTrue(sql.contains("sys_resource_acl"));
        assertTrue(sql.contains("LIMIT #{k}"));
    }
}

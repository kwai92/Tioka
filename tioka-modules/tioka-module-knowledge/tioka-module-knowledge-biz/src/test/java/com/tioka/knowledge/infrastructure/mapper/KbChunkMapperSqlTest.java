package com.tioka.knowledge.infrastructure.mapper;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H2 跳过 pgvector SQL 执行；断言 Mapper XML 近邻 SQL 契约。 */
class KbChunkMapperSqlTest {

    @Test
    void searchNearestSqlUsesPgvectorOperator() throws Exception {
        String xml = mapperXml();
        assertTrue(xml.contains("id=\"searchNearest\""));
        assertTrue(xml.contains("embedding_vec"));
        assertTrue(xml.contains("<=>"));
        assertTrue(xml.contains("CAST(#{vectorLiteral} AS vector)"));
        assertTrue(xml.contains("LIMIT #{k}"));
    }

    @Test
    void searchVisibleSqlPushesAclAndWorkspace() throws Exception {
        String xml = mapperXml();
        assertTrue(xml.contains("id=\"searchVisible\""));
        assertTrue(xml.contains("JOIN kb_base b"));
        assertTrue(xml.contains("c.workspace_id = #{workspaceId}"));
        assertTrue(xml.contains("<=>"));
        assertTrue(xml.contains("${vfSql}"));
        assertTrue(xml.contains("LIMIT #{k}"));
        assertFalse(xml.contains("SelectProvider"), "禁止注解 SQL Provider");
    }

    private static String mapperXml() throws Exception {
        Path path = Path.of("src/main/resources/mapper/KbChunkMapper.xml");
        if (!Files.isRegularFile(path)) {
            path = Path.of("tioka-modules/tioka-module-knowledge/tioka-module-knowledge-biz/src/main/resources/mapper/KbChunkMapper.xml");
        }
        return Files.readString(path);
    }
}

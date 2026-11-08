package com.tioka.server;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestContainers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("it")
class FlywayMigrationIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void migratesFromEmpty() {
        List<String> versions = jdbc.query(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
                (rs, i) -> rs.getString(1));
        Set<String> set = Set.copyOf(versions);
        assertTrue(set.contains("1"), "missing V1: " + versions);
        for (String v : List.of("100", "101", "102", "103", "104", "105", "106", "107")) {
            assertTrue(set.contains(v), "missing V" + v + ": " + versions);
        }
    }

    @Test
    void expectedTablesExist() {
        Set<String> tables = jdbc.query(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                        (rs, i) -> rs.getString(1))
                .stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        for (String t : List.of(
                "runtime_turn", "runtime_item", "runtime_approval",
                "sys_member", "audit_event", "gov_quota", "model_turn_freeze")) {
            assertTrue(tables.contains(t), "missing table " + t + "; have=" + tables);
        }
        assertTrue(TestContainers.PG.isRunning());
    }

    /** MH-01 / V120：会话级冻结表已删除。 */
    @Test
    void modelSessionFreezeDropped() {
        Set<String> tables = jdbc.query(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                        (rs, i) -> rs.getString(1))
                .stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        assertTrue(!tables.contains("model_session_freeze"),
                "V120 后不应再有 model_session_freeze");
        List<String> versions = jdbc.query(
                "SELECT version FROM flyway_schema_history WHERE success = true",
                (rs, i) -> rs.getString(1));
        assertTrue(versions.contains("120"), "missing V120: " + versions);
    }

    /** MH-05 / V121：cost_event 已由 model_usage 取代并删除。 */
    @Test
    void costEventDropped() {
        Set<String> tables = jdbc.query(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                        (rs, i) -> rs.getString(1))
                .stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        assertTrue(!tables.contains("cost_event"), "V121 后不应再有 cost_event");
        assertTrue(tables.contains("model_usage"), "model_usage 应存在");
        List<String> versions = jdbc.query(
                "SELECT version FROM flyway_schema_history WHERE success = true",
                (rs, i) -> rs.getString(1));
        assertTrue(versions.contains("121"), "missing V121: " + versions);
    }

    /** GOV-03 / V122：sys_quota 已由 gov_quota 取代并删除。 */
    @Test
    void sysQuotaDropped() {
        Set<String> tables = jdbc.query(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                        (rs, i) -> rs.getString(1))
                .stream()
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        assertTrue(!tables.contains("sys_quota"), "V122 后不应再有 sys_quota");
        assertTrue(tables.contains("gov_quota"), "gov_quota 应存在");
        List<String> versions = jdbc.query(
                "SELECT version FROM flyway_schema_history WHERE success = true",
                (rs, i) -> rs.getString(1));
        assertTrue(versions.contains("122"), "missing V122: " + versions);
    }

    /** KNW-01 / V119：统一向量维度，embedding TEXT 已删。 */
    @Test
    void embeddingDimUnified() {
        List<String> versions = jdbc.query(
                "SELECT version FROM flyway_schema_history WHERE success = true",
                (rs, i) -> rs.getString(1));
        assertTrue(versions.contains("119"), "missing V119: " + versions);
        Integer embeddingCols = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'kb_chunk' AND column_name = 'embedding'
                """,
                Integer.class);
        assertEquals(0, embeddingCols, "V119 后 kb_chunk.embedding TEXT 应已删除");
        Integer bindingCol = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'kb_base'
                  AND column_name = 'embedding_binding_id'
                """,
                Integer.class);
        assertEquals(1, bindingCol, "kb_base.embedding_binding_id 应存在");
    }

    /** KNW-02 / V118：检索 ACL 列。 */
    @Test
    void knowledgeAclColumns() {
        List<String> versions = jdbc.query(
                "SELECT version FROM flyway_schema_history WHERE success = true",
                (rs, i) -> rs.getString(1));
        assertTrue(versions.contains("118"), "missing V118: " + versions);
        Integer chunkWs = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'kb_chunk'
                  AND column_name = 'workspace_id'
                """,
                Integer.class);
        assertEquals(1, chunkWs, "kb_chunk.workspace_id 应存在");
        Integer docAcl = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'kb_document'
                  AND column_name = 'acl_principals'
                """,
                Integer.class);
        assertEquals(1, docAcl, "kb_document.acl_principals 应存在（M1 建列不启用）");
    }
}

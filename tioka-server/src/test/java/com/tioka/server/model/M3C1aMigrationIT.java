package com.tioka.server.model;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M3 批次 C-1a（MH-08）：V150 → 种子数据 → V151/V156/V222 全链迁移 IT。
 * 两段式执行 Flyway：先迁到 V150，播种存量对象与 model_default_plan，再放开到最新，
 * 断言：重命名、四类对象第 1 版登记、指针回填、sys.* 系统别名、V156 删表、V222 版本列。
 */
@Tag("it")
class M3C1aMigrationIT {

    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("tioka_mh08")
            .withUsername("tioka")
            .withPassword("tioka");

    @Test
    void v151MigratesAndV156DropsDefaultPlan() throws Exception {
        PG.start();
        Flyway first = Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .placeholders(java.util.Map.of("embeddingDim", "32"))
                .target("150")
                .load();
        first.migrate();
        seedLegacyRows();
        // 新实例：目标放开到最新（V151 Java 迁移 / V156 / V222）
        Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration")
                .placeholders(java.util.Map.of("embeddingDim", "32"))
                .load()
                .migrate();

        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
            assertRenamedAndColumns(c);
            assertFirstVersionsRegistered(c);
            assertPointersBackfilled(c);
            assertSysAliases(c);
            assertDefaultPlanDropped(c);
            assertUsageVersionColumns(c);
            assertChangesRecorded(c);
        }
    }

    // ------------------------------------------------------------- seeds

    private void seedLegacyRows() throws SQLException {
        try (Connection c = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())) {
            exec(c, "INSERT INTO model_provider (id, name, base_url, api_key_ref) "
                    + "VALUES ('prv_1', 'prov', 'http://prov', 'ref_1')");
            exec(c, "INSERT INTO model_logical (id, name, capability_tags) VALUES ('lm_1', 'legacy-m', 'tool')");
            exec(c, "INSERT INTO model_binding (id, logical_model_id, provider_id, model_name, kind, "
                    + "input_price_micros, output_price_micros, enabled) "
                    + "VALUES ('bnd_1', 'lm_1', 'prv_1', 'm1', 'chat', 100, 200, TRUE)");
            exec(c, "INSERT INTO model_routing_plan (id, name, type, primary_binding_id, published, fallback_chain) "
                    + "VALUES ('rp_1', 'r1', 'FIXED', 'bnd_1', TRUE, 'bnd_1')");
            exec(c, "INSERT INTO model_default_plan (purpose, routing_plan_id, binding_id) "
                    + "VALUES ('CHAT', 'rp_1', NULL)");
            exec(c, "INSERT INTO model_default_plan (purpose, routing_plan_id, binding_id) "
                    + "VALUES ('EMBEDDING', NULL, 'bnd_1')");
        }
    }

    // ------------------------------------------------------------- asserts

    private void assertRenamedAndColumns(Connection c) throws SQLException {
        assertTrue(tableExists(c, "model_alias"), "V151 后应有 model_alias 表");
        assertFalse(tableExists(c, "model_logical"), "V151 后 model_logical 应已重命名");
        Set<String> cols = columns(c, "model_alias");
        for (String col : new String[]{"purpose", "target_type", "target_id",
                "current_version_id", "current_version_no"}) {
            assertTrue(cols.contains(col), "model_alias 缺少列：" + col);
        }
    }

    private void assertFirstVersionsRegistered(Connection c) throws SQLException {
        for (String[] spec : new String[][]{
                {"model_provider", "prv_1"}, {"model_binding", "bnd_1"},
                {"model_routing_plan", "rp_1"}}) {
            assertEquals(1, count(c, "SELECT COUNT(*) FROM cfg_version WHERE object_type = ? AND object_id = ? "
                    + "AND version_no = 1 AND status = 'PUBLISHED'", spec[0], spec[1]),
                    "缺第 1 版：" + spec[0] + "/" + spec[1]);
        }
        // spec 内容来自行数据（provider 含密钥引用、binding 含 kind）
        String bindingSpec = string(c,
                "SELECT spec::text FROM cfg_version WHERE object_type = 'model_binding' AND object_id = 'bnd_1'");
        assertTrue(bindingSpec.contains("m1") && bindingSpec.contains("chat"), "binding spec 应来自行数据");
    }

    private void assertPointersBackfilled(Connection c) throws SQLException {
        for (String[] spec : new String[][]{
                {"model_provider", "prv_1"}, {"model_binding", "bnd_1"},
                {"model_routing_plan", "rp_1"}}) {
            assertEquals(1, count(c, "SELECT COUNT(*) FROM " + spec[0]
                            + " WHERE id = ? AND current_version_no = 1 AND current_version_id IS NOT NULL",
                    spec[1]),
                    "指针未回填：" + spec[0]);
        }
    }

    private void assertSysAliases(Connection c) throws SQLException {
        for (String[] spec : new String[][]{
                {"alias_sys_chat", "sys.chat", "CHAT", "routing_plan", "rp_1"},
                {"alias_sys_embedding", "sys.embedding", "EMBEDDING", "binding", "bnd_1"}}) {
            assertEquals(1, count(c,
                    "SELECT COUNT(*) FROM model_alias WHERE id = ? AND name = ? AND purpose = ? "
                            + "AND target_type = ? AND target_id = ? AND current_version_no = 1",
                    spec[0], spec[1], spec[2], spec[3], spec[4]),
                    "sys.* 别名行缺失或不完整：" + spec[1]);
        }
        // 原 logical model 行保留（现为无指向别名）并登记第 1 版
        assertEquals(1, count(c, "SELECT COUNT(*) FROM model_alias WHERE id = 'lm_1' AND name = 'legacy-m'"));
        assertEquals(1, count(c, "SELECT COUNT(*) FROM cfg_version WHERE object_type = 'model_alias' "
                + "AND object_id = 'lm_1' AND version_no = 1"));
    }

    private void assertDefaultPlanDropped(Connection c) throws SQLException {
        assertFalse(tableExists(c, "model_default_plan"), "V156 后 model_default_plan 应被删除");
    }

    private void assertUsageVersionColumns(Connection c) throws SQLException {
        Set<String> cols = columns(c, "model_usage");
        for (String col : new String[]{"binding_version_no", "alias_version_no", "plan_version_no"}) {
            assertTrue(cols.contains(col), "model_usage 缺少版本列：" + col);
        }
    }

    private void assertChangesRecorded(Connection c) throws SQLException {
        assertTrue(count(c, "SELECT COUNT(*) FROM cfg_change WHERE object_type = 'model_alias' "
                + "AND target_key = 'alias_sys_chat' AND after_json::text LIKE '%model_default_plan%'") >= 1,
                "sys.* 转换必须留 cfg_change");
        assertTrue(count(c, "SELECT COUNT(*) FROM cfg_change WHERE object_type = 'model_provider' "
                + "AND target_key = 'prv_1' AND after_json::text LIKE '%register%'") >= 1,
                "第 1 版登记必须留 cfg_change");
    }

    // ------------------------------------------------------------- helpers

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static boolean tableExists(Connection c, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static Set<String> columns(Connection c, String table) throws SQLException {
        Set<String> out = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    private static int count(Connection c, String sql, String... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static String string(Connection c, String sql, String... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}

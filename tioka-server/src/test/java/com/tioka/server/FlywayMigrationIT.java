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
                "sys_member", "audit_event", "gov_quota")) {
            assertTrue(tables.contains(t), "missing table " + t + "; have=" + tables);
        }
        assertTrue(TestContainers.PG.isRunning());
    }
}

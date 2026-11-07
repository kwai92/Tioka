package com.tioka.server;

import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M0-P2：JVM 与 JDBC 会话时区均为 UTC（ADR-029 / M0-01 ENG-11）。
 */
@SpringBootTest
@Tag("it")
class TimeZoneIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void jvmAndSessionAreUtc() {
        assertEquals("UTC", TimeZone.getDefault().getID());
        String zone = jdbc.queryForObject("SHOW timezone", String.class);
        assertEquals("UTC", zone);
    }
}

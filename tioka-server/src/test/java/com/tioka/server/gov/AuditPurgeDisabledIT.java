package com.tioka.server.gov;

import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.system.application.audit.AuditForwarder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OBS-01：默认关闭物理 purge，过期已转发行不得被删。
 */
@SpringBootTest
@Tag("it")
class AuditPurgeDisabledIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.audit.physical-purge-enabled", () -> "false");
    }

    @Autowired AuditForwarder forwarder;
    @Autowired JdbcTemplate jdbc;

    @Test
    void purgeExpiredIsNoOpWhenDisabled() {
        String id = "aud_purge_" + UUID.randomUUID().toString().substring(0, 8);
        Instant old = Instant.now().minus(400, ChronoUnit.DAYS);
        jdbc.update("""
                INSERT INTO audit_event
                  (id, ts, workspace_id, actor_type, actor_id, action, outcome, forwarded)
                VALUES (?, ?, 'ws-purge', 'user', 'u1', 'obs01.purge.probe', 'SUCCESS', true)
                """, id, java.sql.Timestamp.from(old));

        forwarder.purgeExpired();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE id = ?", Integer.class, id);
        assertEquals(1, count, "physical-purge-enabled=false 时不得删除历史审计行");
    }
}

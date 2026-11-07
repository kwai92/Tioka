package com.tioka.server.gov;

import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OBS-01：审计自动补齐 trace_id / request_id；未信任代理时忽略伪造 XFF。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("it")
class AuditContextIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;

    @Test
    void auditedWriteFillsTraceAndRequestId() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        String saBody = """
                {"name":"sa-%s","workspaceId":"%s","role":"member"}
                """.formatted(UUID.randomUUID().toString().substring(0, 8), admin.workspaceId());
        mockMvc.perform(admin.auth(post("/api/system/service-accounts"))
                        .header("X-Request-Id", "req-it-obs01-safe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saBody))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT trace_id, request_id FROM audit_event
                WHERE action = 'service_account.create'
                ORDER BY ts DESC LIMIT 1
                """);
        assertNotNull(row.get("trace_id"));
        assertFalse(String.valueOf(row.get("trace_id")).isBlank());
        assertEquals("req-it-obs01-safe", String.valueOf(row.get("request_id")));
    }

    @Test
    void forgedXffIgnoredWhenProxyUntrusted() throws Exception {
        String forged = "203.0.113.9";
        mockMvc.perform(post("/api/system/auth/login")
                        .header("X-Forwarded-For", forged)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"__no_such_user__","password":"wrong-password-xx"}
                                """))
                .andExpect(status().is4xxClientError());

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT client_ip FROM audit_event
                WHERE action = 'auth.login'
                ORDER BY ts DESC LIMIT 1
                """);
        assertNotEquals(forged, String.valueOf(row.get("client_ip")));
    }
}

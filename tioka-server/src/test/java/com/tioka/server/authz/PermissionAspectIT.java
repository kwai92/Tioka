package com.tioka.server.authz;

import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditOutcome;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("it")
class PermissionAspectIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired TestFixtures fixtures;
    @Autowired AuditApi audit;

    @Test
    void viewerCannotWrite() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        TestFixtures.Actor viewer = fixtures.newLimitedUser(admin, "viewer");

        mockMvc.perform(viewer.auth(post("/api/agents"))
                        .contentType("application/json")
                        .content("""
                                {"name":"nope","workspaceId":"%s"}
                                """.formatted(admin.workspaceId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        List<?> denied = audit.query(new AuditApi.AuditQuery(
                admin.workspaceId(), viewer.userId(), null, null, null,
                AuditOutcome.DENIED, null, null, null, 50));
        assertTrue(denied.stream().anyMatch(e -> true), "应有 DENIED 审计");
        assertTrue(!denied.isEmpty());
    }
}

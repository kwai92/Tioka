package com.tioka.server.auth;

import com.tioka.agent.infrastructure.AgentRepository;
import com.tioka.agent.infrastructure.persistence.AgentDO;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Tag("it")
class ScopeInterceptorIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired AgentRepository agents;
    @Autowired PrincipalHolder principals;

    @Test
    void selectAddsWorkspace() {
        String wsA = Ids.newId("ws");
        String wsB = Ids.newId("ws");
        String id = Ids.newId("agt");

        principals.runAs(user("1", wsA), () -> {
            AgentDO row = new AgentDO();
            row.setId(id);
            row.setName("a");
            row.setEnabled(true);
            agents.upsertAgent(row);
            assertEquals(wsA, agents.findAgent(id).getWorkspaceId());
            assertEquals("1", agents.findAgent(id).getOwnerId());
        });

        principals.runAs(user("1", wsB), () -> {
            List<AgentDO> listed = agents.listAgents();
            assertTrue(listed.stream().noneMatch(a -> id.equals(a.getId())),
                    "list leaked: " + listed.stream().map(a -> a.getId() + "@" + a.getWorkspaceId()).toList());
            assertNull(agents.findAgent(id), "selectById leaked workspace=" +
                    (agents.findAgent(id) == null ? null : agents.findAgent(id).getWorkspaceId()));
        });
    }

    @Test
    void noPrincipalFails() {
        Exception ex = assertThrows(Exception.class, () -> agents.listAgents());
        Throwable t = ex;
        while (t.getCause() != null && !(t instanceof IllegalStateException)) {
            t = t.getCause();
        }
        assertTrue(t instanceof IllegalStateException, "got " + ex);
        assertTrue(t.getMessage().contains("Principal") || t.getMessage().contains("workspace"), t.getMessage());
    }

    @Test
    void insertFillsColumns() {
        String ws = Ids.newId("ws");
        String id = Ids.newId("agt");
        principals.runAs(user("99", ws), () -> {
            AgentDO row = new AgentDO();
            row.setId(id);
            row.setName("filled");
            row.setEnabled(true);
            agents.upsertAgent(row);
            AgentDO saved = agents.findAgent(id);
            assertEquals(ws, saved.getWorkspaceId());
            assertEquals("99", saved.getOwnerId());
        });
    }

    private static Principal user(String uid, String ws) {
        return new Principal(uid, ws, PrincipalType.USER, Set.of("member"), Set.of("*"), "test");
    }
}

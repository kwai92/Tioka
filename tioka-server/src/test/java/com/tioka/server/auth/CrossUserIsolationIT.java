package com.tioka.server.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.infrastructure.AgentRepository;
import com.tioka.agent.infrastructure.persistence.AgentDO;
import com.tioka.chat.infrastructure.ChatRepository;
import com.tioka.chat.infrastructure.persistence.ChatConversationDO;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import com.tioka.knowledge.infrastructure.persistence.KbBaseDO;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Tag("it")
class CrossUserIsolationIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired AgentRepository agents;
    @Autowired KnowledgeRepository knowledge;
    @Autowired ChatRepository chats;
    @Autowired PrincipalHolder principals;

    @Test
    void twoUsersCannotSeeEachOther() {
        String wsA = Ids.newId("ws");
        String wsB = Ids.newId("ws");
        String agentId = Ids.newId("agt");
        String kbId = Ids.newId("kb");

        principals.runAs(user("10", wsA), () -> {
            AgentDO a = new AgentDO();
            a.setId(agentId);
            a.setName("a");
            a.setEnabled(true);
            agents.upsertAgent(a);

            KbBaseDO kb = new KbBaseDO();
            kb.setId(kbId);
            kb.setName("kb-a");
            knowledge.insertBase(kb);
            assertTrue(knowledge.hasBase(kbId));
        });

        principals.runAs(user("20", wsB), () -> {
            assertNull(agents.findAgent(agentId));
            assertTrue(agents.listAgents().stream().noneMatch(x -> agentId.equals(x.getId())));
            assertFalse(knowledge.hasBase(kbId));
        });
    }

    @Test
    void sameWorkspacePrivateConversation() {
        String ws = Ids.newId("ws");
        String convId = Ids.newId("conv");

        principals.runAs(user("10", ws), () -> {
            ChatConversationDO c = new ChatConversationDO();
            c.setId(convId);
            c.setTitle("private");
            c.setAgentVersionId("av_x");
            c.setRuntimeSessionId(Ids.newId("thr"));
            chats.insertConversation(c);
            assertNotNull(chats.findConversation(convId));
            assertTrue(chats.listConversations().stream().anyMatch(x -> convId.equals(x.getId())));
        });

        // 同空间另一用户：看得到 Agent，看不到会话
        principals.runAs(user("20", ws), () -> {
            assertNull(chats.findConversation(convId));
            assertTrue(chats.listConversations().stream().noneMatch(x -> convId.equals(x.getId())));
        });
    }

    private static Principal user(String uid, String ws) {
        return new Principal(uid, ws, PrincipalType.USER, Set.of("member"), Set.of("*"), "test");
    }
}

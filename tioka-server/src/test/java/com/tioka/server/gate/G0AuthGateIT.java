package com.tioka.server.gate;

import com.tioka.agent.infrastructure.AgentRepository;
import com.tioka.agent.infrastructure.persistence.AgentDO;
import com.tioka.chat.infrastructure.ChatRepository;
import com.tioka.chat.infrastructure.persistence.ChatConversationDO;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.framework.security.PublicApi;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import com.tioka.knowledge.infrastructure.persistence.KbBaseDO;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 闸门 G0：FND-01、FND-03。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("gate")
class G0AuthGateIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping;
    @Autowired AgentRepository agents;
    @Autowired KnowledgeRepository knowledge;
    @Autowired ChatRepository chats;
    @Autowired PrincipalHolder principals;

    @Test
    void allApiRequireLogin() throws Exception {
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
            if (e.getValue().getMethodAnnotation(PublicApi.class) != null
                    || e.getValue().getBeanType().getAnnotation(PublicApi.class) != null) {
                continue;
            }
            Set<String> patterns = e.getKey().getPatternValues();
            Set<RequestMethod> methods = e.getKey().getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                if (!pattern.startsWith("/api/")) {
                    continue;
                }
                String path = pattern.replaceAll("\\{[^}]+}", "x");
                RequestMethod method = methods.isEmpty() ? RequestMethod.GET : methods.iterator().next();
                mockMvc.perform(request(method.asHttpMethod(), path))
                        .andExpect(status().isUnauthorized());
            }
        }
        mockMvc.perform(request(org.springframework.http.HttpMethod.GET, "/api/system/auth/me")
                        .queryParam("token", "tka_fake"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void usersCannotSeeEachOther() {
        String wsA = Ids.newId("ws");
        String wsB = Ids.newId("ws");
        String agentId = Ids.newId("agt");
        String kbId = Ids.newId("kb");
        String convId = Ids.newId("conv");

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

            ChatConversationDO c = new ChatConversationDO();
            c.setId(convId);
            c.setTitle("private");
            c.setAgentVersionId("av_x");
            c.setRuntimeSessionId(Ids.newId("thr"));
            chats.insertConversation(c);

            assertNotNull(agents.findAgent(agentId));
            assertTrue(knowledge.hasBase(kbId));
            assertNotNull(chats.findConversation(convId));
        });

        principals.runAs(user("20", wsB), () -> {
            assertNull(agents.findAgent(agentId));
            assertFalse(knowledge.hasBase(kbId));
            assertNull(chats.findConversation(convId));
        });
    }

    private static Principal user(String uid, String ws) {
        return new Principal(uid, ws, PrincipalType.USER, Set.of("member"), Set.of("*"), "test");
    }
}

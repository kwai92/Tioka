package com.tioka.server.auth;

import com.tioka.agent.infrastructure.AgentRepository;
import com.tioka.agent.infrastructure.persistence.AgentDO;
import com.tioka.chat.infrastructure.ChatRepository;
import com.tioka.chat.infrastructure.persistence.ChatConversationDO;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import com.tioka.knowledge.infrastructure.persistence.KbBaseDO;
import com.tioka.runtime.api.kernel.RuntimeApi;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.runtime.infrastructure.RuntimeSessionRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeSessionDO;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeTurnDO;
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
    @Autowired RuntimeApi runtime;
    @Autowired RuntimeSessionRepository sessions;
    @Autowired TurnRepository turns;

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

    @Test
    void interruptAndSteerOtherUsersTurnReturns404() {
        String wsA = Ids.newId("ws");
        String wsB = Ids.newId("ws");
        String threadId = Ids.newId("thr");
        String turnId = Ids.newId("turn");

        principals.runAs(user("10", wsA), () -> {
            RuntimeSessionDO s = new RuntimeSessionDO();
            s.setId(threadId);
            s.setStatus("OPEN");
            s.setAgentVersionId("av_x");
            s.setWorkspaceId(wsA);
            s.setOwnerId("10");
            s.setLastItemSeq(0L);
            s.setOrigin("chat");
            s.setDepth(0);
            sessions.upsert(s);

            RuntimeTurnDO t = new RuntimeTurnDO();
            t.setId(turnId);
            t.setSessionId(threadId);
            t.setSeq(1);
            t.setStatus(TurnStatus.QUEUED.name());
            t.setOrigin("chat");
            t.setRunAsJson("{\"userId\":\"10\",\"workspaceId\":\"" + wsA + "\",\"type\":\"USER\"}");
            t.setStepCount(0);
            t.setLeaseVersion(0L);
            turns.insert(t);
        });

        BizException interruptEx = assertThrows(BizException.class,
                () -> principals.runAs(user("20", wsB), () -> runtime.interrupt(turnId)));
        assertEquals(ErrorCode.NOT_FOUND, interruptEx.getErrorCode());

        BizException steerEx = assertThrows(BizException.class,
                () -> principals.runAs(user("20", wsB), () -> runtime.steer(turnId, "hi")));
        assertEquals(ErrorCode.NOT_FOUND, steerEx.getErrorCode());

        // 所有者可以取消
        principals.runAs(user("10", wsA), () -> runtime.interrupt(turnId));
        assertEquals(TurnStatus.CANCELLED.name(), turns.findById(turnId).orElseThrow().getStatus());
    }

    private static Principal user(String uid, String ws) {
        return new Principal(uid, ws, PrincipalType.USER, Set.of("member"), Set.of("*"), "test");
    }
}

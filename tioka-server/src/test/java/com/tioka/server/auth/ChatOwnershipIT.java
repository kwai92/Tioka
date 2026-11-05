package com.tioka.server.auth;

import com.tioka.chat.application.ChatService;
import com.tioka.chat.infrastructure.ChatRepository;
import com.tioka.chat.infrastructure.persistence.ChatConversationDO;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
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

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Tag("it")
class ChatOwnershipIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired ChatRepository chats;
    @Autowired ChatService chatService;
    @Autowired PrincipalHolder principals;

    @Test
    void deleteOthersConversation404() {
        String ws = Ids.newId("ws");
        String convId = Ids.newId("conv");

        principals.runAs(user("10", ws), () -> {
            ChatConversationDO c = new ChatConversationDO();
            c.setId(convId);
            c.setTitle("mine");
            c.setAgentVersionId("av_x");
            c.setRuntimeSessionId(Ids.newId("thr"));
            chats.insertConversation(c);
            assertNotNull(chats.findConversation(convId));
        });

        BizException ex = assertThrows(BizException.class,
                () -> principals.runAs(user("20", ws), () -> chatService.delete(convId)));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());

        principals.runAs(user("10", ws), () -> {
            assertNotNull(chats.findConversation(convId));
            chatService.delete(convId);
            assertNull(chats.findConversation(convId));
        });
    }

    @Test
    void getOthersConversation404() {
        String ws = Ids.newId("ws");
        String convId = Ids.newId("conv");

        principals.runAs(user("10", ws), () -> {
            ChatConversationDO c = new ChatConversationDO();
            c.setId(convId);
            c.setTitle("private");
            c.setAgentVersionId("av_x");
            c.setRuntimeSessionId(Ids.newId("thr"));
            chats.insertConversation(c);
        });

        BizException ex = assertThrows(BizException.class,
                () -> principals.runAs(user("20", ws), () -> chatService.get(convId)));
        assertEquals(ErrorCode.NOT_FOUND, ex.getErrorCode());
    }

    private static Principal user(String uid, String ws) {
        return new Principal(uid, ws, PrincipalType.USER, Set.of("member"), Set.of("chat:use"), "test");
    }
}

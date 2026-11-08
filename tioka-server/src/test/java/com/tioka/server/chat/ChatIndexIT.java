package com.tioka.server.chat;

import com.tioka.chat.application.ChatService;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.runtime.api.kernel.TurnStatus;
import com.tioka.server.gate.support.DualInstance;
import com.tioka.server.gate.support.GateSeed;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CHT-03：Turn 完成后会话 last_preview 由 ConversationIndexListener 回写。
 */
@Tag("it")
class ChatIndexIT {

    @Test
    void previewUpdatedAfterTurn() throws Exception {
        try (DualInstance di = DualInstance.start("chat-index-preview", Map.of(), false)) {
            // DualInstance.start 不会预载自定义剧本名；补载
            di.llm().loadClasspath("llm-scripts/chat-index-preview.yaml", "chat-index-preview");
            System.setProperty("tioka.llm.script", "chat-index-preview");

            GateSeed.Bundle bundle = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
            PrincipalHolder principals = di.a().getBean(PrincipalHolder.class);
            ChatService chat = di.a().getBean(ChatService.class);
            JdbcTemplate jdbc = di.a().getBean(JdbcTemplate.class);

            var created = principals.runAs(GateSeed.user(bundle.userId(), bundle.workspaceId()), () ->
                    chat.create("index-preview", bundle.agentVersionId()));
            var sent = principals.runAs(GateSeed.user(bundle.userId(), bundle.workspaceId()), () ->
                    chat.send(created.id(), "hi preview", "cm-preview-1"));

            var done = GateSeed.awaitStatus(di.a(), bundle, sent.turnId(), 30_000, TurnStatus.COMPLETED);
            assertEquals(TurnStatus.COMPLETED, done.status(), () -> done.errorMessage());

            String preview = null;
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                preview = jdbc.queryForObject(
                        "SELECT last_preview FROM chat_conversation WHERE id = ?",
                        String.class, created.id());
                if (preview != null && !preview.isBlank()) {
                    break;
                }
                Thread.sleep(40);
            }
            assertTrue(preview != null && preview.contains("preview-hello-from-assistant"),
                    "last_preview 应包含助手文本, actual=" + preview);
        }
    }
}

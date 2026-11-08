package com.tioka.connector.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.connector.support.DirectEgress;
import com.tioka.connector.support.FeishuOpenApiStub;
import com.tioka.connector.support.InMemoryImOutboxRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeishuImChannelAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sendTextAndCardViaOpenApiWithTokenCache() throws Exception {
        try (FeishuOpenApiStub stub = FeishuOpenApiStub.start()) {
            stub.enqueueTokenFromResource("feishu/tenant_access_token.json");
            stub.enqueueMessageFromResource("feishu/im_message_text_ok.json");
            stub.enqueueMessageFromResource("feishu/im_message_card_ok.json");

            InMemoryImOutboxRepository outbox = new InMemoryImOutboxRepository();
            FeishuImChannelAdapter adapter = new FeishuImChannelAdapter(
                    outbox, new DirectEgress(),
                    "", "cli_mock_app", "mock_secret",
                    stub.baseUrl(), "chat_id", mapper);

            adapter.sendText("oc_mock_chat", "hello text");
            adapter.sendCard("oc_mock_chat", Map.of("header", Map.of("title", "card")));

            assertEquals(2, adapter.outbox().size());
            assertEquals(1, stub.tokenCalls(), "token 应缓存，两次发送只刷新一次");
            assertEquals(2, stub.messageCalls());
            assertTrue(stub.messageBodies().get(0).contains("\"msg_type\":\"text\""));
            assertTrue(stub.messageBodies().get(1).contains("\"msg_type\":\"interactive\""));
        }
    }

    @Test
    void rateLimit429RejectsAfterOutboxPersist() throws Exception {
        try (FeishuOpenApiStub stub = FeishuOpenApiStub.start()) {
            stub.enqueueTokenFromResource("feishu/tenant_access_token.json");
            stub.enqueueMessage(429, read("feishu/im_message_rate_limited.json"));

            InMemoryImOutboxRepository outbox = new InMemoryImOutboxRepository();
            FeishuImChannelAdapter adapter = new FeishuImChannelAdapter(
                    outbox, new DirectEgress(),
                    "", "cli_mock_app", "mock_secret",
                    stub.baseUrl(), "chat_id", mapper);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> adapter.sendText("oc_mock_chat", "burst"));
            assertTrue(ex.getMessage().contains("feishu"));
            assertEquals(1, outbox.findByChannel("feishu").size(), "失败前仍应落 outbox");
        }
    }

    @Test
    void outboxOnlyWhenNoCredentials() {
        InMemoryImOutboxRepository outbox = new InMemoryImOutboxRepository();
        FeishuImChannelAdapter adapter = new FeishuImChannelAdapter(
                outbox, new DirectEgress(),
                "", "", "",
                "https://open.feishu.cn", "chat_id", mapper);

        adapter.sendText("oc_local", "offline");
        assertEquals("feishu", adapter.channelType());
        assertEquals(1, adapter.outbox().size());
        assertEquals("offline", adapter.outbox().get(0).get("text"));
    }

    @Test
    void webhookHostMustBeAllowed() {
        assertThrows(IllegalStateException.class,
                () -> FeishuImChannelAdapter.assertAllowedWebhook("https://evil.example/hook"));
        FeishuImChannelAdapter.assertAllowedWebhook("https://open.feishu.cn/hook");
    }

    private static String read(String classpath) throws Exception {
        try (var in = FeishuImChannelAdapterTest.class.getClassLoader().getResourceAsStream(classpath)) {
            return new String(in.readAllBytes());
        }
    }
}

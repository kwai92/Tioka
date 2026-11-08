package com.tioka.connector.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tioka.connector.support.DirectEgress;
import com.tioka.connector.support.InMemoryImOutboxRepository;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DingTalkImChannelAdapterTest {

    @Test
    void sendTextPostsWebhookAndPersistsOutbox() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/robot/send", ex -> {
            hits.incrementAndGet();
            byte[] ok = "{\"errcode\":0}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, ok.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(ok);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/robot/send";
            InMemoryImOutboxRepository outbox = new InMemoryImOutboxRepository();
            DingTalkImChannelAdapter adapter = new DingTalkImChannelAdapter(
                    outbox, new DirectEgress(), url, new ObjectMapper());

            adapter.sendText("cid-1", "ding hello");
            adapter.sendCard("cid-1", Map.of("title", "c"));

            assertEquals("dingtalk", adapter.channelType());
            assertEquals(2, adapter.outbox().size());
            assertEquals(2, hits.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectForeignWebhookHost() {
        assertThrows(IllegalStateException.class,
                () -> DingTalkImChannelAdapter.assertAllowed("https://evil.example/hook"));
        DingTalkImChannelAdapter.assertAllowed("https://oapi.dingtalk.com/robot/send?access_token=mock");
    }
}

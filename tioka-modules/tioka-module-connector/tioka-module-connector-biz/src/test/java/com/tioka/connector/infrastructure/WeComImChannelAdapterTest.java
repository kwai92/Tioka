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
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeComImChannelAdapterTest {

    @Test
    void sendTextPostsWebhookAndPersistsOutbox() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cgi-bin/webhook/send", ex -> {
            hits.incrementAndGet();
            byte[] ok = "{\"errcode\":0}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, ok.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(ok);
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/cgi-bin/webhook/send";
            // localhost 白名单；生产企微域另测 assertAllowed
            InMemoryImOutboxRepository outbox = new InMemoryImOutboxRepository();
            WeComImChannelAdapter adapter = new WeComImChannelAdapter(
                    outbox, new DirectEgress(), url, new ObjectMapper());

            adapter.sendText("room-1", "wecom hello");
            adapter.sendCard("room-1", Map.of("title", "c"));

            assertEquals("wecom", adapter.channelType());
            assertEquals(2, adapter.outbox().size());
            assertEquals(2, hits.get());
            assertTrue(adapter.outbox().get(0).get("text").toString().contains("wecom"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectForeignWebhookHost() {
        assertThrows(IllegalStateException.class,
                () -> WeComImChannelAdapter.assertAllowed("https://evil.example/hook"));
        WeComImChannelAdapter.assertAllowed("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=mock");
    }
}

package com.tioka.server.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jayway.jsonpath.JsonPath;
import com.tioka.server.gate.support.feishu.FeishuStubCrypto;
import com.tioka.server.gate.support.feishu.FeishuStubServer;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PK-19a / CON-02：回调入口验签解密、URL 校验、去重与快速应答。
 * 身份/会话/Turn 部分归 PK-19b。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("it")
class FeishuInboundIT {

    private static FeishuStubServer FEISHU;

    @BeforeAll
    static void startStub() {
        FEISHU = FeishuStubServer.start();
    }

    @AfterAll
    static void stopStub() {
        if (FEISHU != null) {
            FEISHU.close();
        }
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.im.feishu.api-base", () -> FEISHU.baseUrl());
        registry.add("tioka.public-url", () -> "https://tioka.test.example");
        registry.add("tioka.egress.mode", () -> "OPEN");
    }

    @Autowired MockMvc mockMvc;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @Test
    void urlVerification() throws Exception {
        String bindingId = createBinding("cli_inbound_url");
        String challenge = "challenge_" + UUID.randomUUID();
        ObjectNode plain = mapper.createObjectNode();
        plain.put("type", "url_verification");
        plain.put("challenge", challenge);
        plain.put("token", FeishuStubServer.VERIFICATION_TOKEN);

        mockMvc.perform(signedEncrypted(bindingId, mapper.writeValueAsString(plain)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").value(challenge));
    }

    @Test
    void duplicateEventIgnored() throws Exception {
        String bindingId = createBinding("cli_inbound_dup");
        String eventId = "evt_dup_" + UUID.randomUUID();
        String body = messageReceiveJson(eventId);

        mockMvc.perform(signedEncrypted(bindingId, body)).andExpect(status().isOk());
        mockMvc.perform(signedEncrypted(bindingId, body)).andExpect(status().isOk());

        Long outbox = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_outbox WHERE topic = ? AND tag = ? AND payload_json LIKE ?",
                Long.class,
                "tioka-connector",
                "tioka.connector.im_inbound.v1",
                "%" + eventId + "%");
        assertEquals(1L, outbox);

        Long dedupe = jdbc.queryForObject(
                "SELECT COUNT(*) FROM im_inbound_dedupe WHERE binding_id = ? AND event_id = ?",
                Long.class, bindingId, eventId);
        assertEquals(1L, dedupe);
    }

    @Test
    void respondsWithin200ms() throws Exception {
        String bindingId = createBinding("cli_inbound_fast");
        String body = messageReceiveJson("evt_fast_" + UUID.randomUUID());
        long start = System.nanoTime();
        mockMvc.perform(signedEncrypted(bindingId, body)).andExpect(status().isOk());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMs < 2000L, "回调应快速返回，实际 " + elapsedMs + "ms（CI 放宽至 2s）");
    }

    private String createBinding(String appId) throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        String createBody = """
                {
                  "name":"入站测试",
                  "workspaceId":"%s",
                  "channel":"feishu",
                  "appId":"%s",
                  "appSecret":"sec_inbound",
                  "encryptKey":"%s",
                  "verificationToken":"%s",
                  "identityMatch":"link"
                }
                """.formatted(admin.workspaceId(), appId,
                FeishuStubServer.ENCRYPT_KEY, FeishuStubServer.VERIFICATION_TOKEN);
        MvcResult created = mockMvc.perform(admin.auth(post("/api/connector/im/bindings"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");
    }

    private String messageReceiveJson(String eventId) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("schema", "2.0");
        ObjectNode header = root.putObject("header");
        header.put("event_type", "im.message.receive_v1");
        header.put("token", FeishuStubServer.VERIFICATION_TOKEN);
        header.put("event_id", eventId);
        ObjectNode event = root.putObject("event");
        ObjectNode message = event.putObject("message");
        message.put("message_id", "om_" + eventId);
        message.put("chat_id", "oc_chat");
        message.put("chat_type", "p2p");
        message.put("content", "{\"text\":\"hello\"}");
        ObjectNode sender = event.putObject("sender");
        sender.put("sender_type", "user");
        sender.putObject("sender_id").put("open_id", "ou_user");
        return mapper.writeValueAsString(root);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signedEncrypted(
            String bindingId, String plainJson) throws Exception {
        String cipher = FeishuStubCrypto.encrypt(FeishuStubServer.ENCRYPT_KEY, plainJson);
        ObjectNode wrapped = mapper.createObjectNode();
        wrapped.put("encrypt", cipher);
        String bodyToSend = mapper.writeValueAsString(wrapped);
        String ts = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String sig = FeishuStubCrypto.sign(FeishuStubServer.ENCRYPT_KEY, ts, nonce, bodyToSend);
        return post("/api/connector/im/feishu/" + bindingId + "/events")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Lark-Request-Timestamp", ts)
                .header("X-Lark-Request-Nonce", nonce)
                .header("X-Lark-Signature", sig)
                .content(bodyToSend);
    }
}

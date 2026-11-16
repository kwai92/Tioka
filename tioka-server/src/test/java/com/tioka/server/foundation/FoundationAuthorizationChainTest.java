package com.tioka.server.foundation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foundation.starter.client.authz.HttpAuthorizationSnapshotClient;
import com.foundation.starter.client.http.CircuitBreaker;
import com.foundation.starter.client.http.FoundationApiVersionMismatchException;
import com.foundation.starter.client.http.FoundationHttpClient;
import com.foundation.starter.core.contract.FoundationApiVersion;
import com.tioka.framework.security.AuthorizationSnapshotSource;
import com.tioka.framework.security.FoundationPermissionEvaluator;
import com.tioka.framework.security.PermissionCodeMapper;
import com.tioka.framework.security.PermissionEvaluator;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalType;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F4 第一处真实接线的端到端验证：本地 HTTP 桩 -> 底座快照客户端 -> 应用适配器 -> 权限判定器。
 *
 * <p>不依赖 Spring 容器、Docker 或真实中台服务；每个用例都走完整的出站与判定链路。
 * 主体刻意携带本地超管权限集，用来证明接入模式绝不会回落本地授权。
 */
class FoundationAuthorizationChainTest {

    private static final String APP = "tioka";
    private static final String PERSON = "01JPERSON00000000000000000";

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void 中台授予的权限可放行_出站带版本头与服务账号令牌() throws Exception {
        enqueue(snapshot(PERSON, APP, "[\"agent:read\"]"));

        assertNull(evaluator().firstMissing(principal(), new String[]{"agent:read"}));

        RecordedRequest request = server.takeRequest();
        assertEquals("/api/fdn/apps/tioka/persons/" + PERSON + "/snapshot", request.getPath());
        assertEquals(FoundationApiVersion.CURRENT, request.getHeader(FoundationApiVersion.HEADER));
        assertEquals("Bearer svc-token", request.getHeader("Authorization"));
    }

    @Test
    void 中台未授予的权限被拒绝() {
        enqueue(snapshot(PERSON, APP, "[\"agent:read\"]"));

        assertEquals("agent:write",
                evaluator().firstMissing(principal(), new String[]{"agent:write"}));
    }

    @Test
    void 本地超管权限集不参与接入模式判定() {
        enqueue(snapshot(PERSON, APP, "[]"));

        assertEquals("model:admin",
                evaluator().firstMissing(principal(), new String[]{"model:admin"}));
    }

    @Test
    void 中台通配码不提升为超管() {
        enqueue(snapshot(PERSON, APP, "[\"*\"]"));

        assertEquals("model:admin",
                evaluator().firstMissing(principal(), new String[]{"model:admin"}));
    }

    @Test
    void 未登记的声明码直接拒绝且不回源() {
        assertEquals("typo:code",
                evaluator().firstMissing(principal(), new String[]{"typo:code"}));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void 中台不可达时一律拒绝() {
        server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        assertEquals("agent:read",
                evaluator().firstMissing(principal(), new String[]{"agent:read"}));

        server.enqueue(new MockResponse().setResponseCode(404).setBody("missing"));
        assertEquals("agent:read",
                evaluator().firstMissing(principal(), new String[]{"agent:read"}));
    }

    @Test
    void 空响应与结构非法快照按不可用拒绝() {
        server.enqueue(new MockResponse().setResponseCode(200));
        assertEquals("agent:read",
                evaluator().firstMissing(principal(), new String[]{"agent:read"}));

        enqueue(snapshot("another-person", APP, "[\"agent:read\"]"));
        assertEquals("agent:read",
                evaluator().firstMissing(principal(), new String[]{"agent:read"}));

        enqueue(snapshot(PERSON, APP, "[\"agent:read\", 7]"));
        assertEquals("agent:read",
                evaluator().firstMissing(principal(), new String[]{"agent:read"}));
    }

    @Test
    void 响应主版本错配不重试且拒绝放行() {
        server.enqueue(new MockResponse()
                .setBody(snapshot(PERSON, APP, "[\"agent:read\"]"))
                .setHeader(FoundationApiVersion.HEADER, "2"));

        assertEquals("agent:read",
                evaluator(3).firstMissing(principal(), new String[]{"agent:read"}));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void 客户端自身对版本错配抛出的异常保持可见() {
        server.enqueue(new MockResponse().setBody("{}").setHeader(FoundationApiVersion.HEADER, "9"));

        FoundationHttpClient http = foundationHttp(1);
        assertThrows(FoundationApiVersionMismatchException.class, () -> http.get("/api/fdn/apps/tioka/persons/x/snapshot"));
    }

    @Test
    void 适配器不改写中台返回的原始权限项() {
        enqueue(snapshot(PERSON, APP, "[\"agent:read\",\"unknown:code\"]"));

        AuthorizationSnapshotSource source =
                new FoundationAuthorizationSnapshotSource(
                        new HttpAuthorizationSnapshotClient(foundationHttp(1), APP));
        Set<String> raw = source.permissionsFor(PERSON);

        assertTrue(raw.contains("agent:read") && raw.contains("unknown:code"));
    }

    private PermissionEvaluator evaluator() {
        return evaluator(1);
    }

    private PermissionEvaluator evaluator(int attempts) {
        AuthorizationSnapshotSource source = new FoundationAuthorizationSnapshotSource(
                new HttpAuthorizationSnapshotClient(foundationHttp(attempts), APP));
        return new FoundationPermissionEvaluator(source,
                PermissionCodeMapper.loadDefault(new ObjectMapper()));
    }

    private FoundationHttpClient foundationHttp(int attempts) {
        return new FoundationHttpClient(new OkHttpClient(), new ObjectMapper(),
                server.url("/").toString().replaceAll("/$", ""), () -> "svc-token",
                new CircuitBreaker(50, 20, 60_000, 30_000), attempts, 1);
    }

    private Principal principal() {
        return new Principal(PERSON, "ws-1", PrincipalType.USER,
                Set.of(), Set.of("*"), "password");
    }

    private void enqueue(String body) {
        server.enqueue(new MockResponse().setBody(body)
                .setHeader("Content-Type", "application/json"));
    }

    private static String snapshot(String personId, String appCode, String permissions) {
        return "{\"personId\":\"" + personId + "\",\"appCode\":\"" + appCode + "\","
                + "\"version\":7,\"permissions\":" + permissions + "}";
    }
}

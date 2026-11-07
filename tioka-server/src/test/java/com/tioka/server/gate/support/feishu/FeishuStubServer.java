package com.tioka.server.gate.support.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ENG-04c：飞书开放平台桩（独立 HTTP 服务）。
 * <ul>
 *   <li>按飞书规则签名、加密事件并推送到目标实例回调</li>
 *   <li>录制 tenant_access_token / 发送与更新消息、卡片请求</li>
 *   <li>模拟卡片按钮回调、频控错误码、令牌失效与再颁发</li>
 * </ul>
 */
public final class FeishuStubServer implements AutoCloseable {

    public static final String VERIFICATION_TOKEN = "gate_feishu_verify_token";
    public static final String ENCRYPT_KEY = "gate_feishu_encrypt_key";

    /** 飞书通用频控。 */
    public static final int RATE_LIMIT_CODE = 99991400;
    /** 消息更新频控（M2-03）。 */
    public static final int MESSAGE_UPDATE_RATE_LIMIT_CODE = 230020;
    /** token 无效。 */
    public static final int INVALID_TOKEN_CODE = 99991663;

    private final MockWebServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<OutboundRecord> outbound = new CopyOnWriteArrayList<>();
    private final List<PushRecord> pushes = new CopyOnWriteArrayList<>();
    private final AtomicInteger messageSeq = new AtomicInteger();
    private final AtomicInteger tokenSeq = new AtomicInteger();
    private final AtomicInteger rateLimitResponses = new AtomicInteger();
    private final AtomicBoolean failNextMessageRateLimit = new AtomicBoolean(false);
    private final AtomicBoolean failNextUpdateRateLimit = new AtomicBoolean(false);
    private final Set<String> validTokens = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean tokensInvalidated = new AtomicBoolean(false);

    private FeishuStubServer(MockWebServer server) {
        this.server = server;
    }

    public static FeishuStubServer start() {
        try {
            FeishuStubServer stub = new FeishuStubServer(new MockWebServer());
            stub.server.setDispatcher(stub.dispatcher());
            stub.server.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** OpenAPI 基址，对应 {@code tioka.feishu.api-base}。 */
    public String baseUrl() {
        return server.url("/").toString().replaceAll("/$", "");
    }

    /** 本桩内建的 url_verification 回声地址。 */
    public String urlVerificationEchoUrl() {
        return baseUrl() + "/_gate/url_verification";
    }

    /** 本桩内建：验签 + 解密后回传明文，供冒烟自测加密推送。 */
    public String eventEchoUrl() {
        return baseUrl() + "/_gate/event_echo";
    }

    public List<OutboundRecord> outboundCalls() {
        return List.copyOf(outbound);
    }

    public List<OutboundRecord> outboundMessages() {
        List<OutboundRecord> out = new ArrayList<>();
        for (OutboundRecord r : outbound) {
            if (r.kind() == OutboundKind.MESSAGE_CREATE
                    || r.kind() == OutboundKind.MESSAGE_UPDATE
                    || r.kind() == OutboundKind.CARD) {
                out.add(r);
            }
        }
        return List.copyOf(out);
    }

    public List<PushRecord> pushRecords() {
        return List.copyOf(pushes);
    }

    public int tokenIssueCount() {
        return tokenSeq.get();
    }

    public int rateLimitResponses() {
        return rateLimitResponses.get();
    }

    public void clear() {
        outbound.clear();
        pushes.clear();
    }

    /** 下一次 im messages create 返回频控码。 */
    public void failNextMessageWithRateLimit() {
        failNextMessageRateLimit.set(true);
    }

    /** 下一次 im messages PATCH 返回 230020。 */
    public void failNextUpdateWithRateLimit() {
        failNextUpdateRateLimit.set(true);
    }

    /** 使已颁发的 tenant_access_token 全部失效（模拟刷新前旧令牌）。 */
    public void invalidateTenantToken() {
        tokensInvalidated.set(true);
        validTokens.clear();
    }

    /**
     * 直接调用桩的 token 接口并返回 tenant_access_token。
     */
    public String issueTenantAccessToken() throws Exception {
        String body = "{\"app_id\":\"cli_gate_smoke\",\"app_secret\":\"sec_gate_smoke\"}";
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(baseUrl() + "/open-apis/auth/v3/tenant_access_token/internal"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = json.readTree(resp.body() == null ? "{}" : resp.body());
        String token = root.path("tenant_access_token").asText(null);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("token 颁发失败: " + resp.body());
        }
        return token;
    }

    /** 用指定 Bearer 调 messages create（供频控 / 令牌断言）。 */
    public HttpResponse<String> stubPostMessage(String bearerToken, String receiveId, String text)
            throws Exception {
        String content = json.writeValueAsString(Map.of("text", text));
        String body = json.writeValueAsString(Map.of(
                "receive_id", receiveId,
                "msg_type", "text",
                "content", content));
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(baseUrl() + "/open-apis/im/v1/messages?receive_id_type=chat_id"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + bearerToken)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 构造 url_verification 载荷推送到目标；HTTP 200 且 challenge 匹配则返回 challenge。
     */
    public String pushUrlVerification(String targetUrl, String challenge) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("type", "url_verification");
        body.put("challenge", challenge);
        body.put("token", VERIFICATION_TOKEN);
        HttpResponse<String> resp = pushEvent(targetUrl, json.writeValueAsString(body), false);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("url_verification HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = json.readTree(resp.body() == null ? "{}" : resp.body());
        String echoed = root.path("challenge").asText(null);
        if (echoed == null || !echoed.equals(challenge)) {
            throw new IllegalStateException("url_verification challenge mismatch: " + resp.body());
        }
        return echoed;
    }

    /** 签名并加密后推送事件到目标。 */
    public HttpResponse<String> pushSignedEncryptedEvent(String targetUrl, String plainJson)
            throws Exception {
        return pushEvent(targetUrl, plainJson, true);
    }

    /**
     * 模拟卡片按钮回调 {@code card.action.trigger}（签名加密推送）。
     */
    public HttpResponse<String> pushCardAction(String targetUrl, Map<String, Object> actionValue)
            throws Exception {
        ObjectNode root = json.createObjectNode();
        root.put("schema", "2.0");
        ObjectNode header = root.putObject("header");
        header.put("event_type", "card.action.trigger");
        header.put("token", VERIFICATION_TOKEN);
        header.put("event_id", "evt_card_" + UUID.randomUUID());
        ObjectNode event = root.putObject("event");
        event.set("action", json.valueToTree(Map.of("value", actionValue == null ? Map.of() : actionValue)));
        return pushSignedEncryptedEvent(targetUrl, json.writeValueAsString(root));
    }

    /** 向目标推送 JSON；{@code encryptAndSign=true} 时按飞书规则包装。 */
    public HttpResponse<String> pushEvent(String targetUrl, String jsonBody, boolean encryptAndSign)
            throws Exception {
        String bodyToSend = jsonBody;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(targetUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8");
        if (encryptAndSign) {
            String cipher = FeishuStubCrypto.encrypt(ENCRYPT_KEY, jsonBody);
            ObjectNode wrapped = json.createObjectNode();
            wrapped.put("encrypt", cipher);
            bodyToSend = json.writeValueAsString(wrapped);
            String ts = String.valueOf(Instant.now().getEpochSecond());
            String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            String sig = FeishuStubCrypto.sign(ENCRYPT_KEY, ts, nonce, bodyToSend);
            builder.header("X-Lark-Request-Timestamp", ts);
            builder.header("X-Lark-Request-Nonce", nonce);
            builder.header("X-Lark-Signature", sig);
        }
        HttpRequest req = builder.POST(HttpRequest.BodyPublishers.ofString(bodyToSend)).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        pushes.add(new PushRecord(targetUrl, bodyToSend, resp.statusCode(),
                resp.body() == null ? "" : resp.body()));
        return resp;
    }

    private Dispatcher dispatcher() {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                String method = request.getMethod() == null ? "" : request.getMethod().toUpperCase(Locale.ROOT);
                String body = bodyUtf8(request);
                try {
                    if (path.startsWith("/_gate/url_verification") && "POST".equals(method)) {
                        return urlVerificationResponse(body);
                    }
                    if (path.startsWith("/_gate/event_echo") && "POST".equals(method)) {
                        return eventEchoResponse(request, body);
                    }
                    if (path.startsWith("/open-apis/auth/v3/tenant_access_token/internal")
                            && "POST".equals(method)) {
                        return tokenResponse(method, path, body);
                    }
                    if (path.startsWith("/open-apis/im/v1/messages/") && path.contains("/reply")
                            && "POST".equals(method)) {
                        return messageCreateResponse(request, method, path, body, OutboundKind.MESSAGE_CREATE);
                    }
                    if (path.startsWith("/open-apis/im/v1/messages/") && "PATCH".equals(method)) {
                        return messageUpdateResponse(request, method, path, body);
                    }
                    if (path.startsWith("/open-apis/im/v1/messages") && "POST".equals(method)) {
                        OutboundKind kind = body != null && body.contains("\"msg_type\":\"interactive\"")
                                ? OutboundKind.CARD
                                : OutboundKind.MESSAGE_CREATE;
                        return messageCreateResponse(request, method, path, body, kind);
                    }
                    if (path.startsWith("/open-apis/contact/v3/users/") && "GET".equals(method)) {
                        outbound.add(new OutboundRecord(OutboundKind.CONTACT, method, path, body));
                        return jsonOk("""
                                {"code":0,"msg":"ok","data":{"user":{"email":"stub@example.com","open_id":"ou_stub"}}}
                                """);
                    }
                    if (path.startsWith("/open-apis/authen/v2/oauth/token") && "POST".equals(method)) {
                        outbound.add(new OutboundRecord(OutboundKind.TOKEN, method, path, body));
                        return jsonOk("""
                                {"access_token":"u-gate-access","refresh_token":"u-gate-refresh","expires_in":7200,"refresh_token_expires_in":604800,"open_id":"ou_oauth_stub","scope":"docs:read"}
                                """);
                    }
                    outbound.add(new OutboundRecord(OutboundKind.OTHER, method, path, body));
                    return new MockResponse().setResponseCode(404)
                            .setBody("{\"code\":404,\"msg\":\"stub path\"}");
                } catch (Exception e) {
                    return new MockResponse().setResponseCode(500).setBody(String.valueOf(e.getMessage()));
                }
            }
        };
    }

    private MockResponse tokenResponse(String method, String path, String body) {
        outbound.add(new OutboundRecord(OutboundKind.TOKEN, method, path, body));
        tokensInvalidated.set(false);
        int n = tokenSeq.incrementAndGet();
        String token = "t-gate-stub-" + n;
        validTokens.add(token);
        return jsonOk("{\"code\":0,\"msg\":\"ok\",\"tenant_access_token\":\"" + token
                + "\",\"expire\":7200}");
    }

    private MockResponse messageCreateResponse(RecordedRequest request, String method, String path,
                                               String body, OutboundKind kind) {
        outbound.add(new OutboundRecord(kind, method, path, body));
        MockResponse authFail = rejectIfInvalidToken(request);
        if (authFail != null) {
            return authFail;
        }
        if (failNextMessageRateLimit.compareAndSet(true, false)) {
            rateLimitResponses.incrementAndGet();
            return jsonOk("{\"code\":" + RATE_LIMIT_CODE + ",\"msg\":\"frequency limited\"}");
        }
        int n = messageSeq.incrementAndGet();
        return jsonOk("{\"code\":0,\"msg\":\"ok\",\"data\":{\"message_id\":\"om_gate_" + n + "\"}}");
    }

    private MockResponse messageUpdateResponse(RecordedRequest request, String method, String path,
                                               String body) {
        outbound.add(new OutboundRecord(OutboundKind.MESSAGE_UPDATE, method, path, body));
        MockResponse authFail = rejectIfInvalidToken(request);
        if (authFail != null) {
            return authFail;
        }
        if (failNextUpdateRateLimit.compareAndSet(true, false)) {
            rateLimitResponses.incrementAndGet();
            return jsonOk("{\"code\":" + MESSAGE_UPDATE_RATE_LIMIT_CODE
                    + ",\"msg\":\"update frequency limited\"}");
        }
        return jsonOk("{\"code\":0,\"msg\":\"ok\",\"data\":{}}");
    }

    private MockResponse rejectIfInvalidToken(RecordedRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return jsonOk("{\"code\":" + INVALID_TOKEN_CODE + ",\"msg\":\"missing token\"}");
        }
        String token = auth.substring(7).trim();
        if (tokensInvalidated.get() || !validTokens.contains(token)) {
            // 冒烟用的探针 token（非本桩颁发）也视为无效
            if (!validTokens.contains(token)) {
                return jsonOk("{\"code\":" + INVALID_TOKEN_CODE + ",\"msg\":\"invalid token\"}");
            }
        }
        return null;
    }

    private MockResponse urlVerificationResponse(String body) throws Exception {
        JsonNode root = json.readTree(body == null || body.isBlank() ? "{}" : body);
        if (!"url_verification".equals(root.path("type").asText())) {
            return new MockResponse().setResponseCode(400).setBody("{\"msg\":\"not url_verification\"}");
        }
        String token = root.path("token").asText("");
        if (!VERIFICATION_TOKEN.equals(token)) {
            return new MockResponse().setResponseCode(401).setBody("{\"msg\":\"bad token\"}");
        }
        ObjectNode resp = json.createObjectNode();
        resp.put("challenge", root.path("challenge").asText(""));
        return jsonOk(json.writeValueAsString(resp));
    }

    private MockResponse eventEchoResponse(RecordedRequest request, String body) throws Exception {
        String ts = request.getHeader("X-Lark-Request-Timestamp");
        String nonce = request.getHeader("X-Lark-Request-Nonce");
        String sig = request.getHeader("X-Lark-Signature");
        if (ts == null || nonce == null || sig == null) {
            return new MockResponse().setResponseCode(401).setBody("{\"msg\":\"missing signature headers\"}");
        }
        String expected = FeishuStubCrypto.sign(ENCRYPT_KEY, ts, nonce, body);
        if (!expected.equalsIgnoreCase(sig.trim())) {
            return new MockResponse().setResponseCode(401).setBody("{\"msg\":\"bad signature\"}");
        }
        JsonNode root = json.readTree(body == null || body.isBlank() ? "{}" : body);
        String encrypt = root.path("encrypt").asText(null);
        if (encrypt == null || encrypt.isBlank()) {
            return new MockResponse().setResponseCode(400).setBody("{\"msg\":\"missing encrypt\"}");
        }
        String plain = FeishuStubCrypto.decrypt(ENCRYPT_KEY, encrypt);
        return jsonOk(plain);
    }

    private static MockResponse jsonOk(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body.trim());
    }

    private static String bodyUtf8(RecordedRequest request) {
        try {
            return request.getBody().readUtf8();
        } catch (Exception e) {
            return "";
        }
    }

    @Override
    public void close() {
        try {
            server.shutdown();
        } catch (IOException ignored) {
        }
    }

    public enum OutboundKind {
        TOKEN, MESSAGE_CREATE, MESSAGE_UPDATE, CARD, CONTACT, OTHER
    }

    public record OutboundRecord(OutboundKind kind, String method, String path, String body) {
    }

    public record PushRecord(String targetUrl, String body, int statusCode, String responseBody) {
    }
}

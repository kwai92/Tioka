package com.tioka.server.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.server.gate.support.KeycloakGateSupport;
import com.tioka.server.gate.support.KeycloakLogin;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FND-05：Keycloak 授权码 + PKCE 登录、组角色映射、state 一次性。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("it")
class OidcLoginIT {
    private static final Pattern FORM_ACTION = Pattern.compile(
            "<form[^>]*action=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern HIDDEN_INPUT = Pattern.compile(
            "<input[^>]*type=\"hidden\"[^>]*name=\"([^\"]+)\"[^>]*value=\"([^\"]*)\"",
            Pattern.CASE_INSENSITIVE);

    private static KeycloakGateSupport keycloak;

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void startKeycloak() {
        keycloak = KeycloakGateSupport.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        keycloak = KeycloakGateSupport.start();
        registry.add("tioka.auth.oidc.frontend-base-url", () -> "http://127.0.0.1:5173");
        registry.add("tioka.auth.oidc.providers.keycloak.issuer-uri", keycloak::issuerUri);
        registry.add("tioka.auth.oidc.providers.keycloak.client-id", () -> KeycloakGateSupport.CLIENT_ID);
        registry.add("tioka.auth.oidc.providers.keycloak.client-secret", () -> KeycloakGateSupport.CLIENT_SECRET);
        registry.add("tioka.auth.oidc.providers.keycloak.groups-claim", () -> "groups");
        registry.add("tioka.auth.oidc.providers.keycloak.auto-provision", () -> "true");
        registry.add("tioka.auth.oidc.providers.keycloak.group-role-mappings[0].group", () -> "/tioka/admins");
        registry.add("tioka.auth.oidc.providers.keycloak.group-role-mappings[0].system-role", () -> "system_admin");
        registry.add("tioka.auth.oidc.providers.keycloak.group-role-mappings[1].group", () -> "/tioka/sales");
        registry.add("tioka.auth.oidc.providers.keycloak.group-role-mappings[1].workspace", () -> "ws_sales");
        registry.add("tioka.auth.oidc.providers.keycloak.group-role-mappings[1].role", () -> "member");
        registry.add("tioka.auth.oidc.resource-server.enabled", () -> "true");
        registry.add("tioka.auth.oidc.resource-server.audiences[0]", () -> "tioka-api");
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    @Test
    void authorizationCodeFlow() throws Exception {
        ensureWsSales();
        KeycloakLogin login = new KeycloakLogin(keycloak, baseUrl());
        String token = login.login(KeycloakGateSupport.USER_ALICE, KeycloakGateSupport.PASSWORD_ALICE);
        assertTrue(token.startsWith("tka_"), token);

        HttpResponse<String> me = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/api/system/auth/me"))
                        .header("Authorization", "Bearer " + token)
                        .header("X-Workspace-Id", "ws_default")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, me.statusCode(), me.body());
        JsonNode data = json.readTree(me.body()).path("data");
        assertEquals("alice", data.path("user").path("username").asText());
    }

    @Test
    void groupMapsToRole() throws Exception {
        ensureWsSales();
        KeycloakLogin login = new KeycloakLogin(keycloak, baseUrl());
        String token = login.login(KeycloakGateSupport.USER_ALICE, KeycloakGateSupport.PASSWORD_ALICE);
        Long userId = jdbc.queryForObject(
                "SELECT id FROM auth_user WHERE username = ? AND idp = 'oidc:keycloak'",
                Long.class, "alice");
        assertNotNull(userId);
        List<String> roles = jdbc.queryForList(
                "SELECT role_code FROM sys_user_role WHERE user_id = ?", String.class, String.valueOf(userId));
        assertTrue(roles.contains("system_admin"), "roles=" + roles);

        removeUserFromGroup("alice", "/tioka/admins");
        Thread.sleep(500);
        // 再次登录应清掉映射角色（新 Cookie 会话，避免旧 SSO）
        String token2 = new KeycloakLogin(keycloak, baseUrl())
                .login(KeycloakGateSupport.USER_ALICE, KeycloakGateSupport.PASSWORD_ALICE);
        assertTrue(token2.startsWith("tka_"), token2);
        List<String> after = jdbc.queryForList(
                "SELECT role_code FROM sys_user_role WHERE user_id = ?", String.class, String.valueOf(userId));
        assertFalse(after.contains("system_admin"), "after=" + after);

        // 恢复组，避免影响同 JVM 其他 IT
        addUserToGroup("alice", "/tioka/admins");
    }

    @Test
    void stateReplayRejected() throws Exception {
        CookieManager cookies = new CookieManager();
        cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(cookies)
                .build();

        keycloak.ensureRedirectUri(baseUrl() + "/api/system/auth/oidc/keycloak/callback");
        HttpResponse<String> start = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl()
                                + "/api/system/auth/oidc/keycloak/authorize?redirect=/chat"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(302, start.statusCode());
        URI authUri = URI.create(start.headers().firstValue("location").orElseThrow());

        HttpResponse<String> page = http.send(HttpRequest.newBuilder(authUri).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        page = followRedirectsToHtml(http, page);
        String html = page.body();
        Matcher m = FORM_ACTION.matcher(html);
        assertTrue(m.find());
        String action = m.group(1).replace("&amp;", "&");
        Map<String, String> form = new java.util.LinkedHashMap<>();
        Matcher h = HIDDEN_INPUT.matcher(html);
        while (h.find()) {
            form.put(h.group(1), h.group(2));
        }
        form.put("username", KeycloakGateSupport.USER_CAROL);
        form.put("password", KeycloakGateSupport.PASSWORD_CAROL);
        form.putIfAbsent("credentialId", "");

        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpResponse<String> posted = http.send(
                HttpRequest.newBuilder(URI.create(absolute(action, keycloak.authServerUrl())))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        URI callback = captureTiokaCallback(http, posted);
        assertNotNull(callback);

        HttpResponse<String> first = http.send(HttpRequest.newBuilder(callback).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(first.statusCode() >= 300 && first.statusCode() < 400, "first=" + first.statusCode());

        HttpResponse<String> second = http.send(HttpRequest.newBuilder(callback).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, second.statusCode(), second.body());
    }

    private void ensureWsSales() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sys_workspace WHERE id = 'ws_sales'", Integer.class);
        if (n != null && n == 0) {
            jdbc.update("""
                    INSERT INTO sys_workspace (id, name, owner_user_id, create_at, update_at, create_by, update_by, deleted)
                    VALUES ('ws_sales', 'Sales', 1, now(), now(), 'system', 'system', FALSE)
                    """);
        }
    }

    private void removeUserFromGroup(String username, String groupPath) throws Exception {
        String adminToken = keycloakAdminToken();
        String userId = findKeycloakUserId(adminToken, username);
        String groupId = findKeycloakGroupId(adminToken, groupPath);
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(keycloak.authServerUrl()
                                + "/admin/realms/tioka/users/" + userId + "/groups/" + groupId))
                        .header("Authorization", "Bearer " + adminToken)
                        .DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(resp.statusCode() / 100 == 2 || resp.statusCode() == 204, resp.body());
    }

    private void addUserToGroup(String username, String groupPath) throws Exception {
        String adminToken = keycloakAdminToken();
        String userId = findKeycloakUserId(adminToken, username);
        String groupId = findKeycloakGroupId(adminToken, groupPath);
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(keycloak.authServerUrl()
                                + "/admin/realms/tioka/users/" + userId + "/groups/" + groupId))
                        .header("Authorization", "Bearer " + adminToken)
                        .PUT(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(resp.statusCode() / 100 == 2 || resp.statusCode() == 204, resp.body());
    }

    private String keycloakAdminToken() throws Exception {
        String form = "grant_type=password&client_id=admin-cli&username=admin&password=admin";
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(keycloak.authServerUrl()
                                + "/realms/master/protocol/openid-connect/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), resp.body());
        return json.readTree(resp.body()).path("access_token").asText();
    }

    private String findKeycloakUserId(String adminToken, String username) throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(keycloak.authServerUrl()
                                + "/admin/realms/tioka/users?username=" + enc(username) + "&exact=true"))
                        .header("Authorization", "Bearer " + adminToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), resp.body());
        JsonNode arr = json.readTree(resp.body());
        assertTrue(arr.isArray() && arr.size() > 0, resp.body());
        return arr.get(0).path("id").asText();
    }

    private String findKeycloakGroupId(String adminToken, String path) throws Exception {
        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(keycloak.authServerUrl()
                                + "/admin/realms/tioka/groups?search=" + enc(pathLeaf(path))))
                        .header("Authorization", "Bearer " + adminToken).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), resp.body());
        return findGroupIdRecursive(json.readTree(resp.body()), path);
    }

    private static String findGroupIdRecursive(JsonNode nodes, String path) {
        if (nodes == null || !nodes.isArray()) {
            return null;
        }
        for (JsonNode n : nodes) {
            if (path.equals(n.path("path").asText())) {
                return n.path("id").asText();
            }
            String nested = findGroupIdRecursive(n.path("subGroups"), path);
            if (nested != null) {
                return nested;
            }
        }
        throw new IllegalStateException("group not found: " + path);
    }

    private static String pathLeaf(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    private static HttpResponse<String> followRedirectsToHtml(HttpClient http, HttpResponse<String> resp)
            throws Exception {
        int hops = 0;
        while (hops++ < 8 && resp.statusCode() >= 300 && resp.statusCode() < 400) {
            URI next = URI.create(absolute(resp.headers().firstValue("location").orElseThrow(),
                    resp.uri().toString()));
            resp = http.send(HttpRequest.newBuilder(next).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        return resp;
    }

    private static URI captureTiokaCallback(HttpClient http, HttpResponse<String> resp) throws Exception {
        int hops = 0;
        while (hops++ < 12) {
            if (resp.statusCode() >= 300 && resp.statusCode() < 400) {
                String loc = resp.headers().firstValue("location").orElseThrow();
                URI next = URI.create(absolute(loc, resp.uri().toString()));
                if (next.toString().contains("/api/system/auth/oidc/") && next.toString().contains("/callback")) {
                    return next;
                }
                resp = http.send(HttpRequest.newBuilder(next).GET().build(), HttpResponse.BodyHandlers.ofString());
                continue;
            }
            fail("did not reach Tioka callback, status=" + resp.statusCode());
        }
        return null;
    }

    private static String absolute(String loc, String base) {
        URI u = URI.create(loc.replace("&amp;", "&"));
        if (u.isAbsolute()) {
            return u.toString();
        }
        return URI.create(base).resolve(u).toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}

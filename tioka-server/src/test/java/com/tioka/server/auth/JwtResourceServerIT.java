package com.tioka.server.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.server.gate.support.KeycloakGateSupport;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FND-05：IdP JWT 资源服务器路径（audience 校验）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("it")
class JwtResourceServerIT {
    private static final Pattern FORM_ACTION = Pattern.compile(
            "<form[^>]*action=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern HIDDEN_INPUT = Pattern.compile(
            "<input[^>]*type=\"hidden\"[^>]*name=\"([^\"]+)\"[^>]*value=\"([^\"]*)\"",
            Pattern.CASE_INSENSITIVE);

    private static KeycloakGateSupport keycloak;

    @LocalServerPort
    int port;

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
        registry.add("tioka.auth.oidc.providers.keycloak.auto-provision", () -> "true");
        registry.add("tioka.auth.oidc.providers.keycloak.group-role-mappings[0].group", () -> "/tioka/admins");
        registry.add("tioka.auth.oidc.providers.keycloak.group-role-mappings[0].system-role", () -> "system_admin");
        registry.add("tioka.auth.oidc.resource-server.enabled", () -> "true");
        registry.add("tioka.auth.oidc.resource-server.audiences[0]", () -> "tioka-api");
    }

    @Test
    void idpTokenAccepted() throws Exception {
        String accessToken = obtainKeycloakAccessToken(
                KeycloakGateSupport.USER_ALICE, KeycloakGateSupport.PASSWORD_ALICE);
        // system_admin 可无空间成员身份访问；不强制 X-Workspace-Id
        HttpResponse<String> me = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/system/auth/me"))
                        .header("Authorization", "Bearer " + accessToken)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, me.statusCode(), me.body());
        assertEquals(0, json.readTree(me.body()).path("code").asInt());
    }

    @Test
    void wrongAudienceRejected() throws Exception {
        // 使用 id_token（aud=client_id tioka，不含 tioka-api）作为 Bearer
        TokenPair pair = obtainKeycloakTokens(KeycloakGateSupport.USER_BOB, KeycloakGateSupport.PASSWORD_BOB);
        assertNotNull(pair.idToken);
        HttpResponse<String> me = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/system/auth/me"))
                        .header("Authorization", "Bearer " + pair.idToken)
                        .header("X-Workspace-Id", "ws_default")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, me.statusCode(), me.body());
    }

    private String obtainKeycloakAccessToken(String user, String password) throws Exception {
        return obtainKeycloakTokens(user, password).accessToken;
    }

    private TokenPair obtainKeycloakTokens(String user, String password) throws Exception {
        CookieManager cookies = new CookieManager();
        cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(cookies)
                .build();

        String state = randomUrlSafe(16);
        String verifier = randomUrlSafe(32);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(md.digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        // 直连 Keycloak，redirect 到本机假回调以截获 code（不经 Tioka）
        String redirectUri = "http://127.0.0.1:5173/login/callback";
        String authUrl = keycloak.authorizationEndpoint()
                + "?client_id=" + enc(KeycloakGateSupport.CLIENT_ID)
                + "&response_type=code"
                + "&scope=" + enc("openid profile email")
                + "&redirect_uri=" + enc(redirectUri)
                + "&state=" + enc(state)
                + "&code_challenge=" + enc(challenge)
                + "&code_challenge_method=S256";

        HttpResponse<String> page = http.send(HttpRequest.newBuilder(URI.create(authUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        int hops = 0;
        while (hops++ < 8 && page.statusCode() >= 300 && page.statusCode() < 400) {
            page = http.send(HttpRequest.newBuilder(URI.create(page.headers().firstValue("location").orElseThrow()))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        Matcher m = FORM_ACTION.matcher(page.body());
        assertTrue(m.find());
        Map<String, String> form = new LinkedHashMap<>();
        Matcher h = HIDDEN_INPUT.matcher(page.body());
        while (h.find()) {
            form.put(h.group(1), h.group(2));
        }
        form.put("username", user);
        form.put("password", password);
        form.putIfAbsent("credentialId", "");
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpResponse<String> posted = http.send(
                HttpRequest.newBuilder(URI.create(absolute(m.group(1).replace("&amp;", "&"), keycloak.authServerUrl())))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        String code = null;
        hops = 0;
        while (hops++ < 10) {
            assertTrue(posted.statusCode() >= 300 && posted.statusCode() < 400, posted.body());
            URI loc = URI.create(posted.headers().firstValue("location").orElseThrow());
            if (loc.toString().startsWith(redirectUri) || loc.toString().contains("/login/callback")) {
                code = query(loc).get("code");
                break;
            }
            posted = http.send(HttpRequest.newBuilder(loc).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        assertNotNull(code);

        String tokenForm = "grant_type=authorization_code"
                + "&code=" + enc(code)
                + "&redirect_uri=" + enc(redirectUri)
                + "&client_id=" + enc(KeycloakGateSupport.CLIENT_ID)
                + "&client_secret=" + enc(KeycloakGateSupport.CLIENT_SECRET)
                + "&code_verifier=" + enc(verifier);
        HttpResponse<String> tokenResp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(keycloak.tokenEndpoint()))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(tokenForm)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, tokenResp.statusCode(), tokenResp.body());
        JsonNode root = json.readTree(tokenResp.body());
        return new TokenPair(root.path("access_token").asText(), root.path("id_token").asText());
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            if (i < 0) {
                out.put(part, "");
            } else {
                out.put(part.substring(0, i), part.substring(i + 1));
            }
        }
        return out;
    }

    private static String absolute(String loc, String base) {
        URI u = URI.create(loc.replace("&amp;", "&"));
        return u.isAbsolute() ? u.toString() : URI.create(base).resolve(u).toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String randomUrlSafe(int bytes) {
        byte[] buf = new byte[bytes];
        new SecureRandom().nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private record TokenPair(String accessToken, String idToken) {}
}

package com.tioka.server.gate.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ENG-04b / FND-05：无浏览器走 Keycloak 授权码（PKCE）+ Tioka OIDC 回调，返回 {@code tka_} 访问令牌。
 * <p>入口：{@code GET /api/system/auth/oidc/keycloak/authorize}；回调写 {@code tioka_rt} Cookie 后经 {@code /refresh} 取访问令牌。
 */
public final class KeycloakLogin {
    private static final Pattern FORM_ACTION = Pattern.compile(
            "<form[^>]*action=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern HIDDEN_INPUT = Pattern.compile(
            "<input[^>]*type=\"hidden\"[^>]*name=\"([^\"]+)\"[^>]*value=\"([^\"]*)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HIDDEN_INPUT_ALT = Pattern.compile(
            "<input[^>]*name=\"([^\"]+)\"[^>]*type=\"hidden\"[^>]*value=\"([^\"]*)\"",
            Pattern.CASE_INSENSITIVE);

    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final KeycloakGateSupport keycloak;
    private final String tiokaBaseUrl;

    public KeycloakLogin(KeycloakGateSupport keycloak, String tiokaBaseUrl) {
        CookieManager cookies = new CookieManager();
        cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .cookieHandler(cookies)
                .build();
        this.keycloak = keycloak;
        this.tiokaBaseUrl = tiokaBaseUrl.replaceAll("/$", "");
    }

    public String login(String username, String password) throws Exception {
        keycloak.ensureRedirectUri(tiokaBaseUrl + "/api/system/auth/oidc/keycloak/callback");
        HttpResponse<String> start = http.send(
                HttpRequest.newBuilder(URI.create(tiokaBaseUrl
                                + "/api/system/auth/oidc/keycloak/authorize?redirect="
                                + enc("/chat")))
                        .GET().timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (start.statusCode() / 100 != 3) {
            throw new IllegalStateException("authorize expected 302, got " + start.statusCode()
                    + " " + truncate(start.body()));
        }
        String authLoc = start.headers().firstValue("location")
                .orElseThrow(() -> new IllegalStateException("authorize missing Location"));
        HttpResponse<String> loginPage = http.send(
                HttpRequest.newBuilder(URI.create(authLoc)).GET().timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
        String html = followToLoginHtml(loginPage);
        String action = extractFormAction(html);
        Map<String, String> form = extractHidden(html);
        form.put("username", username);
        form.put("password", password);
        form.putIfAbsent("credentialId", "");

        followLoginToFrontend(action, form);
        return refreshAccessToken();
    }

    private String followToLoginHtml(HttpResponse<String> resp) throws Exception {
        int hops = 0;
        while (hops++ < 8) {
            int code = resp.statusCode();
            if (code >= 300 && code < 400) {
                String loc = resp.headers().firstValue("location").orElseThrow();
                URI next = URI.create(authAbsolute(loc, resp.uri()));
                resp = http.send(HttpRequest.newBuilder(next).GET().timeout(Duration.ofSeconds(30)).build(),
                        HttpResponse.BodyHandlers.ofString());
                continue;
            }
            if (code == 200 && resp.body() != null && resp.body().contains("username")) {
                return resp.body();
            }
            throw new IllegalStateException("unexpected Keycloak login page status=" + code
                    + " body=" + truncate(resp.body()));
        }
        throw new IllegalStateException("too many redirects before login form");
    }

    private void followLoginToFrontend(String action, Map<String, String> form) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> enc(e.getKey()) + "=" + enc(e.getValue() == null ? "" : e.getValue()))
                .collect(Collectors.joining("&"));
        URI actionUri = URI.create(authAbsolute(action, URI.create(keycloak.authServerUrl())));
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(actionUri)
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        int hops = 0;
        while (hops++ < 12) {
            int status = resp.statusCode();
            if (status >= 300 && status < 400) {
                String loc = resp.headers().firstValue("location")
                        .orElseThrow(() -> new IllegalStateException("redirect without location"));
                URI next = URI.create(authAbsolute(loc, resp.uri()));
                if (isFrontendCallback(next)) {
                    return;
                }
                resp = http.send(HttpRequest.newBuilder(next).GET().timeout(Duration.ofSeconds(30)).build(),
                        HttpResponse.BodyHandlers.ofString());
                continue;
            }
            throw new IllegalStateException("login did not complete OIDC callback, status=" + status
                    + " body=" + truncate(resp.body()));
        }
        throw new IllegalStateException("too many redirects after login");
    }

    private String refreshAccessToken() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(tiokaBaseUrl + "/api/system/auth/refresh"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("Tioka /refresh failed: " + resp.statusCode() + " " + resp.body());
        }
        JsonNode root = json.readTree(resp.body());
        String token = root.path("data").path("accessToken").asText(null);
        if (token == null || !token.startsWith("tka_")) {
            throw new IllegalStateException("expected tka_ token, got: " + resp.body());
        }
        return token;
    }

    private static boolean isFrontendCallback(URI uri) {
        String s = uri.toString();
        return s.contains("/login/callback");
    }

    private static String extractFormAction(String html) {
        Matcher m = FORM_ACTION.matcher(html);
        if (!m.find()) {
            throw new IllegalStateException("login form action not found");
        }
        return m.group(1).replace("&amp;", "&");
    }

    private static Map<String, String> extractHidden(String html) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m1 = HIDDEN_INPUT.matcher(html);
        while (m1.find()) {
            out.put(m1.group(1), m1.group(2));
        }
        Matcher m2 = HIDDEN_INPUT_ALT.matcher(html);
        while (m2.find()) {
            out.putIfAbsent(m2.group(1), m2.group(2));
        }
        return out;
    }

    static Map<String, String> query(URI uri) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split("&")) {
            int i = part.indexOf('=');
            if (i < 0) {
                out.put(URLDecoder.decode(part, StandardCharsets.UTF_8), "");
            } else {
                out.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static String authAbsolute(String loc, URI base) {
        URI u = URI.create(loc.replace("&amp;", "&"));
        if (u.isAbsolute()) {
            return u.toString();
        }
        return base.resolve(u).toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 400 ? s.substring(0, 400) : s;
    }
}

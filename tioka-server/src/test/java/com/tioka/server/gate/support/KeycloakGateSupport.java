package com.tioka.server.gate.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dasniko.testcontainers.keycloak.KeycloakContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * ENG-04b：闸门用 Keycloak 单例（Keycloak 24 + tioka realm）。
 * 测试账号见 {@code docs/architecture/ops/oidc-keycloak.md}（仅测试/本地，非生产密钥）。
 */
public final class KeycloakGateSupport implements AutoCloseable {
    public static final String REALM = "tioka";
    public static final String CLIENT_ID = "tioka";
    /** 与 tioka-realm.json / compose 一致；仅测试环境。 */
    public static final String CLIENT_SECRET = "tioka-test-client-secret";
    /** 前端回调（JWT 直换码等）；FND-05 Tioka 后端回调为动态端口，见 {@link #ensureRedirectUri}。 */
    public static final String REDIRECT_URI = "http://127.0.0.1:5173/login/callback";

    public static final String USER_ALICE = "alice";
    public static final String USER_BOB = "bob";
    public static final String USER_CAROL = "carol";
    /** ENG-04d：华东 / 华南区域测试用户（属性 region），供 DAT 与 G3EndToEndGateIT。 */
    public static final String USER_EAST = "user_east";
    public static final String USER_SOUTH = "user_south";
    public static final String REGION_EAST = "华东";
    public static final String REGION_SOUTH = "华南";
    /** 与 realm 导入一致；见 oidc-keycloak.md。 */
    public static final String PASSWORD_ALICE = "AlicePass1!";
    public static final String PASSWORD_BOB = "BobPass1!";
    public static final String PASSWORD_CAROL = "CarolPass1!";
    public static final String PASSWORD_EAST = "EastPass1!";
    public static final String PASSWORD_SOUTH = "SouthPass1!";

    private static final Object LOCK = new Object();
    private static KeycloakGateSupport singleton;

    private final KeycloakContainer container;
    private final boolean shared;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private KeycloakGateSupport(KeycloakContainer container, boolean shared) {
        this.container = container;
        this.shared = shared;
    }

    public static KeycloakGateSupport start() {
        synchronized (LOCK) {
            if (singleton == null) {
                KeycloakContainer c = new KeycloakContainer("quay.io/keycloak/keycloak:24.0")
                        .withRealmImportFile("keycloak/tioka-realm.json");
                c.start();
                singleton = new KeycloakGateSupport(c, true);
            }
            return singleton;
        }
    }

    public String authServerUrl() {
        return container.getAuthServerUrl().replaceAll("/$", "");
    }

    public String issuerUri() {
        return authServerUrl() + "/realms/" + REALM;
    }

    public String authorizationEndpoint() {
        return issuerUri() + "/protocol/openid-connect/auth";
    }

    public String tokenEndpoint() {
        return issuerUri() + "/protocol/openid-connect/token";
    }

    /**
     * 将动态端口的 Tioka OIDC 回调 URI 注册到测试客户端（Keycloak 不支持可靠的端口通配）。
     */
    public synchronized void ensureRedirectUri(String redirectUri) {
        if (redirectUri == null || redirectUri.isBlank()) {
            return;
        }
        try {
            String admin = adminToken();
            String clientUuid = findClientUuid(admin);
            ObjectNode client = (ObjectNode) getClient(admin, clientUuid);
            ArrayNode redirects = client.withArray("redirectUris");
            List<String> existing = new ArrayList<>();
            redirects.forEach(n -> existing.add(n.asText()));
            if (existing.contains(redirectUri)) {
                return;
            }
            redirects.add(redirectUri);
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(authServerUrl()
                                    + "/admin/realms/" + REALM + "/clients/" + clientUuid))
                            .header("Authorization", "Bearer " + admin)
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(client)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("update client redirect failed: "
                        + resp.statusCode() + " " + resp.body());
            }
        } catch (Exception e) {
            throw new IllegalStateException("ensureRedirectUri failed: " + redirectUri, e);
        }
    }

    private String adminToken() throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(authServerUrl()
                                + "/realms/master/protocol/openid-connect/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "grant_type=password&client_id=admin-cli&username=admin&password=admin"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("admin token: " + resp.statusCode() + " " + resp.body());
        }
        return json.readTree(resp.body()).path("access_token").asText();
    }

    private String findClientUuid(String admin) throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(authServerUrl()
                                + "/admin/realms/" + REALM + "/clients?clientId=" + CLIENT_ID))
                        .header("Authorization", "Bearer " + admin).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode arr = json.readTree(resp.body());
        if (!arr.isArray() || arr.isEmpty()) {
            throw new IllegalStateException("client not found: " + CLIENT_ID);
        }
        return arr.get(0).path("id").asText();
    }

    private JsonNode getClient(String admin, String clientUuid) throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(authServerUrl()
                                + "/admin/realms/" + REALM + "/clients/" + clientUuid))
                        .header("Authorization", "Bearer " + admin).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return json.readTree(resp.body());
    }

    public void removeUserFromGroup(String username, String groupPath) {
        try {
            String admin = adminToken();
            String userId = findUserId(admin, username);
            String groupId = findGroupId(admin, groupPath);
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(authServerUrl()
                                    + "/admin/realms/" + REALM + "/users/" + userId + "/groups/" + groupId))
                            .header("Authorization", "Bearer " + admin)
                            .DELETE().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2 && resp.statusCode() != 204) {
                throw new IllegalStateException("remove group: " + resp.statusCode() + " " + resp.body());
            }
        } catch (Exception e) {
            throw new IllegalStateException("removeUserFromGroup " + username + " " + groupPath, e);
        }
    }

    /**
     * ENG-04d：确保华东 / 华南两位区域用户存在且 {@code region} 属性不同。
     * 幂等；挂在 {@code /tioka/sales}，与 bob 同组以便后续 DAT 闸门复用。
     */
    public synchronized void ensureRegionUsers() {
        ensureRegionUser(USER_EAST, PASSWORD_EAST, "East", "Huadong", REGION_EAST);
        ensureRegionUser(USER_SOUTH, PASSWORD_SOUTH, "South", "Huanan", REGION_SOUTH);
    }

    public String userRegionAttribute(String username) {
        try {
            String admin = adminToken();
            String userId = findUserId(admin, username);
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(authServerUrl()
                                    + "/admin/realms/" + REALM + "/users/" + userId))
                            .header("Authorization", "Bearer " + admin).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("get user: " + resp.statusCode() + " " + resp.body());
            }
            JsonNode attrs = json.readTree(resp.body()).path("attributes").path("region");
            if (attrs.isArray() && !attrs.isEmpty()) {
                return attrs.get(0).asText();
            }
            return "";
        } catch (Exception e) {
            throw new IllegalStateException("userRegionAttribute " + username, e);
        }
    }

    private void ensureRegionUser(String username, String password, String first, String last, String region) {
        try {
            String admin = adminToken();
            try {
                findUserId(admin, username);
            } catch (IllegalStateException missing) {
                ObjectNode user = json.createObjectNode();
                user.put("username", username);
                user.put("enabled", true);
                user.put("email", username + "@tioka.local");
                user.put("emailVerified", true);
                user.put("firstName", first);
                user.put("lastName", last);
                ObjectNode attrs = user.putObject("attributes");
                attrs.putArray("region").add(region);
                ArrayNode creds = user.putArray("credentials");
                ObjectNode cred = creds.addObject();
                cred.put("type", "password");
                cred.put("value", password);
                cred.put("temporary", false);
                HttpResponse<String> create = http.send(
                        HttpRequest.newBuilder(URI.create(authServerUrl()
                                        + "/admin/realms/" + REALM + "/users"))
                                .header("Authorization", "Bearer " + admin)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(user)))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                if (create.statusCode() / 100 != 2 && create.statusCode() != 409) {
                    throw new IllegalStateException("create user " + username + ": "
                            + create.statusCode() + " " + create.body());
                }
            }
            // 保证 region 属性与组
            String userId = findUserId(admin, username);
            HttpResponse<String> get = http.send(
                    HttpRequest.newBuilder(URI.create(authServerUrl()
                                    + "/admin/realms/" + REALM + "/users/" + userId))
                            .header("Authorization", "Bearer " + admin).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            ObjectNode existing = (ObjectNode) json.readTree(get.body());
            ObjectNode attrs = existing.with("attributes");
            attrs.putArray("region").add(region);
            HttpResponse<String> put = http.send(
                    HttpRequest.newBuilder(URI.create(authServerUrl()
                                    + "/admin/realms/" + REALM + "/users/" + userId))
                            .header("Authorization", "Bearer " + admin)
                            .header("Content-Type", "application/json")
                            .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(existing)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (put.statusCode() / 100 != 2) {
                throw new IllegalStateException("update region attr: " + put.statusCode() + " " + put.body());
            }
            addUserToGroup(username, "/tioka/sales");
        } catch (Exception e) {
            throw new IllegalStateException("ensureRegionUser " + username, e);
        }
    }

    public void addUserToGroup(String username, String groupPath) {
        try {
            String admin = adminToken();
            String userId = findUserId(admin, username);
            String groupId = findGroupId(admin, groupPath);
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(authServerUrl()
                                    + "/admin/realms/" + REALM + "/users/" + userId + "/groups/" + groupId))
                            .header("Authorization", "Bearer " + admin)
                            .PUT(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2 && resp.statusCode() != 204) {
                throw new IllegalStateException("add group: " + resp.statusCode() + " " + resp.body());
            }
        } catch (Exception e) {
            throw new IllegalStateException("addUserToGroup " + username + " " + groupPath, e);
        }
    }

    private String findUserId(String admin, String username) throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(authServerUrl()
                                + "/admin/realms/" + REALM + "/users?username=" + username + "&exact=true"))
                        .header("Authorization", "Bearer " + admin).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode arr = json.readTree(resp.body());
        if (!arr.isArray() || arr.isEmpty()) {
            throw new IllegalStateException("user not found: " + username);
        }
        return arr.get(0).path("id").asText();
    }

    private String findGroupId(String admin, String path) throws Exception {
        HttpResponse<String> resp = http.send(
                HttpRequest.newBuilder(URI.create(authServerUrl()
                                + "/admin/realms/" + REALM + "/groups?briefRepresentation=false&populateHierarchy=true"))
                        .header("Authorization", "Bearer " + admin).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        String id = findGroupIdRecursive(admin, json.readTree(resp.body()), path);
        if (id == null) {
            throw new IllegalStateException("group not found: " + path + " body=" + resp.body());
        }
        return id;
    }

    private String findGroupIdRecursive(String admin, JsonNode nodes, String path) throws Exception {
        if (nodes == null || !nodes.isArray()) {
            return null;
        }
        for (JsonNode n : nodes) {
            if (path.equals(n.path("path").asText())) {
                return n.path("id").asText();
            }
            JsonNode children = n.path("subGroups");
            if ((!children.isArray() || children.isEmpty()) && n.path("subGroupCount").asInt(0) > 0) {
                HttpResponse<String> childResp = http.send(
                        HttpRequest.newBuilder(URI.create(authServerUrl()
                                        + "/admin/realms/" + REALM + "/groups/" + n.path("id").asText() + "/children"))
                                .header("Authorization", "Bearer " + admin).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                children = json.readTree(childResp.body());
            }
            String nested = findGroupIdRecursive(admin, children, path);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    @Override
    public void close() {
        // 单例跨 IT 复用，不在单个 GateCluster.close 时停容器
        if (!shared) {
            container.stop();
        }
    }
}

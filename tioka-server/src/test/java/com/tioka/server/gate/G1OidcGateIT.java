package com.tioka.server.gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.server.gate.support.GateCluster;
import com.tioka.server.gate.support.KeycloakGateSupport;
import com.tioka.server.gate.support.KeycloakLogin;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 闸门 G1：FND-05。Keycloak 用户经授权码流程登录，按组映射到角色；从组移除后再次登录角色消失。
 */
@Tag("gate")
class G1OidcGateIT {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void keycloakUserLogsInWithMappedRole() throws Exception {
        try (GateCluster cluster = GateCluster.start(1)) {
            JdbcTemplate jdbc = cluster.instance(0).getBean(JdbcTemplate.class);
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM sys_workspace WHERE id = 'ws_sales'", Integer.class);
            if (n != null && n == 0) {
                jdbc.update("""
                        INSERT INTO sys_workspace (id, name, owner_user_id, create_at, update_at, create_by, update_by, deleted)
                        VALUES ('ws_sales', 'Sales', 1, now(), now(), 'system', 'system', FALSE)
                        """);
            }

            KeycloakLogin login = new KeycloakLogin(cluster.keycloak(), cluster.baseUrl(0));
            String token = login.login(KeycloakGateSupport.USER_ALICE, KeycloakGateSupport.PASSWORD_ALICE);
            assertTrue(token.startsWith("tka_"), token);

            HttpResponse<String> me = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(cluster.baseUrl(0) + "/api/system/auth/me"))
                            .header("Authorization", "Bearer " + token)
                            .header("X-Workspace-Id", "ws_default")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, me.statusCode(), me.body());
            JsonNode data = json.readTree(me.body()).path("data");
            assertEquals("alice", data.path("user").path("username").asText());

            Long userId = jdbc.queryForObject(
                    "SELECT id FROM auth_user WHERE username = ? AND idp = 'oidc:keycloak'",
                    Long.class, "alice");
            assertNotNull(userId);
            List<String> roles = jdbc.queryForList(
                    "SELECT role_code FROM sys_user_role WHERE user_id = ?",
                    String.class, String.valueOf(userId));
            assertTrue(roles.contains("system_admin"), "roles=" + roles);

            cluster.keycloak().removeUserFromGroup("alice", "/tioka/admins");
            Thread.sleep(500);
            new KeycloakLogin(cluster.keycloak(), cluster.baseUrl(0))
                    .login(KeycloakGateSupport.USER_ALICE, KeycloakGateSupport.PASSWORD_ALICE);
            List<String> after = jdbc.queryForList(
                    "SELECT role_code FROM sys_user_role WHERE user_id = ?",
                    String.class, String.valueOf(userId));
            assertFalse(after.contains("system_admin"), "after=" + after);

            cluster.keycloak().addUserToGroup("alice", "/tioka/admins");
        }
    }
}

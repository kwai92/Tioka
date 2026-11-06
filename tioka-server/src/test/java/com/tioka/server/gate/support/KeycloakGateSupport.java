package com.tioka.server.gate.support;

import dasniko.testcontainers.keycloak.KeycloakContainer;

/**
 * ENG-04b：闸门用 Keycloak 单例（Keycloak 24 + tioka realm）。
 * 测试账号见 {@code docs/architecture/ops/oidc-keycloak.md}（仅测试/本地，非生产密钥）。
 */
public final class KeycloakGateSupport implements AutoCloseable {
    public static final String REALM = "tioka";
    public static final String CLIENT_ID = "tioka";
    /** 与 tioka-realm.json / compose 一致；仅测试环境。 */
    public static final String CLIENT_SECRET = "tioka-test-client-secret";
    public static final String REDIRECT_URI = "http://127.0.0.1:5173/login/callback";

    public static final String USER_ALICE = "alice";
    public static final String USER_BOB = "bob";
    public static final String USER_CAROL = "carol";
    /** 与 realm 导入一致；见 oidc-keycloak.md。 */
    public static final String PASSWORD_ALICE = "AlicePass1!";
    public static final String PASSWORD_BOB = "BobPass1!";
    public static final String PASSWORD_CAROL = "CarolPass1!";

    private static final Object LOCK = new Object();
    private static KeycloakGateSupport singleton;

    private final KeycloakContainer container;
    private final boolean shared;

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

    @Override
    public void close() {
        // 单例跨 IT 复用，不在单个 GateCluster.close 时停容器
        if (!shared) {
            container.stop();
        }
    }
}

package com.tioka.server.gate.support;

import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.kernel.application.RuntimeTestHooks;
import com.tioka.server.TiokaServerApplication;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestContainers;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ENG-04b：同一 JVM 内 N 个 Spring 上下文，共享 Testcontainers PG/Redis（可选 Keycloak）与 LLM/MCP 桩。
 */
public final class GateCluster implements AutoCloseable {
    private final List<ConfigurableApplicationContext> instances;
    private final LlmStubServer llm;
    private final McpStubServer mcp;
    private final KeycloakGateSupport keycloak;
    private final boolean ownKeycloak;

    private GateCluster(List<ConfigurableApplicationContext> instances,
                        LlmStubServer llm,
                        McpStubServer mcp,
                        KeycloakGateSupport keycloak,
                        boolean ownKeycloak) {
        this.instances = instances;
        this.llm = llm;
        this.mcp = mcp;
        this.keycloak = keycloak;
        this.ownKeycloak = ownKeycloak;
    }

    public static GateCluster start(int n) {
        return start(n, "three-tools", Map.of(), true);
    }

    public static GateCluster start(int n, String llmScript, Map<String, Object> extraProps, boolean withKeycloak) {
        if (n < 1) {
            throw new IllegalArgumentException("n >= 1");
        }
        TestContainers.PG.getJdbcUrl();
        System.setProperty("tioka.llm.script", llmScript == null ? "three-tools" : llmScript);
        LlmStubServer llm = LlmStubServer.start();
        McpStubServer mcp = McpStubServer.start();
        KeycloakGateSupport keycloak = withKeycloak ? KeycloakGateSupport.start() : null;

        Map<String, Object> shared = baseProps(llm.baseUrl(), mcp.baseUrl(), keycloak);
        if (extraProps != null) {
            shared.putAll(extraProps);
        }

        List<ConfigurableApplicationContext> ctxs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            String id = Character.toString((char) ('A' + i));
            ctxs.add(run(withInstance(shared, id)));
        }
        return new GateCluster(ctxs, llm, mcp, keycloak, withKeycloak);
    }

    public ConfigurableApplicationContext instance(int i) {
        return Objects.requireNonNull(instances.get(i), "instance " + i + " already crashed");
    }

    public int size() {
        return instances.size();
    }

    public int port(int i) {
        return portOf(instance(i));
    }

    public String baseUrl(int i) {
        return "http://127.0.0.1:" + port(i);
    }

    public ConfigurableApplicationContext a() { return instance(0); }

    public ConfigurableApplicationContext b() { return instance(1); }

    public int portA() { return port(0); }

    public int portB() { return port(1); }

    public String baseUrlA() { return baseUrl(0); }

    public String baseUrlB() { return baseUrl(1); }

    public LlmStubServer llm() { return llm; }

    public McpStubServer mcp() { return mcp; }

    public KeycloakGateSupport keycloak() {
        if (keycloak == null) {
            throw new IllegalStateException("本集群未启用 Keycloak");
        }
        return keycloak;
    }

    /**
     * 模拟崩溃：freeze → release-on-shutdown=false → 关闭上下文，租约不释放。
     */
    public void crash(int i) {
        ConfigurableApplicationContext ctx = instances.get(i);
        if (ctx == null) {
            throw new IllegalStateException("instance " + i + " already crashed");
        }
        RuntimeTestHooks hooks = ctx.getBean(RuntimeTestHooks.class);
        hooks.freeze();
        ctx.getBean(KernelProperties.class).setReleaseOnShutdown(false);
        ctx.close();
        instances.set(i, null);
    }

    @Override
    public void close() {
        for (int i = instances.size() - 1; i >= 0; i--) {
            ConfigurableApplicationContext ctx = instances.get(i);
            if (ctx != null) {
                try {
                    ctx.close();
                } catch (Exception ignored) {
                }
                instances.set(i, null);
            }
        }
        try {
            mcp.close();
        } finally {
            try {
                llm.close();
            } finally {
                if (ownKeycloak && keycloak != null) {
                    keycloak.close();
                }
                System.clearProperty("tioka.llm.script");
            }
        }
    }

    private static ConfigurableApplicationContext run(Map<String, Object> props) {
        SpringApplication app = new SpringApplication(TiokaServerApplication.class);
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("gate-cluster", props));
        app.setEnvironment(env);
        app.setAdditionalProfiles("gate");
        return app.run();
    }

    private static int portOf(ConfigurableApplicationContext ctx) {
        return ctx.getEnvironment().getProperty("local.server.port", Integer.class, 0);
    }

    private static Map<String, Object> baseProps(String llmBaseUrl, String mcpBaseUrl, KeycloakGateSupport keycloak) {
        Map<String, Object> m = new HashMap<>();
        m.put("server.port", 0);
        m.put("spring.main.allow-bean-definition-overriding", "true");
        m.put("spring.datasource.url", TestContainers.PG.getJdbcUrl());
        m.put("spring.datasource.username", TestContainers.PG.getUsername());
        m.put("spring.datasource.password", TestContainers.PG.getPassword());
        m.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
        m.put("spring.datasource.hikari.maximum-pool-size", "16");
        m.put("spring.datasource.hikari.minimum-idle", "2");
        m.put("spring.data.redis.host", TestContainers.REDIS.getHost());
        m.put("spring.data.redis.port", TestContainers.REDIS.getMappedPort(6379));
        m.put("spring.flyway.enabled", true);
        m.put("spring.sql.init.mode", "never");
        m.put("tioka.bootstrap.admin-password", IntegrationTestSupport.BOOTSTRAP_ADMIN_PASSWORD);
        m.put("tioka.home", System.getProperty("java.io.tmpdir") + "/tioka-gate-home");
        m.put("tioka.model.mock", false);
        m.put("tioka.mcp.mock", false);
        m.put("tioka.egress.mode", "OPEN");
        m.put("tioka.gate.llm-base-url", llmBaseUrl);
        m.put("tioka.gate.mcp-base-url", mcpBaseUrl);
        m.put("tioka.runtime.executor.max-concurrent-turns", 128);
        m.put("tioka.runtime.executor.queue-capacity", 512);
        // 闸门 profile：租约 TTL 5s，便于 crash 后观察过期
        m.put("tioka.runtime.lease-ttl", "5s");
        m.put("tioka.runtime.lease-renew-interval", "2s");
        m.put("tioka.runtime.release-on-shutdown", true);
        m.put("tioka.runtime.dispatcher-enabled", true);
        m.put("tioka.runtime.queued-sweeper-enabled", true);
        if (keycloak != null) {
            m.put("tioka.auth.oidc-enabled", true);
            m.put("tioka.auth.oidc-issuer", keycloak.issuerUri());
            m.put("tioka.auth.oidc-client-id", KeycloakGateSupport.CLIENT_ID);
            m.put("tioka.auth.oidc-client-secret", KeycloakGateSupport.CLIENT_SECRET);
            m.put("tioka.auth.oidc-redirect-uri", KeycloakGateSupport.REDIRECT_URI);
            m.put("tioka.auth.oidc-token-url", keycloak.tokenEndpoint());
        }
        return m;
    }

    private static Map<String, Object> withInstance(Map<String, Object> shared, String id) {
        Map<String, Object> m = new HashMap<>(shared);
        m.put("tioka.runtime.instance-id", id);
        m.put("spring.application.name", "tioka-" + id);
        return m;
    }
}

package com.tioka.server.gate.support;

import com.tioka.server.TiokaServerApplication;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestContainers;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

/**
 * ENG-04：同一 JVM 内双 Spring 上下文，共享 Testcontainers PG/Redis。
 */
public final class DualInstance implements AutoCloseable {
    private final ConfigurableApplicationContext a;
    private final ConfigurableApplicationContext b;
    private final LlmStubServer llm;

    private DualInstance(ConfigurableApplicationContext a,
                         ConfigurableApplicationContext b,
                         LlmStubServer llm) {
        this.a = a;
        this.b = b;
        this.llm = llm;
    }

    public static DualInstance start() {
        return start("three-tools", Map.of(), true);
    }

    public static DualInstance start(String llmScript, Map<String, Object> extraProps) {
        return start(llmScript, extraProps, true);
    }

    /** @param dual 为 false 时只启实例 A（并发闸门用，避免双池压垮 PG） */
    public static DualInstance start(String llmScript, Map<String, Object> extraProps, boolean dual) {
        TestContainers.PG.getJdbcUrl();
        System.setProperty("tioka.llm.script", llmScript == null ? "three-tools" : llmScript);
        LlmStubServer llm = LlmStubServer.start();
        Map<String, Object> shared = baseProps(llm.baseUrl());
        if (extraProps != null) {
            shared.putAll(extraProps);
        }
        ConfigurableApplicationContext ctxA = run(withInstance(shared, "A"));
        ConfigurableApplicationContext ctxB = dual ? run(withInstance(shared, "B")) : null;
        return new DualInstance(ctxA, ctxB, llm);
    }

    public ConfigurableApplicationContext a() { return a; }

    public ConfigurableApplicationContext b() {
        if (b == null) {
            throw new IllegalStateException("单实例模式无 B");
        }
        return b;
    }

    public int portA() { return portOf(a); }

    public int portB() { return portOf(b()); }

    public LlmStubServer llm() { return llm; }

    public String baseUrlA() { return "http://127.0.0.1:" + portA(); }

    public String baseUrlB() { return "http://127.0.0.1:" + portB(); }

    @Override
    public void close() {
        try {
            if (b != null) {
                b.close();
            }
        } finally {
            try {
                a.close();
            } finally {
                llm.close();
                System.clearProperty("tioka.llm.script");
            }
        }
    }

    private static ConfigurableApplicationContext run(Map<String, Object> props) {
        SpringApplication app = new SpringApplication(TiokaServerApplication.class);
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("dual-instance", props));
        app.setEnvironment(env);
        app.setAdditionalProfiles("gate");
        return app.run();
    }

    private static int portOf(ConfigurableApplicationContext ctx) {
        return ctx.getEnvironment().getProperty("local.server.port", Integer.class, 0);
    }

    private static Map<String, Object> baseProps(String llmBaseUrl) {
        Map<String, Object> m = new HashMap<>();
        m.put("server.port", 0);
        m.put("spring.main.allow-bean-definition-overriding", "true");
        m.put("spring.datasource.url", TestContainers.PG.getJdbcUrl());
        m.put("spring.datasource.username", TestContainers.PG.getUsername());
        m.put("spring.datasource.password", TestContainers.PG.getPassword());
        m.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
        // 双实例共享 Testcontainers PG，默认 max_connections≈100，两池合计须留余量
        m.put("spring.datasource.hikari.maximum-pool-size", "24");
        m.put("spring.datasource.hikari.minimum-idle", "2");
        m.put("spring.data.redis.host", TestContainers.REDIS.getHost());
        m.put("spring.data.redis.port", TestContainers.REDIS.getMappedPort(6379));
        m.put("spring.flyway.enabled", true);
        m.put("spring.sql.init.mode", "never");
        m.put("tioka.bootstrap.admin-password", IntegrationTestSupport.BOOTSTRAP_ADMIN_PASSWORD);
        m.put("tioka.home", System.getProperty("java.io.tmpdir") + "/tioka-gate-home");
        m.put("tioka.model.mock", false);
        m.put("tioka.egress.mode", "OPEN");
        m.put("tioka.gate.llm-base-url", llmBaseUrl);
        m.put("tioka.runtime.executor.max-concurrent-turns", 128);
        m.put("tioka.runtime.executor.queue-capacity", 512);
        return m;
    }

    private static Map<String, Object> withInstance(Map<String, Object> shared, String id) {
        Map<String, Object> m = new HashMap<>(shared);
        m.put("tioka.runtime.instance-id", id);
        m.put("spring.application.name", "tioka-" + id);
        return m;
    }
}

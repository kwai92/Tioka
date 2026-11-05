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
        TestContainers.PG.getJdbcUrl();
        LlmStubServer llm = LlmStubServer.start();
        Map<String, Object> shared = baseProps(llm.baseUrl());
        ConfigurableApplicationContext ctxA = run(withInstance(shared, "A"));
        ConfigurableApplicationContext ctxB = run(withInstance(shared, "B"));
        return new DualInstance(ctxA, ctxB, llm);
    }

    public ConfigurableApplicationContext a() { return a; }

    public ConfigurableApplicationContext b() { return b; }

    public int portA() { return portOf(a); }

    public int portB() { return portOf(b); }

    public LlmStubServer llm() { return llm; }

    public String baseUrlA() { return "http://127.0.0.1:" + portA(); }

    public String baseUrlB() { return "http://127.0.0.1:" + portB(); }

    @Override
    public void close() {
        try {
            b.close();
        } finally {
            try {
                a.close();
            } finally {
                llm.close();
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
        m.put("spring.datasource.hikari.maximum-pool-size", "4");
        m.put("spring.datasource.hikari.minimum-idle", "1");
        m.put("spring.data.redis.host", TestContainers.REDIS.getHost());
        m.put("spring.data.redis.port", TestContainers.REDIS.getMappedPort(6379));
        m.put("spring.flyway.enabled", true);
        m.put("spring.sql.init.mode", "never");
        m.put("tioka.bootstrap.admin-password", IntegrationTestSupport.BOOTSTRAP_ADMIN_PASSWORD);
        m.put("tioka.home", System.getProperty("java.io.tmpdir") + "/tioka-gate-home");
        m.put("tioka.model.mock", false);
        m.put("tioka.gate.llm-base-url", llmBaseUrl);
        return m;
    }

    private static Map<String, Object> withInstance(Map<String, Object> shared, String id) {
        Map<String, Object> m = new HashMap<>(shared);
        m.put("tioka.runtime.instance-id", id);
        m.put("spring.application.name", "tioka-" + id);
        return m;
    }
}

package com.tioka.server.support;

import org.springframework.test.context.DynamicPropertyRegistry;

/** 集成测试属性注册（与 {@link IntegrationTest} 注解分离，避免注解接口加载异常）。 */
public final class IntegrationTestSupport {
    private IntegrationTestSupport() {}

    /** 集成测试固定初始管理员密码（经 BootstrapAdminRunner） */
    public static final String BOOTSTRAP_ADMIN_PASSWORD = "TestAdmin!23456";

    public static void register(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("tioka.bootstrap.admin-password", () -> BOOTSTRAP_ADMIN_PASSWORD);
        registry.add("tioka.home", () -> System.getProperty("java.io.tmpdir") + "/tioka-it-home");
        // 多 Spring 上下文会各自建池；限制池大小避免打满 Testcontainers PG
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        // 与 GateCluster / DualInstance 共享 Testcontainers Redis 时，禁止本上下文抢 rt:turns
        registry.add("tioka.runtime.dispatcher-enabled", () -> "false");
        registry.add("tioka.runtime.queued-sweeper-enabled", () -> "false");
    }
}

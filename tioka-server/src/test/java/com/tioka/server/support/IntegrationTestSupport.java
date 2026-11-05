package com.tioka.server.support;

import org.springframework.test.context.DynamicPropertyRegistry;

/** 集成测试属性注册（与 {@link IntegrationTest} 注解分离，避免注解接口加载异常）。 */
public final class IntegrationTestSupport {
    private IntegrationTestSupport() {}

    public static void register(DynamicPropertyRegistry registry) {
        TestContainers.register(registry);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }
}

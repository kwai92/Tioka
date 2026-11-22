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
        // 同理关掉 LeaseReaper 的**定时**回收：本类支撑的 IT（如 RecoveryIT / LeaseReaperIT）自己
        // plantRunning 后显式调 reaper.reap() 再断言终态，若后台调度器每 10s 同时在跑，会与测试线程
        // 争抢同一行——调度器抢先置 INTERRUPTED 后测试的 markInterrupted 返回 0 而跳过，测试遂读到
        // 尚未推进到 QUEUED 的中间态（偶发 expected: <QUEUED> but was: <INTERRUPTED>）。
        // 只关定时触发，LeaseReaper bean 仍装配，reap() 可继续显式调用；GateCluster 系闸门依赖定时回收，
        // 不走本方法，故不受影响。
        registry.add("tioka.runtime.lease-reaper-enabled", () -> "false");
        // KNW-01：IT 用 32 维，与 ProviderClient mock / LlmStub 默认一致
        registry.add("spring.flyway.placeholders.embeddingDim", () -> "32");
        registry.add("tioka.knowledge.embedding-dim", () -> "32");
        registry.add("tioka.knowledge.reindex-enabled", () -> "false");
    }
}

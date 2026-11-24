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
        // 同理关掉 OrchTimerPoller 的**定时**扫描（默认 tioka.orch.timer-poll-enabled=true、间隔 5s）。
        // 机理与上面 LeaseReaper 那条逐字同构：OrchTimerPoller.tick() 以 SYSTEM principal 调
        // pollOnce()，而 OrchTimerMapper.claimDue 是「全表扫描（不按空间隔离，任何身份都扫全量）+
        // ORDER BY next_fire_at LIMIT 100 + FOR UPDATE SKIP LOCKED」。TestContainers.PG 是
        // static final（同一 JVM 内所有 IT 共享一个 PG），Spring 又会缓存并保持各 @SpringBootTest
        // 上下文活跃，故**本类支撑的每个上下文的 poller 都在每 5s 扫同一张 orch_timer 表**。
        // 单个 IT 在自己的 @DynamicPropertySource 里关掉本开关**不足以**自保——它管不住其它上下文
        // 的 poller；后者一旦先领走并行锁，测试自己的 pollOnce() 就被 SKIP LOCKED 跳过，
        // 表现为 next_fire_at 未被推进（2026-11-25 实测 OrchTimerIT.misfirePolicies:186
        // `assertTrue(afterSkip.getNextFireAt().isAfter(before))` 偶发 false；
        // survivesRestart:102 的 `fired >= 1` 与 noDoubleFireAcrossInstances:153 的
        // `assertEquals(1, totalFired)` 同样暴露于此竞态，只是尚未被观测到）。
        // 只关定时触发：OrchTimerService bean 仍装配，OrchTimerIT 的 5 处 pollOnce() 显式调用不受影响
        // （全仓无任何测试依赖 poller 自动触发——零 OrchTimerPoller/.tick() 引用）。
        // GateCluster 系闸门不走本方法（独立装配、gate job 亦为独立 JVM 与独立 PG），故不受影响；
        // 其未设本开关即沿用默认 true，因 gate 用例不建 orch_timer 行，扫空表无害。
        registry.add("tioka.orch.timer-poll-enabled", () -> "false");
        // KNW-01：IT 用 32 维，与 ProviderClient mock / LlmStub 默认一致
        registry.add("spring.flyway.placeholders.embeddingDim", () -> "32");
        registry.add("tioka.knowledge.embedding-dim", () -> "32");
        registry.add("tioka.knowledge.reindex-enabled", () -> "false");
    }
}

package com.tioka.server.auth;

import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.system.application.auth.BootstrapAdminRunner;
import com.tioka.system.infrastructure.SysUserRoleRepository;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V110 起 tenant_admin 落在 {@code sys_user_role}，不再写入 {@code sys_member}。
 * 本用例不得依赖其它 IT（如 WorkspaceMemberIT）偶发回填 sys_member 才能通过。
 */
@SpringBootTest
@Tag("it")
class BootstrapAdminIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired
    BootstrapAdminRunner runner;
    @Autowired
    SysUserRoleRepository userRoles;

    @Test
    void createsOnceAcrossTwoRunners() throws Exception {
        assertTrue(userRoles.existsEnabledTenantAdmin(),
                "BootstrapAdminRunner 应已写入 sys_user_role.tenant_admin");

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger errors = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    runner.run(new DefaultApplicationArguments(new String[0]));
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertEquals(0, errors.get());
        assertTrue(userRoles.existsEnabledTenantAdmin());
        assertEquals(1L, userRoles.countByRole("tenant_admin"),
                "并发二次 bootstrap 不得再造出第二个 tenant_admin");
    }
}

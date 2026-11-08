package com.tioka.server.runtime;

import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.runtime.api.spi.ToolCallContexts;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.runtime.application.gateway.PreparedCallStore;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import com.tioka.testkit.FakeErp;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TRU-05：审批挂起期间外部数据漂移 → commit 返回 STALE，不执行（PK-24b / M2-05）。
 */
@SpringBootTest
@Tag("it")
@Import(DriftDetectionIT.FakeErpConfig.class)
class DriftDetectionIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @TestConfiguration
    static class FakeErpConfig {
        @Bean
        FakeErp fakeErp() {
            return new FakeErp()
                    .seedOrder("ord_drift", "OPEN", 2_000_000L);
        }
    }

    @Autowired ToolGateway gateway;
    @Autowired FakeErp erp;
    @Autowired PreparedCallStore store;
    @Autowired TestFixtures fixtures;
    @Autowired PrincipalHolder principals;

    @Test
    void staleFingerprint() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        Principal p = principal(admin);
        ToolGateway.ToolCallContext ctx = ToolCallContexts.forTurn(
                p, "thr_d1", "turn_d1", "ver_drift_" + admin.userId(), "web", List.of(), Map.of());

        String preparedId = principals.runAs(p, () ->
                gateway.prepare(ctx, FakeErp.TOOL_UPDATE_ORDER,
                        Map.of("orderId", "ord_drift", "status", "SHIPPED")).preparedId());

        // 挂起期间改写 before
        erp.mutateBefore("order", "ord_drift", "status", "CANCELLED");

        principals.runAs(p, () -> {
            ToolProvider.ToolResult result = gateway.commit(ctx, preparedId);
            assertFalse(result.ok());
            assertTrue(result.content().contains("数据已变化"), result.content());
            assertEquals(PreparedCallStore.STATUS_STALE, store.find(preparedId).getStatus());
            return null;
        });
        assertEquals("CANCELLED", erp.order("ord_drift").get("status"));
    }

    private static Principal principal(TestFixtures.Actor a) {
        return new Principal(a.userId(), a.workspaceId(), PrincipalType.USER,
                Set.of("workspace_admin"), Set.of("*"), "password");
    }
}

package com.tioka.server.runtime;

import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.runtime.api.spi.GateDecision;
import com.tioka.runtime.api.spi.ToolCallContexts;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.runtime.application.gateway.PreparedCallStore;
import com.tioka.runtime.infrastructure.persistence.TrustActionDO;
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
 * TRU-05：prepare 不改外部状态；commit 幂等（PK-24b / M2-05）。
 */
@SpringBootTest
@Tag("it")
@Import(PrepareCommitIT.FakeErpConfig.class)
class PrepareCommitIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @TestConfiguration
    static class FakeErpConfig {
        @Bean
        FakeErp fakeErp() {
            return new FakeErp()
                    .seedOrder("ord_1", "OPEN", 1_000_000L)
                    .seedAr("ar_1", "OPEN", 500_000L);
        }
    }

    @Autowired ToolGateway gateway;
    @Autowired FakeErp erp;
    @Autowired PreparedCallStore store;
    @Autowired TestFixtures fixtures;
    @Autowired PrincipalHolder principals;

    @Test
    void previewDoesNotMutate() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        Principal p = principal(admin);
        String versionId = "ver_prepare_" + admin.userId();
        ToolGateway.ToolCallContext ctx = ToolCallContexts.forTurn(
                p, "thr_p1", "turn_p1", versionId, "web", List.of(), Map.of());

        String statusBefore = String.valueOf(erp.order("ord_1").get("status"));
        principals.runAs(p, () -> {
            for (int i = 0; i < 3; i++) {
                ToolGateway.PreparedCall prep = gateway.prepare(ctx, FakeErp.TOOL_UPDATE_ORDER,
                        Map.of("orderId", "ord_1", "status", "SHIPPED"));
                assertEquals(GateDecision.REQUIRE_APPROVAL, prep.gate());
                assertFalse(prep.changeSet().estimated());
                assertEquals(PreparedCallStore.STATUS_PROPOSED,
                        store.find(prep.preparedId()).getStatus());
            }
        });
        assertEquals(statusBefore, erp.order("ord_1").get("status"));
    }

    @Test
    void commitIdempotent() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        Principal p = principal(admin);
        String versionId = "ver_commit_" + admin.userId();
        ToolGateway.ToolCallContext ctx = ToolCallContexts.forTurn(
                p, "thr_c1", "turn_c1", versionId, "web", List.of(), Map.of());

        ToolProvider.ToolResult first = principals.runAs(p, () -> {
            ToolGateway.PreparedCall prep = gateway.prepare(ctx, FakeErp.TOOL_UPDATE_ORDER,
                    Map.of("orderId", "ord_1", "status", "SHIPPED"));
            return gateway.commit(ctx, prep.preparedId());
        });
        assertTrue(first.ok(), first.content());
        assertEquals("SHIPPED", erp.order("ord_1").get("status"));

        principals.runAs(p, () -> {
            ToolGateway.PreparedCall prep = gateway.prepare(ctx, FakeErp.TOOL_UPDATE_ORDER,
                    Map.of("orderId", "ord_1", "status", "DONE"));
            ToolProvider.ToolResult once = gateway.commit(ctx, prep.preparedId());
            assertTrue(once.ok(), once.content());
            ToolProvider.ToolResult twice = gateway.commit(ctx, prep.preparedId());
            assertTrue(twice.ok(), twice.content());
            assertEquals(PreparedCallStore.STATUS_APPLIED, store.find(prep.preparedId()).getStatus());
            return null;
        });
        assertEquals("DONE", erp.order("ord_1").get("status"));
    }

    private static Principal principal(TestFixtures.Actor a) {
        return new Principal(a.userId(), a.workspaceId(), PrincipalType.USER,
                Set.of("workspace_admin"), Set.of("*"), "password");
    }
}

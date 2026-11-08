package com.tioka.server.model;

import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.model.api.gateway.ModelPurpose;
import com.tioka.model.application.ModelAdminService;
import com.tioka.server.gate.support.DualInstance;
import com.tioka.server.gate.support.GateSeed;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MH-03：同 binding 重试后成功（不触发 failover）。剧本见 backoff-retry.yaml。
 */
@Tag("it")
class BackoffIT {

    @Test
    void sameBindingRetrySucceedsWithoutFailover() {
        try (DualInstance di = DualInstance.start("backoff-retry",
                Map.of("tioka.model.retry.base-delay", "1ms",
                        "tioka.model.retry.max-attempts", "2"),
                false)) {
            PrincipalHolder principals = di.a().getBean(PrincipalHolder.class);
            ModelAdminService models = di.a().getBean(ModelAdminService.class);
            ModelGateway gateway = di.a().getBean(ModelGateway.class);

            GateSeed.Bundle login = GateSeed.seedPublishedAgent(di.a(), di.llm().baseUrl());
            Seeded seeded = principals.runAs(GateSeed.user(login.userId(), login.workspaceId()), () -> {
                var provider = models.createProvider(
                        new CreateProviderRequest(Ids.newId("p"), di.llm().baseUrl(), "k"));
                String lm = models.createLogicalModel(new CreateLogicalModelRequest(Ids.newId("m"), "tool"));
                String primary = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt-p"));
                String fallback = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt-f"));
                String planId = models.createPlan(
                        new CreateRoutingPlanRequest(Ids.newId("rp"), "PRIMARY_FALLBACK",
                                primary, fallback, null)).id();
                models.publish(planId);
                return new Seeded(login.userId(), login.workspaceId(), planId, primary, fallback);
            });

            Principal p = GateSeed.user(seeded.userId(), seeded.workspaceId());
            ModelGateway.ChatResult r = principals.runAs(p, () -> gateway.chat(
                    new ModelCallContext(p, Ids.newId("sess"), Ids.newId("turn"), null,
                            seeded.planId(), ModelPurpose.CHAT),
                    new ModelGateway.ChatRequest(
                            java.util.List.of(Map.of("role", "user", "content", "ping")),
                            java.util.List.of(), null, null),
                    d -> {}));

            assertEquals("backoff-retry-ok", r.content());
            assertEquals(seeded.primaryBindingId(), r.bindingId(), "应留在主 binding");
            assertEquals(0, r.failoverCount(), "同 binding 重试不得计为 failover");
        }
    }

    private record Seeded(String userId, String workspaceId, String planId,
                          String primaryBindingId, String fallbackBindingId) {}
}

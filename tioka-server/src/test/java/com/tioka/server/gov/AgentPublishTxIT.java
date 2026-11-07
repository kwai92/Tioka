package com.tioka.server.gov;

import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.application.AgentService;
import com.tioka.agent.domain.AgentStore;
import com.tioka.agent.infrastructure.AgentRepository;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.model.api.dto.ProviderDtos.CreateModelBindingRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateLogicalModelRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateProviderRequest;
import com.tioka.model.api.dto.ProviderDtos.CreateRoutingPlanRequest;
import com.tioka.model.application.ModelAdminService;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@Tag("it")
class AgentPublishTxIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired AgentService agents;
    @Autowired AgentRepository agentRepo;
    @Autowired ModelAdminService models;
    @Autowired PrincipalHolder principals;
    @SpyBean AgentStore store;

    @Test
    void failureRollsBack() {
        String ws = Ids.newId("ws");
        principals.runAs(user("1", ws), () -> {
            var provider = models.createProvider(new CreateProviderRequest("p", "mock://x", "k"));
            String lm = models.createLogicalModel(new CreateLogicalModelRequest("m", "tool"));
            String binding = models.createModelBinding(new CreateModelBindingRequest(lm, provider.id(), "gpt"));
            String planId = models.createPlan(new CreateRoutingPlanRequest("r", "FIXED", binding)).id();
            models.publish(planId);

            var agent = agents.create("tx-agent", ws);
            AgentSpec spec = new AgentSpec(
                    1,
                    new AgentSpec.Profile(agent.name(), null, null, List.of(), "CHAT"),
                    new AgentSpec.Instructions(List.of(new AgentSpec.Instructions.Section("main", "hi"))),
                    new AgentSpec.ModelPolicy(planId, false),
                    AgentSpec.ToolSet.empty(),
                    List.of(),
                    AgentSpec.KnowledgeBinding.empty(),
                    AgentSpec.SandboxPolicy.defaults(),
                    AgentSpec.ApprovalSettings.empty(),
                    AgentSpec.Limits.defaults(),
                    null, null, null, null);
            AgentService.DraftView draft = agents.saveDraft(agent.id(), spec, agents.getDraft(agent.id()).revision());

            doThrow(new RuntimeException("boom-second-step")).when(store).putAgent(any());

            assertThrows(RuntimeException.class, () -> agents.publish(agent.id(), draft.revision(), null));

            assertTrue(agentRepo.listVersions(agent.id()).isEmpty(), "第二步失败后不应留下已发布版本");
            assertNull(agentRepo.findAgent(agent.id()).getPublishedVersionId());
        });
    }

    private static Principal user(String uid, String ws) {
        return new Principal(uid, ws, PrincipalType.USER, Set.of("builder"), Set.of("*"), "test");
    }
}

package com.tioka.server.agent;

import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.agent.application.AgentService;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Tag("it")
class AgentDraftIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired AgentService agents;
    @Autowired PrincipalHolder principals;

    @Test
    void optimisticLock() {
        String ws = Ids.newId("ws");
        principals.runAs(user("1", ws), () -> {
            var agent = agents.create("draft-lock", ws);
            AgentService.DraftView base = agents.getDraft(agent.id());
            AgentSpec specA = withText(base.spec(), "alice");
            AgentSpec specB = withText(base.spec(), "bob");

            agents.saveDraft(agent.id(), specA, base.revision());
            BizException ex = assertThrows(BizException.class,
                    () -> agents.saveDraft(agent.id(), specB, base.revision()));
            assertEquals(ErrorCode.STALE_REVISION, ex.getErrorCode());
        });
    }

    private static AgentSpec withText(AgentSpec base, String text) {
        return new AgentSpec(
                base.schemaVersion(),
                base.profile(),
                new AgentSpec.Instructions(List.of(new AgentSpec.Instructions.Section("main", text))),
                base.model(),
                base.tools(),
                base.skills(),
                base.knowledge(),
                base.sandbox(),
                base.approval(),
                base.limits(),
                base.charter(),
                base.output(),
                base.subAgents(),
                base.dataClearance());
    }

    private static Principal user(String uid, String ws) {
        return new Principal(uid, ws, PrincipalType.USER,
                Set.of("system_admin", "builder"), Set.of("*"), "test");
    }
}

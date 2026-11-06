package com.tioka.server.runtime;

import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.knowledge.application.KbToolProvider;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import com.tioka.knowledge.infrastructure.persistence.KbBaseDO;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.runtime.kernel.application.TurnRunner;
import com.tioka.runtime.kernel.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import com.tioka.system.application.auth.PrincipalLoader;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0-1：Turn 按 runAsJson 重载发起人权限；知识库工具不能跨空间读。
 */
@SpringBootTest
@Tag("it")
class TurnPrincipalIsolationIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired TestFixtures fixtures;
    @Autowired PrincipalLoader principalLoader;
    @Autowired PrincipalHolder principals;
    @Autowired KnowledgeRepository knowledge;
    @Autowired KbToolProvider kbTools;
    @Autowired TurnRunner turnRunner;

    @Test
    void resolveRunAsReloadsUserPrincipal() {
        TestFixtures.Actor actor = fixtures.newActor("member");
        RuntimeTurnDO turn = new RuntimeTurnDO();
        turn.setRunAsJson("""
                {"userId":"%s","workspaceId":"%s","type":"USER"}
                """.formatted(actor.userId(), actor.workspaceId()).trim());

        Principal p = turnRunner.resolveRunAs(turn);
        assertEquals(actor.userId(), p.userId());
        assertEquals(actor.workspaceId(), p.workspaceId());
        assertNotEquals("SYSTEM", p.type().name());
    }

    @Test
    void userATurnCannotSearchWorkspaceBKnowledge() {
        TestFixtures.Actor a = fixtures.newActor("member");
        TestFixtures.Actor b = fixtures.newActor("member");
        String kbId = Ids.newId("kb");

        Principal pb = principalLoader.load(Long.parseLong(b.userId()), b.workspaceId());
        principals.runAs(pb, () -> {
            KbBaseDO kb = new KbBaseDO();
            kb.setId(kbId);
            kb.setName("kb-b");
            knowledge.insertBase(kb);
            assertTrue(knowledge.hasBase(kbId));
        });

        Principal pa = principalLoader.load(Long.parseLong(a.userId()), a.workspaceId());
        principals.runAs(pa, () -> {
            assertFalse(knowledge.hasBase(kbId));
            ToolProvider.ToolResult result = kbTools.invoke("kb.search",
                    Map.of("kbId", kbId, "query", "secret", "topK", 3));
            assertFalse(result.ok(), "跨空间 kb.search 应失败: " + result.content());
        });
    }
}

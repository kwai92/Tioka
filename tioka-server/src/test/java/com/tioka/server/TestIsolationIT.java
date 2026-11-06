package com.tioka.server;

import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestFixtures.class)
@Tag("it")
class TestIsolationIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired
    TestFixtures fixtures;

    @Test
    void twoActorsGetDistinctWorkspaces() {
        TestFixtures.Actor a = fixtures.newActor("builder");
        TestFixtures.Actor b = fixtures.newActor("member");
        assertNotEquals(a.workspaceId(), b.workspaceId());
        assertTrue(a.workspaceId().startsWith("ws_"));
        assertTrue(b.workspaceId().startsWith("ws_"));
    }
}

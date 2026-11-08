package com.tioka.server;

import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 旧编排人审环已随 V132 / ORC-05 删除；ORC-03 / ORC-04 重建后恢复本用例。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestFixtures.class)
@Tag("it")
@Disabled("ORC-05 删除旧 orch_attention/claim；ORC-03/04 重建")
class OrchAttentionIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Test
    void eventIdempotentAndAttentionLoop() {
        // disabled
    }
}

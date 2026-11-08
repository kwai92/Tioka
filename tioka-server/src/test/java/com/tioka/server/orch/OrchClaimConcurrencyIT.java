package com.tioka.server.orch;

import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 旧 claim 模型已随 V132 删除；ORC-03 派工重建后恢复。
 */
@SpringBootTest
@Tag("it")
@Disabled("ORC-05 删除旧 OrchService.claim；ORC-03 重建")
class OrchClaimConcurrencyIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Test
    void concurrentClaimUnique() {
        // disabled
    }
}

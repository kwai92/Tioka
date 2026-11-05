package com.tioka.server.fidelity;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Depth P0：fidelity profile 必须存在且默认关 mock。 */
class FidelityProfilePresenceTest {
    @Test
    void fidelityProfile_disablesMocks() throws Exception {
        var res = new ClassPathResource("application-fidelity.yml");
        assertTrue(res.exists());
        String yaml = new String(res.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(yaml.contains("mock: false"));
        assertTrue(yaml.contains("model:") || yaml.contains("tioka:"));
    }
}

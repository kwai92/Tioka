package com.tioka.server.auth;

import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("it")
class AuthControllerIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.auth.login-max-failures", () -> "10");
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void defaultAdmin123Rejected() throws Exception {
        mockMvc.perform(post("/api/system/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void bootstrapAdminLoginOk() throws Exception {
        mockMvc.perform(post("/api/system/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"admin\",\"password\":\""
                                + IntegrationTestSupport.BOOTSTRAP_ADMIN_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.user.mustChangePassword").value(true));
    }

    @Test
    void bruteForceLimited() throws Exception {
        String body = "{\"username\":\"nobody\",\"password\":\"wrong\"}";
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/system/auth/login")
                            .contentType("application/json")
                            .content(body)
                            .with(req -> {
                                req.setRemoteAddr("10.9.8.7");
                                return req;
                            }))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/api/system/auth/login")
                        .contentType("application/json")
                        .content(body)
                        .with(req -> {
                            req.setRemoteAddr("10.9.8.7");
                            return req;
                        }))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42900));
    }
}

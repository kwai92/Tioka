package com.tioka.server;

import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ENG-03：假实现 API 统一 501 / code=50100。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestFixtures.class)
@Tag("it")
class FakeApiOfflineIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired TestFixtures fixtures;

    @Test
    void fakeApisReturn501() throws Exception {
        TestFixtures.Actor actor = fixtures.newWorkspace();

        mockMvc.perform(actor.auth(get("/api/ops/graph"))
                        .param("kbId", "kb_x")
                        .param("entity", "e"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value(50100));

        mockMvc.perform(actor.auth(post("/api/ops/interpreter"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"print(1)\"}"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value(50100));

        mockMvc.perform(actor.auth(post("/api/ops/jobs"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"job\"}"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value(50100));

        mockMvc.perform(actor.auth(post("/api/ops/chat/conv_x/fork")))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value(50100));

        mockMvc.perform(actor.auth(post("/api/model/routing-plans"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"diff\",\"type\":\"DIFFICULTY\",\"primaryBindingId\":\"bnd_x\"}"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.code").value(50100));
    }
}

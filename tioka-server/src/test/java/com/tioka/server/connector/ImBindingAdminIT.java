package com.tioka.server.connector;

import com.jayway.jsonpath.JsonPath;
import com.tioka.server.gate.support.feishu.FeishuStubServer;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IM-ADM / PK-15：IM 绑定增删改查、密钥只写不读、回调地址与连通性检查。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("it")
class ImBindingAdminIT {

    private static FeishuStubServer FEISHU;

    @BeforeAll
    static void startStub() {
        FEISHU = FeishuStubServer.start();
    }

    @AfterAll
    static void stopStub() {
        if (FEISHU != null) {
            FEISHU.close();
        }
    }

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.im.feishu.api-base", () -> FEISHU.baseUrl());
        registry.add("tioka.public-url", () -> "https://tioka.test.example");
        registry.add("tioka.egress.mode", () -> "OPEN");
    }

    @Autowired MockMvc mockMvc;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;

    @Test
    void crudSecretsWriteOnlyCallbackAndConnectivity() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        String createBody = """
                {
                  "name":"飞书演示",
                  "workspaceId":"%s",
                  "channel":"feishu",
                  "appId":"cli_im_adm_it",
                  "appSecret":"sec_plaintext_never_echo",
                  "encryptKey":"enc_key_plain",
                  "verificationToken":"verify_token_plain",
                  "defaultAgentId":"agt_demo",
                  "orchEnabled":true,
                  "identityMatch":"link"
                }
                """.formatted(admin.workspaceId());

        MvcResult created = mockMvc.perform(admin.auth(post("/api/connector/im/bindings"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.channel").value("feishu"))
                .andExpect(jsonPath("$.data.workspaceId").value(admin.workspaceId()))
                .andExpect(jsonPath("$.data.appId").value("cli_im_adm_it"))
                .andExpect(jsonPath("$.data.defaultAgentId").value("agt_demo"))
                .andExpect(jsonPath("$.data.orchEnabled").value(true))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.callbackUrl").value(containsString(
                        "/api/connector/im/feishu/")))
                .andExpect(jsonPath("$.data.callbackUrl").value(containsString("/events")))
                .andExpect(jsonPath("$.data.appSecret").doesNotExist())
                .andExpect(jsonPath("$.data.encryptKey").doesNotExist())
                .andExpect(jsonPath("$.data.verificationToken").doesNotExist())
                .andExpect(jsonPath("$.data.secretRef").doesNotExist())
                .andExpect(jsonPath("$.data.encryptKeyRef").doesNotExist())
                .andReturn();

        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");
        String callbackUrl = JsonPath.read(created.getResponse().getContentAsString(), "$.data.callbackUrl");
        assertEquals("https://tioka.test.example/api/connector/im/feishu/" + id + "/events", callbackUrl);

        // 明文密钥不得落库；库中仅有 SecretStore 引用
        String secretRef = jdbc.queryForObject(
                "SELECT secret_ref FROM im_binding WHERE id = ?", String.class, id);
        assertFalse(secretRef.contains("sec_plaintext_never_echo"));
        assertTrue(secretRef.contains("::"));

        mockMvc.perform(admin.auth(get("/api/connector/im/bindings/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.callbackUrl").value(callbackUrl))
                .andExpect(jsonPath("$.data.appSecret").doesNotExist())
                .andExpect(jsonPath("$.data.secretRef").doesNotExist());

        mockMvc.perform(admin.auth(get("/api/connector/im/bindings")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id=='" + id + "')]").exists());

        mockMvc.perform(admin.auth(patch("/api/connector/im/bindings/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orchEnabled":false,"enabled":true,"defaultAgentId":"agt_patched","identityMatch":"email"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orchEnabled").value(false))
                .andExpect(jsonPath("$.data.defaultAgentId").value("agt_patched"))
                .andExpect(jsonPath("$.data.identityMatch").value("email"))
                .andExpect(jsonPath("$.data.appSecret").doesNotExist());

        mockMvc.perform(admin.auth(post("/api/connector/im/bindings/" + id + "/connectivity"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true));

        assertTrue(FEISHU.tokenIssueCount() >= 1, "连通性检查应向飞书桩换取 tenant_access_token");

        mockMvc.perform(admin.auth(delete("/api/connector/im/bindings/" + id)))
                .andExpect(status().isOk());

        mockMvc.perform(admin.auth(get("/api/connector/im/bindings/" + id)))
                .andExpect(status().isNotFound());
    }

    @Test
    void secretsNotReadableAfterCreate() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        MvcResult created = mockMvc.perform(admin.auth(post("/api/connector/im/bindings"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workspaceId":"%s",
                                  "appId":"cli_secret_only",
                                  "appSecret":"super-secret-value"
                                }
                                """.formatted(admin.workspaceId())))
                .andExpect(status().isOk())
                .andReturn();
        String body = created.getResponse().getContentAsString();
        assertFalse(body.contains("super-secret-value"), "响应不得回显应用密钥明文");
        String id = JsonPath.read(body, "$.data.id");

        MvcResult got = mockMvc.perform(admin.auth(get("/api/connector/im/bindings/" + id)))
                .andExpect(status().isOk())
                .andReturn();
        assertFalse(got.getResponse().getContentAsString().contains("super-secret-value"));
    }
}

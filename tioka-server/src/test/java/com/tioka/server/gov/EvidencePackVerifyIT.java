package com.tioka.server.gov;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.foundation.common.id.Ids;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import com.tioka.system.application.evidence.EvidencePackVerifier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OBS-12 / ADR-075 §5：证据包导出 — 平台公钥可独立重算各文件哈希与包整体哈希并验签；
 * 任一文件被改动后校验失败。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("it")
class EvidencePackVerifyIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    @Test
    void exportedPackVerifiesWithPublicKeyAndFailsWhenTampered() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        String subject = "order:verify-" + System.nanoTime();
        seedExtInteraction(admin.workspaceId(), subject, "OUT", "INTERNAL", "ext-ref-" + System.nanoTime());

        MvcResult export = mockMvc.perform(admin.auth(get("/api/system/evidence-pack/export")
                        .param("scopeType", "SUBJECT")
                        .param("scopeRef", subject)))
                .andExpect(status().isOk())
                .andReturn();
        byte[] bundle = export.getResponse().getContentAsByteArray();
        assertTrue(bundle.length > 0, "证据包不得为空");
        assertNotNull(export.getResponse().getHeader("X-Pack-Id"), "须回包标识");
        assertNotNull(export.getResponse().getHeader("X-Pack-Sha256"), "须回包哈希");

        MvcResult keyResult = mockMvc.perform(
                        admin.auth(get("/api/system/evidence-pack/public-key")))
                .andExpect(status().isOk())
                .andReturn();
        String publicKeyPem = objectMapper.readTree(keyResult.getResponse().getContentAsString())
                .path("data").path("publicKeyPem").asText();
        assertFalse(publicKeyPem.isBlank(), "平台公钥须可下发");

        EvidencePackVerifier.VerifyReport report = EvidencePackVerifier.verify(bundle, publicKeyPem, objectMapper);
        assertTrue(report.ok(), "证据包须以平台公钥验签通过: " + report);
        assertTrue(report.packageHashMatch(), "包整体哈希须一致");
        assertTrue(report.signatureValid(), "签名须有效");
        assertFalse(report.files().isEmpty(), "证据包须含文件清单");

        // 改动任一文件内容 → 校验失败
        ObjectNode root = (ObjectNode) objectMapper.readTree(bundle);
        JsonNode files = root.get("files");
        assertNotNull(files, "缺少 files");
        ObjectNode first = (ObjectNode) files.get(0);
        first.put("content", first.path("content").asText() + "\n// tampered");
        byte[] tampered = objectMapper.writeValueAsBytes(root);

        EvidencePackVerifier.VerifyReport afterTamper =
                EvidencePackVerifier.verify(tampered, publicKeyPem, objectMapper);
        assertFalse(afterTamper.ok(), "改动文件内容后校验必须失败");
        assertFalse(afterTamper.packageHashMatch(), "改动文件内容后包整体哈希须不一致");
    }

    private void seedExtInteraction(String workspaceId, String subject, String direction,
                                    String dataLabel, String externalRef) {
        jdbc.update("""
                INSERT INTO ext_interaction(
                  id, ts, direction, kind, auth_mode, attempt, status, retention_class,
                  data_label, subject, external_ref)
                VALUES (?, now(), ?, 'http', 'NONE', 1, 'OK', 'BIZ', ?, ?, ?)
                """, Ids.newId("ext"), direction, dataLabel, subject, externalRef);
    }
}

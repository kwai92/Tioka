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

        // 篡改场景一：只改 content，不改 sha256 → 逐文件内容摘要比对必须抓出
        // 注意：packageHash 只对 {文件名 → 声明 sha256} 求哈希，与「重算内容摘要」是两道独立防线
        // （见 EvidencePackVerifier 类注释）。只动 content 不动 sha256 时包哈希不变，
        // 但 allFilesOk 变 false、ok() 变 false，篡改仍被检出——这是设计如此，不是漏检。
        ObjectNode root = (ObjectNode) objectMapper.readTree(bundle);
        JsonNode files = root.get("files");
        assertNotNull(files, "缺少 files");
        ObjectNode first = (ObjectNode) files.get(0);
        String originalContent = first.path("content").asText();
        first.put("content", originalContent + "\n// tampered");
        byte[] tampered = objectMapper.writeValueAsBytes(root);

        EvidencePackVerifier.VerifyReport afterTamper =
                EvidencePackVerifier.verify(tampered, publicKeyPem, objectMapper);
        assertFalse(afterTamper.ok(), "改动文件内容后校验必须失败");
        assertTrue(afterTamper.files().stream().anyMatch(f -> !f.ok()),
                "必须定位到被篡改的文件：内容重算摘要与声明值不符");

        // 篡改场景二：改 content 的同时把 sha256 改成篡改后内容的正确摘要
        // → 逐文件比对已骗过，此时必须靠包整体哈希与签名兜住（否则可伪造证据包）
        ObjectNode root2 = (ObjectNode) objectMapper.readTree(bundle);
        ObjectNode first2 = (ObjectNode) root2.get("files").get(0);
        String forgedContent = originalContent + "\n// forged";
        first2.put("content", forgedContent);
        first2.put("sha256", sha256Hex(forgedContent));
        byte[] forged = objectMapper.writeValueAsBytes(root2);

        EvidencePackVerifier.VerifyReport afterForge =
                EvidencePackVerifier.verify(forged, publicKeyPem, objectMapper);
        assertFalse(afterForge.ok(), "同步伪造 sha256 后校验仍必须失败");
        assertFalse(afterForge.packageHashMatch(), "文件哈希集变化后包整体哈希须不一致");
        assertFalse(afterForge.signatureValid(), "包哈希变化后平台签名须失效（无法伪造签名）");
    }

    /** 与 EvidencePackVerifier 同口径的内容摘要，用于构造「同时改内容与声明哈希」的伪造包。 */
    private static String sha256Hex(String content) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    md.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
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

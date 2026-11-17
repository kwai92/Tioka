package com.tioka.server.gov;

import com.foundation.common.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import com.tioka.system.application.evidence.EvidencePackService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-12 / ADR-075 §5：证据包导出 — CONFIDENTIAL 内容按导出人权限决定是否包含；
 * 无权限时省略并登记省略项，包内不含其原文。
 */
@SpringBootTest
@Tag("it")
class EvidencePackLabelIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired EvidencePackService service;
    @Autowired PrincipalHolder principals;
    @Autowired TestFixtures fixtures;
    @Autowired JdbcTemplate jdbc;

    @Test
    void confidentialOmittedWithoutClearanceAndIncludedWith() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        String subject = "order:label-" + System.nanoTime();
        String secretMarker = "CONF-MARKER-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO ext_interaction(
                  id, ts, direction, kind, auth_mode, attempt, status, retention_class,
                  data_label, subject, external_ref)
                VALUES (?, now(), 'OUT', 'http', 'NONE', 1, 'OK', 'BIZ', 'CONFIDENTIAL', ?, ?)
                """, Ids.newId("ext"), subject, secretMarker);

        // 无 trajectory:read:content 且非平台管理员：clearance = INTERNAL，省略 CONFIDENTIAL
        Principal limited = new Principal(admin.userId(), admin.workspaceId(), PrincipalType.USER,
                Set.of("member"), Set.of("audit:read", "audit:export"), "password");
        EvidencePackService.ExportResult gated = principals.runAs(limited,
                () -> service.export(new EvidencePackService.ExportRequest("SUBJECT", subject, null, null)));
        assertFalse(gated.includeConfidential(), "无权限导出不得包含 CONFIDENTIAL");
        assertTrue(gated.omittedSections().contains("confidential"), "须登记省略项 confidential");
        String gatedBundle = new String(gated.content(), StandardCharsets.UTF_8);
        assertFalse(gatedBundle.contains(secretMarker), "无权限导出不得含 CONFIDENTIAL 原文");

        // 持 trajectory:read:content：clearance = SECRET，包含 CONFIDENTIAL
        Principal privileged = new Principal(admin.userId(), admin.workspaceId(), PrincipalType.USER,
                Set.of("member"), Set.of("audit:read", "audit:export", "trajectory:read:content"), "password");
        EvidencePackService.ExportResult open = principals.runAs(privileged,
                () -> service.export(new EvidencePackService.ExportRequest("SUBJECT", subject, null, null)));
        assertTrue(open.includeConfidential(), "持权导出须包含 CONFIDENTIAL");
        String openBundle = new String(open.content(), StandardCharsets.UTF_8);
        assertTrue(openBundle.contains(secretMarker), "持权导出须含 CONFIDENTIAL 原文");
    }
}

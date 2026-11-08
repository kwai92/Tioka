package com.tioka.server.gov;

import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.server.gov.support.Obs01FailureProbe;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OBS-01：{@code @Audited} 对非 BizException 记 ERROR（独立事务）。
 */
@SpringBootTest
@Tag("it")
class AuditFailureSemanticsIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired TestFixtures fixtures;
    @Autowired PrincipalHolder principals;
    @Autowired AuditApi audit;
    @Autowired Obs01FailureProbe probe;

    @Test
    void runtimeExceptionRecordsErrorAudit() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        Principal p = new Principal(admin.userId(), admin.workspaceId(), PrincipalType.USER,
                Set.of("workspace_admin"), Set.of("*"), "password");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> principals.runAs(p, () -> {
                    probe.failWithRuntime();
                    return null;
                }));
        assertEquals("obs01-forced-runtime", thrown.getMessage());

        List<AuditEvent> rows = audit.query(new AuditApi.AuditQuery(
                admin.workspaceId(), admin.userId(), "obs01.probe.runtime_error",
                "probe", null, AuditOutcome.ERROR, null, null, null, 20));
        assertFalse(rows.isEmpty(), "RuntimeException 应有 ERROR 审计（REQUIRES_NEW）");
        assertTrue(rows.stream().anyMatch(e ->
                e.reason() != null && e.reason().contains("obs01-forced-runtime")));
    }
}

package com.tioka.server.gov;

import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import com.tioka.system.application.audit.AuditForwarder;
import com.tioka.system.infrastructure.AuditEventRepository;
import com.tioka.system.infrastructure.spi.FileAuditSink;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GOV-02：{@link AuditForwarder} 写文件 sink 并置 {@code forwarded=true}。
 */
@SpringBootTest
@Tag("it")
class AuditForwarderIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired TestFixtures fixtures;
    @Autowired PrincipalHolder principals;
    @Autowired AuditApi audit;
    @Autowired AuditForwarder forwarder;
    @Autowired FileAuditSink sink;
    @Autowired AuditEventRepository repository;

    @Test
    void fileSinkReceivesAndMarksForwarded() {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");
        String action = "gov02.forward." + UUID.randomUUID().toString().substring(0, 8);
        principals.runAs(new Principal(admin.userId(), admin.workspaceId(), PrincipalType.USER,
                        Set.of("workspace_admin"), Set.of("*"), "password"),
                () -> audit.record(AuditEvent.builder()
                        .ts(Instant.now())
                        .workspaceId(admin.workspaceId())
                        .actorType("user")
                        .actorId(admin.userId())
                        .action(action)
                        .outcome(AuditOutcome.SUCCESS)
                        .build()));

        forwarder.forwardBatch();

        List<Map<String, Object>> recent = sink.recent(50);
        assertTrue(recent.stream().anyMatch(r -> action.equals(String.valueOf(r.get("action")))),
                "文件 sink 应收到转发记录");

        List<Map<String, Object>> rows = repository.query(
                new com.tioka.system.infrastructure.persistence.AuditEventQuery(
                        admin.workspaceId(), admin.userId(), action, null, null,
                        AuditOutcome.SUCCESS.name(), null, null, 10, 0));
        assertFalse(rows.isEmpty());
        assertTrue(rows.stream().allMatch(r -> Boolean.TRUE.equals(r.get("forwarded"))
                        || "true".equalsIgnoreCase(String.valueOf(r.get("forwarded")))),
                "forwarded 应置为 true");
    }
}

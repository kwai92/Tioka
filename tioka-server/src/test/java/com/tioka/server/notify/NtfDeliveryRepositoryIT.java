package com.tioka.server.notify;

import com.tioka.infra.infrastructure.NtfDeliveryRepository;
import com.tioka.infra.infrastructure.SysExternalIdentityRepository;
import com.tioka.infra.infrastructure.persistence.NtfDeliveryDO;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NtfDeliveryRepository IT（Testcontainers PG）：ON CONFLICT(dedup_key) 幂等、
 * PENDING→SENT/FAILED 状态流转、REQUIRES_NEW 记账、外部身份仓储往返。
 */
@SpringBootTest
@Tag("it")
class NtfDeliveryRepositoryIT {

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.notify.enabled", () -> "true");
    }

    @Autowired NtfDeliveryRepository repository;
    @Autowired SysExternalIdentityRepository identities;
    @Autowired JdbcTemplate jdbc;

    @Test
    void insertIfAbsentIsIdempotentOnDedupKey() {
        String dedupKey = "it:" + UUID.randomUUID() + ":noop:r1";
        assertTrue(repository.insertIfAbsent(row(dedupKey)), "首次应新插入");
        assertFalse(repository.insertIfAbsent(row(dedupKey)), "重复 dedup_key 不得插入（ON CONFLICT DO NOTHING）");
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ntf_delivery WHERE dedup_key = ?", Integer.class, dedupKey);
        assertEquals(1, count);
    }

    @Test
    void statusFlowPendingToSent() {
        String dedupKey = "it:" + UUID.randomUUID() + ":noop:r2";
        NtfDeliveryDO row = row(dedupKey);
        assertTrue(repository.insertIfAbsent(row));
        NtfDeliveryDO stored = repository.findById(row.getDeliveryId());
        assertEquals("PENDING", stored.getStatus());
        assertEquals(0, stored.getAttempts());
        assertNotNull(stored.getCreatedAt());

        Instant sentAt = Instant.now();
        assertTrue(repository.markSent(stored.getDeliveryId(), "ext-ref-1", sentAt));
        // 终态幂等：非 PENDING 不再变更
        assertFalse(repository.markSent(stored.getDeliveryId(), "ext-ref-2", sentAt));
        assertFalse(repository.markFailed(stored.getDeliveryId(), "late-failure"));

        NtfDeliveryDO sent = repository.findById(stored.getDeliveryId());
        assertEquals("SENT", sent.getStatus());
        assertEquals("ext-ref-1", sent.getExternalRef());
        assertNotNull(sent.getSentAt());
    }

    @Test
    void statusFlowPendingToFailed() {
        String dedupKey = "it:" + UUID.randomUUID() + ":noop:r3";
        NtfDeliveryDO row = row(dedupKey);
        assertTrue(repository.insertIfAbsent(row));

        assertTrue(repository.markFailed(row.getDeliveryId(), "CHANNEL_NOT_REGISTERED"));
        NtfDeliveryDO failed = repository.findById(row.getDeliveryId());
        assertEquals("FAILED", failed.getStatus());
        assertEquals("CHANNEL_NOT_REGISTERED", failed.getLastError());
        assertNull(failed.getSentAt());
    }

    @Test
    void recordAttemptAccumulates() {
        String dedupKey = "it:" + UUID.randomUUID() + ":noop:r4";
        NtfDeliveryDO row = row(dedupKey);
        assertTrue(repository.insertIfAbsent(row));

        assertEquals(1, repository.recordAttempt(row.getDeliveryId(), "boom-1"));
        assertEquals(2, repository.recordAttempt(row.getDeliveryId(), "boom-2"));
        assertEquals(3, repository.recordAttempt(row.getDeliveryId(), "boom-3"));

        NtfDeliveryDO after = repository.findById(row.getDeliveryId());
        assertEquals(3, after.getAttempts());
        assertEquals("boom-3", after.getLastError());
        assertEquals("PENDING", after.getStatus(), "记账不改状态（终态由 markFailed 承担）");
    }

    @Test
    void externalIdentityRoundTrip() {
        String userId = "u-repo-" + UUID.randomUUID();
        jdbc.update("INSERT INTO sys_external_identity (source, external_id, user_id, create_at, create_by) "
                + "VALUES ('noop', ?, ?, now(), 'it')", "ext-" + userId, userId);

        assertEquals("ext-" + userId, identities.findExternalIdByUser(userId, "noop"));
        assertNull(identities.findExternalIdByUser(userId, "email"), "来源不匹配应无映射");
        assertEquals("ext-" + userId, identities.findExternalId("ext-" + userId, "noop"));
        assertNull(identities.findExternalId("u-unknown", "noop"));
    }

    private static NtfDeliveryDO row(String dedupKey) {
        NtfDeliveryDO row = new NtfDeliveryDO();
        row.setDeliveryId(UUID.randomUUID().toString().replace("-", "").substring(0, 26));
        row.setDedupKey(dedupKey);
        row.setChannel("noop");
        row.setRecipient("r1");
        row.setNoticeJson("{\"noticeKey\":\"it\",\"title\":\"标题\",\"blocks\":[],\"actions\":[],\"label\":\"INTERNAL\"}");
        row.setStatus("PENDING");
        row.setAttempts(0);
        return row;
    }
}

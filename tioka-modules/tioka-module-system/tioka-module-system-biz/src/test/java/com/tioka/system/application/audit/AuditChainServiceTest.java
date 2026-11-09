package com.tioka.system.application.audit;

import com.tioka.system.application.audit.AuditChainService.ChainVerifyResult;
import com.tioka.system.application.audit.AuditChainService.SealOutcome;
import com.tioka.system.infrastructure.AuditEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GOV-09 审计哈希链离线单测：封印推演（链式哈希、分链、锚）、校验重算（篡改/断链/锚不一致）、
 * 规范化与摘要已知向量。Redis 用内存 KV 模拟，事务管理器用 mock（事务边界在 IT 归 CI 验证）。
 */
class AuditChainServiceTest {

    private AuditEventRepository repository;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private Map<String, String> kv;
    private AuditChainService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuditEventRepository.class);
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        kv = new ConcurrentHashMap<>();
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(inv -> kv.get(inv.getArgument(0, String.class)));
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0, String.class);
            return kv.putIfAbsent(key, inv.getArgument(1, String.class)) == null;
        });
        when(redis.delete(anyString())).thenAnswer(inv -> kv.remove(inv.getArgument(0, String.class)) != null);
        service = new AuditChainService(repository, mock(PlatformTransactionManager.class), redis);
    }

    /* ---------- 已知向量与规范化 ---------- */

    @Test
    void sha256MatchesKnownVector() {
        // NIST FIPS-180 向量：sha256("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                AuditChainService.sha256Hex("abc"));
    }

    @Test
    void canonicalJoinsFixedFieldOrderAndNormalizesTimestamp() {
        Map<String, Object> row = baseRow("aud_1", "ws1");
        row.put("ts", OffsetDateTime.of(2026, 2, 11, 8, 30, 0, 0, ZoneOffset.ofHours(8)));
        // 固定 17 字段 '|' 连接；时间统一转 UTC ISO-8601；未设字段 null → 空串
        assertEquals("aud_1|2026-02-11T00:30:00Z|ws1|user|u1|audit.login|||SUCCESS||||tioka-server||||",
                AuditChainService.canonical(row));
    }

    @Test
    void chainKeyFallsBackToSystem() {
        assertEquals("_system", AuditChainService.chainKey(null));
        assertEquals("_system", AuditChainService.chainKey("  "));
        assertEquals("ws1", AuditChainService.chainKey("ws1"));
    }

    /* ---------- 封印推演 ---------- */

    @Test
    void sealPendingSealsChainsWithHashLinkingAndAnchors() {
        List<Map<String, Object>> rows = List.of(
                baseRow("aud_1", "ws1"),
                baseRow("aud_2", "ws1"),
                baseRow("aud_3", null)); // 系统级 → _system 链
        when(repository.listUnsealed(500)).thenReturn(rows);
        when(repository.getSeal("ws1")).thenReturn(null);
        when(repository.getSeal("_system")).thenReturn(null);

        SealOutcome outcome = service.sealPending(500);

        assertTrue(outcome instanceof SealOutcome.Sealed);
        SealOutcome.Sealed sealed = (SealOutcome.Sealed) outcome;
        assertEquals(3, sealed.events());
        assertEquals(2, sealed.chains());

        // 链式哈希：每条 prev = 上一条 hash；链首 prev = ""
        String h1 = AuditChainService.sha256Hex("" + "\n" + AuditChainService.canonical(rows.get(0)));
        String h2 = AuditChainService.sha256Hex(h1 + "\n" + AuditChainService.canonical(rows.get(1)));
        String h3 = AuditChainService.sha256Hex("" + "\n" + AuditChainService.canonical(rows.get(2)));
        verify(repository).markSealed("aud_1", "", h1);
        verify(repository).markSealed("aud_2", h1, h2);
        verify(repository).markSealed("aud_3", "", h3);
        // 链末锚 upsert：lastHash = 链末哈希
        verify(repository).upsertSeal("ws1", "aud_2", h2, 2);
        verify(repository).upsertSeal("_system", "aud_3", h3, 1);
        // 释放锁
        verify(redis).delete(eq(AuditChainService.LOCK_KEY));
    }

    @Test
    void sealPendingContinuesFromAnchor() {
        List<Map<String, Object>> rows = List.of(baseRow("aud_9", "ws1"));
        when(repository.listUnsealed(500)).thenReturn(rows);
        // 已有锚：链首 prev 取锚 last_hash
        Map<String, Object> anchor = new HashMap<>();
        anchor.put("lastHash", "prev-anchor-hash");
        when(repository.getSeal("ws1")).thenReturn(anchor);

        service.sealPending(500);

        String expected = AuditChainService.sha256Hex("prev-anchor-hash" + "\n"
                + AuditChainService.canonical(rows.get(0)));
        verify(repository).markSealed("aud_9", "prev-anchor-hash", expected);
        verify(repository).upsertSeal("ws1", "aud_9", expected, 1);
    }

    @Test
    void sealPendingSkippedWhenLockHeld() {
        kv.put(AuditChainService.LOCK_KEY, "another-instance");

        SealOutcome outcome = service.sealPending(500);

        assertTrue(outcome instanceof SealOutcome.SkippedByLock);
        verify(repository, never()).listUnsealed(500);
    }

    @Test
    void sealPendingEmptyWhenNoUnsealedRows() {
        when(repository.listUnsealed(500)).thenReturn(List.of());

        SealOutcome outcome = service.sealPending(500);

        assertTrue(outcome instanceof SealOutcome.Empty);
        verify(repository, never()).markSealed(anyString(), anyString(), anyString());
    }

    /* ---------- 校验重算 ---------- */

    @Test
    void verifyAcceptsIntactChain() {
        List<Map<String, Object>> sealedRows = sealedChain("ws1", "aud_1", "aud_2");
        when(repository.listSealedByChain("ws1")).thenReturn(sealedRows);
        Map<String, Object> anchor = new HashMap<>();
        anchor.put("lastHash", sealedRows.get(1).get("hash"));
        when(repository.getSeal("ws1")).thenReturn(anchor);

        ChainVerifyResult r = service.verify("ws1");

        assertTrue(r.intact());
        assertEquals(2, r.checked());
        assertTrue(r.anchorMatch());
        assertNull(r.brokenAtId());
        assertTrue(r.summary().contains("校验通过"));
    }

    @Test
    void verifyDetectsRecordTampering() {
        List<Map<String, Object>> rows = sealedChain("ws1", "aud_1", "aud_2");
        // 篡改第二条记录内容（哈希未重算 → 必然检出）
        rows.get(1).put("detailsJson", "{\"evil\":true}");
        when(repository.listSealedByChain("ws1")).thenReturn(rows);
        when(repository.getSeal("ws1")).thenReturn(null);

        ChainVerifyResult r = service.verify("ws1");

        assertFalse(r.intact());
        assertEquals("aud_2", r.brokenAtId());
        // actualHash = 篡改行落库的原哈希；expectedHash = 内容重算的新哈希，两者必然不同
        assertEquals(rows.get(1).get("hash"), r.actualHash());
        assertTrue(r.summary().contains("篡改"));
    }

    @Test
    void verifyDetectsChainOrderBreak() {
        // 第二条 prev_hash 与首条 hash 不衔接（链序断裂）
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> first = sealedChain("ws1", "aud_1").get(0);
        Map<String, Object> second = baseRow("aud_2", "ws1");
        second.put("prevHash", "orphan-prev"); // 不等于 first.hash
        second.put("hash", AuditChainService.sha256Hex("orphan-prev\n" + AuditChainService.canonical(second)));
        rows.add(first);
        rows.add(second);
        when(repository.listSealedByChain("ws1")).thenReturn(rows);
        when(repository.getSeal("ws1")).thenReturn(null);

        ChainVerifyResult r = service.verify("ws1");

        assertFalse(r.intact());
        assertEquals("aud_2", r.brokenAtId());
        assertTrue(r.summary().contains("链序断裂"));
    }

    @Test
    void verifyDetectsAnchorMismatch() {
        List<Map<String, Object>> rows = sealedChain("ws1", "aud_1");
        when(repository.listSealedByChain("ws1")).thenReturn(rows);
        Map<String, Object> anchor = new HashMap<>();
        anchor.put("lastHash", "bogus-anchor");
        when(repository.getSeal("ws1")).thenReturn(anchor);

        ChainVerifyResult r = service.verify("ws1");

        assertFalse(r.intact());
        assertFalse(r.anchorMatch());
        assertEquals("bogus-anchor", r.anchorHash());
        assertTrue(r.summary().contains("封印锚"));
    }

    @Test
    void verifyEmptyChainWithoutAnchorIsIntact() {
        when(repository.listSealedByChain("ws1")).thenReturn(List.of());
        when(repository.getSeal("ws1")).thenReturn(null);

        ChainVerifyResult r = service.verify("ws1");

        assertTrue(r.intact());
        assertEquals(0, r.checked());
    }

    /* ---------- fixtures ---------- */

    /** 未封印行：17 个规范化字段齐全（ts/部分业务字段留默认）。 */
    private static Map<String, Object> baseRow(String id, String workspaceId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("ts", OffsetDateTime.of(2026, 2, 11, 0, 0, 0, 0, ZoneOffset.UTC));
        row.put("workspaceId", workspaceId);
        row.put("actorType", "user");
        row.put("actorId", "u1");
        row.put("action", "audit.login");
        row.put("resourceType", null);
        row.put("resourceId", null);
        row.put("outcome", "SUCCESS");
        row.put("reason", null);
        row.put("traceId", null);
        row.put("spanId", null);
        row.put("service", "tioka-server");
        row.put("requestId", null);
        row.put("subject", null);
        row.put("clientIp", null);
        row.put("detailsJson", null);
        return row;
    }

    /**
     * 构造一条哈希正确的已封印链（prev/hash 与 canonical 重算严格一致），
     * 供 verify 正反用例使用。
     */
    private static List<Map<String, Object>> sealedChain(String workspaceId, String... ids) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String prev = "";
        for (String id : ids) {
            Map<String, Object> row = baseRow(id, workspaceId);
            row.put("prevHash", prev);
            row.put("hash", AuditChainService.sha256Hex(prev + "\n" + AuditChainService.canonical(row)));
            rows.add(row);
            prev = (String) row.get("hash");
        }
        return rows;
    }
}

package com.tioka.runtime.application.grant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.runtime.api.grant.DelegationGrant;
import com.tioka.runtime.api.grant.GrantStatus;
import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.infrastructure.GrantBody;
import com.tioka.runtime.infrastructure.mapper.RuntimeGrantMapper;
import com.tioka.runtime.infrastructure.persistence.RuntimeGrantDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.tioka.runtime.infrastructure.GrantStore;

/**
 * MAS-01 缓存与失效（M3-01 L95-96）离线验证：
 * reap 级联（WITH RECURSIVE 由 mapper 承担，此处断言抽象边界调用）→
 * 事务提交后 DEL 对应键并发布 rt:grant:revoked（GrantCacheStore 抽象，禁真实 Redis）。
 * get 走 Redis 60 秒缓存、未命中回源并回填。
 */
class GrantRevokeIT {
    GrantStore store;
    GrantCacheStore cache;
    DefaultGrantService service;

    @BeforeEach
    void setUp() {
        store = mock(GrantStore.class);
        cache = mock(GrantCacheStore.class);
        service = new DefaultGrantService(store, cache, new KernelProperties(), mapper(),
                (org.springframework.beans.factory.ObjectProvider<com.tioka.agent.api.AgentSnapshotApi>) mock(org.springframework.beans.factory.ObjectProvider.class));
    }

    /** 与 Spring Boot 全局 mapper 同口径（Instant 需要 JavaTimeModule）。 */
    static ObjectMapper mapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        m.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return m;
    }

    DelegationGrant grant(String id, String parent, GrantStatus status) {
        return new DelegationGrant(id, parent, "user:1", null,
                List.of(), Set.of("echo"), Set.of(), Set.of(),
                Map.of("kb", Set.of("kb_a")), Map.of(), null, Set.of(), null,
                Instant.now().plusSeconds(3600), status, null);
    }

    @Test
    void reapCascadesAndBroadcastsRevocation() {
        when(store.reap("grt_root", "REAPED"))
                .thenReturn(List.of("grt_root", "grt_child", "grt_grandchild"));

        service.reap("grt_root");

        ArgumentCaptor<List<String>> evicted = ArgumentCaptor.forClass(List.class);
        verify(cache, times(1)).evict(evicted.capture());
        assertEquals(List.of("grt_root", "grt_child", "grt_grandchild"), evicted.getValue());
        verify(cache, times(1)).publishRevoked(evicted.getValue());
    }

    @Test
    void reapOfDescendantSatisfiesOneSecondDenialWindowViaCacheEvict() {
        // 撤销根授予后，孙授予的调用在缓存失效广播下立即失效（不依赖 60 秒 TTL 过期）
        when(store.reap("grt_root", "REAPED")).thenReturn(List.of("grt_root", "grt_child", "grt_grandchild"));
        service.reap("grt_root");
        // 孙授予缓存被显式清除 → 下一次 get 必然回源 REAPED 行 → GRANT_INACTIVE
        verify(cache).evict(argContains("grt_grandchild"));
    }

    private static <T> List<T> argContains(String id) {
        return org.mockito.ArgumentMatchers.argThat(list -> list != null && list.contains(id));
    }

    @Test
    void getUsesCacheThenFallsBackToStore() throws Exception {
        GrantBody body = new GrantBody(grant("grt_child", "grt_parent", GrantStatus.ACTIVE), 0, 1);
        when(cache.get("grt_child")).thenReturn(null);
        when(store.findBody("grt_child")).thenReturn(body);

        DelegationGrant g = service.get("grt_child");
        assertNotNull(g);
        assertEquals("grt_child", g.grantId());
        verify(cache, atLeastOnce()).put(eq("grt_child"), anyString());

        // 第二次走缓存
        ObjectMapper mapper = mapper();
        when(cache.get("grt_child")).thenReturn(mapper.writeValueAsString(body));
        DelegationGrant cached = service.get("grt_child");
        assertEquals("grt_child", cached.grantId());
        verify(store, times(1)).findBody("grt_child");
    }

    @Test
    void narrowRequiresActiveParent() {
        GrantBody reaped = new GrantBody(grant("grt_parent", null, GrantStatus.REAPED), 0, 1);
        when(store.findBody("grt_parent")).thenReturn(reaped);
        BizException ex = assertThrows(BizException.class, () -> service.narrow(
                reaped.grant(),
                new com.tioka.runtime.api.grant.GrantAgentView("av_child", "agv_child", Set.of("echo"),
                        Set.of(), Set.of(), Map.of(), null, Set.of(), null),
                new com.tioka.runtime.api.grant.TaskNarrow(null, null, null, null)));
        assertEquals(com.tioka.framework.error.ErrorCode.GRANT_INACTIVE, ex.getErrorCode());
        verify(cache, never()).evict(any());
    }

    @Test
    void accrueReturnsBudgetToParentWithCasRetry() {
        DelegationGrant childGrant = new DelegationGrant("grt_child", "grt_parent", "user:1", null,
                List.of(), Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), null, Set.of(),
                new com.tioka.runtime.api.grant.Budget(java.math.BigDecimal.valueOf(10000), null, null),
                Instant.now().plusSeconds(3600), GrantStatus.ACTIVE, null);
        GrantBody child = new GrantBody(childGrant, 0, 1);
        DelegationGrant parentGrant = new DelegationGrant("grt_parent", null, "user:1", null,
                List.of(), Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), null, Set.of(),
                new com.tioka.runtime.api.grant.Budget(java.math.BigDecimal.valueOf(10000), null, null),
                Instant.now().plusSeconds(3600), GrantStatus.ACTIVE, null);
        GrantBody parent = new GrantBody(parentGrant, 7000, 5); // 划拨冻结：已用含 10k 拨付的 7k
        when(store.findBody("grt_child")).thenReturn(child);
        when(store.findBody("grt_parent")).thenReturn(parent);
        // 子授予 CAS 直接成功；父授予第一次冲突、重试成功
        when(store.casBody(eq("grt_child"), any(GrantBody.class), any(GrantBody.class))).thenReturn(true);
        when(store.casBody(eq("grt_parent"), any(GrantBody.class), any(GrantBody.class)))
                .thenReturn(false).thenReturn(true);

        service.accrueAndReturnToParent("grt_child", 3000);

        // 子授予用量累计 3000
        verify(store, atLeastOnce()).casBody(eq("grt_child"), any(GrantBody.class), any(GrantBody.class));
        // 父剩余 += 10k − 3k = 7k：used 7000 → 0
        ArgumentCaptor<GrantBody> updated = ArgumentCaptor.forClass(GrantBody.class);
        verify(store, atLeastOnce()).casBody(eq("grt_parent"), any(GrantBody.class), updated.capture());
        assertEquals(0, updated.getValue().usedTokens());
        verify(cache, atLeastOnce()).evict(List.of("grt_child"));
        verify(cache, atLeastOnce()).evict(List.of("grt_parent"));
    }

    @Test
    void insertPersistsGrantRow() {
        // root 签发落库（store.insert 收到 GrantBody，status ACTIVE）
        com.tioka.framework.security.Principal runAs =
                new com.tioka.framework.security.Principal("1", "ws",
                        com.tioka.framework.security.PrincipalType.SERVICE, Set.of("svc"), Set.of("*"), "api_key");
        com.tioka.runtime.api.grant.GrantAgentView view =
                new com.tioka.runtime.api.grant.GrantAgentView("av_1", "agv_1", Set.of("echo"),
                        Set.of(), Set.of(), Map.of("kb", Set.of("kb_a")), null, Set.of(), null);
        DelegationGrant root = service.root(runAs, "u_9", view, com.tioka.runtime.api.grant.MandateScope.empty());
        assertNotNull(root.grantId());
        assertTrue(root.grantId().startsWith("grt"));
        assertEquals("service:1", root.rootPrincipalRef());
        assertEquals("u_9", root.onBehalfOf());
        ArgumentCaptor<GrantBody> body = ArgumentCaptor.forClass(GrantBody.class);
        ArgumentCaptor<String> rootPrincipal = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> behalf = ArgumentCaptor.forClass(String.class);
        verify(store, times(1)).insert(body.capture(), eq(null), rootPrincipal.capture(),
                behalf.capture(), anyString(), eq(1), any(Instant.class));
        assertEquals(root.grantId(), body.getValue().grant().grantId());
        assertEquals("service:1", rootPrincipal.getValue());
        assertEquals("u_9", behalf.getValue());
        assertEquals(GrantStatus.ACTIVE, body.getValue().grant().status());
        // TRU-02：charter 为 null → 动作类别空集
        assertTrue(body.getValue().grant().actionClasses().isEmpty());
    }
}

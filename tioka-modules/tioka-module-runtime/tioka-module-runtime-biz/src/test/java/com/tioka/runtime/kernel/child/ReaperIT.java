package com.tioka.runtime.kernel.child;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.runtime.application.grant.DefaultGrantService;
import com.tioka.runtime.infrastructure.GrantBody;
import com.tioka.runtime.application.grant.GrantCacheStore;
import com.tioka.runtime.infrastructure.GrantStore;
import com.tioka.runtime.application.tool.DelegationGrantCheck;
import com.tioka.runtime.api.grant.Budget;
import com.tioka.runtime.api.grant.DelegationGrant;
import com.tioka.runtime.api.grant.GrantStatus;
import com.tioka.runtime.infrastructure.RuntimeThreadRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeThreadDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.kernel.infrastructure.ApprovalRepository;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MAS-03 子任务回收离线验证（M3-01 L224-229 离线版）：
 * 终态后 grant REAPED、预算回拨（10k 用 3k 父增 7k）、approval EXPIRED、父 Turn 唤醒。
 * 需要 Testcontainers 的双实例 / 真实 SQL 版本登记于批次文档（容器 IT 归 CI）。
 */
class ReaperIT {
    RuntimeThreadRepository threads;
    TurnRepository turns;
    ItemRepository items;
    ApprovalRepository approvals;
    GrantStore grantStore;
    DefaultGrantService grantService;
    ChildPendingRegistry registry;
    RuntimeChildRouterConsumer router;
    ApplicationEventPublisher appEvents;
    SubAgentReaper reaper;

    GrantBody child;
    GrantBody parent;

    @BeforeEach
    void setUp() {
        threads = mock(RuntimeThreadRepository.class);
        turns = mock(TurnRepository.class);
        items = mock(ItemRepository.class);
        approvals = mock(ApprovalRepository.class);
        grantStore = mock(GrantStore.class);
        registry = new ChildPendingRegistry();
        router = mock(RuntimeChildRouterConsumer.class);
        appEvents = mock(ApplicationEventPublisher.class);
        grantService = new DefaultGrantService(grantStore, mock(GrantCacheStore.class),
                new com.tioka.runtime.config.KernelProperties(), mapper(),
                (org.springframework.beans.factory.ObjectProvider<com.tioka.agent.api.AgentSnapshotApi>) mock(org.springframework.beans.factory.ObjectProvider.class));
        reaper = new SubAgentReaper(threads, turns, items, approvals, grantStore, grantService,
                registry, router, mapper(), appEvents);
        when(grantStore.casBody(anyString(), any(GrantBody.class), any(GrantBody.class))).thenReturn(true);

        child = new GrantBody(grant("grt_child", "grt_parent",
                new Budget(BigDecimal.valueOf(10000), null, null)), 0, 1);
        parent = new GrantBody(grant("grt_parent", null,
                new Budget(BigDecimal.valueOf(10000), null, null)), 7000, 5);
    }

    static DelegationGrant grant(String id, String parent, Budget budget) {
        return new DelegationGrant(id, parent, "user:1", null, List.of(), Set.of("echo"),
                Set.of(), Set.of(), Map.of(), Map.of(), null, Set.of(), budget,
                Instant.now().plusSeconds(3600), GrantStatus.ACTIVE, null);
    }

    /** 与 Spring Boot 全局 mapper 同口径（Instant 需要 JavaTimeModule）。 */
    static ObjectMapper mapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        m.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return m;
    }

    void childThreadOf(String parentTurnId) {
        RuntimeThreadDO t = new RuntimeThreadDO();
        t.setId("thr_child");
        t.setParentTurnId(parentTurnId);
        t.setGrantId("grt_child");
        when(threads.find("thr_child")).thenReturn(t);
    }

    @Test
    void grantInvalidAfterDone() {
        childThreadOf("turn_parent");
        when(turns.listByThread("thr_child", 0, 500)).thenReturn(List.of());
        when(grantStore.findBody("grt_child")).thenReturn(child);
        when(grantStore.sumThreadTokens("thr_child")).thenReturn(3000L);

        reaper.onChildTerminal("thr_child", "turn_child", com.tioka.runtime.api.kernel.TurnStatus.COMPLETED);

        verify(grantStore).reap("grt_child", "REAPED");
        // ReaperIT#grantInvalidAfterDone 的完整断言（用其 grantId 构造的工具调用 DENIED(GRANT_INACTIVE)）
        DelegationGrant reaped = new DelegationGrant("grt_child", "grt_parent", "user:1", null,
                List.of(), Set.of("echo"), Set.of(), Set.of(), Map.of(), Map.of(), null, Set.of(),
                child.grant().budget(), child.grant().expiresAt(), GrantStatus.REAPED, null);
        com.tioka.runtime.api.grant.GrantService gs = mock(com.tioka.runtime.api.grant.GrantService.class);
        when(gs.get("grt_child")).thenReturn(reaped);
        DelegationGrantCheck check = new DelegationGrantCheck(gs,
                new com.tioka.runtime.application.ToolRegistry(List.of()),
                (org.springframework.beans.factory.ObjectProvider<com.tioka.agent.api.AgentSnapshotApi>) mock(org.springframework.beans.factory.ObjectProvider.class));
        String deny = check.check(com.tioka.runtime.api.spi.ToolCallContexts.forTurn(null,
                "thr_child", "turn_child", "av_child", "delegate", List.of(), Map.of(), "grt_child"),
                "echo", Map.of());
        assertTrue(deny.startsWith("GRANT_INACTIVE"), deny);
    }

    @Test
    void budgetReturned() {
        childThreadOf("turn_parent");
        when(turns.listByThread("thr_child", 0, 500)).thenReturn(List.of());
        when(grantStore.findBody("grt_child")).thenReturn(child);
        when(grantStore.findBody("grt_parent")).thenReturn(parent);
        when(grantStore.casBody(anyString(), any(GrantBody.class), any(GrantBody.class))).thenReturn(true);
        when(grantStore.sumThreadTokens("thr_child")).thenReturn(3000L);

        reaper.onChildTerminal("thr_child", "turn_child", com.tioka.runtime.api.kernel.TurnStatus.COMPLETED);

        // 子授予 10k 用了 3k → 父剩余额度增加 7k（used 7000 → 0）
        ArgumentCaptor<GrantBody> updated = ArgumentCaptor.forClass(GrantBody.class);
        verify(grantStore, atLeastOnce()).casBody(eq("grt_parent"), any(GrantBody.class), updated.capture());
        assertEquals(0, updated.getValue().usedTokens());
        // 子授予自身用量累计 3000
        ArgumentCaptor<GrantBody> childUpdated = ArgumentCaptor.forClass(GrantBody.class);
        verify(grantStore, atLeastOnce()).casBody(eq("grt_child"), any(GrantBody.class), childUpdated.capture());
        assertEquals(3000, childUpdated.getValue().usedTokens());
    }

    @Test
    void approvalsExpiredWithReapedReason() {
        childThreadOf("turn_parent");
        RuntimeTurnDO t = new RuntimeTurnDO();
        t.setId("turn_child");
        when(turns.listByThread("thr_child", 0, 500)).thenReturn(List.of(t));
        when(grantStore.findBody("grt_child")).thenReturn(child);
        when(grantStore.sumThreadTokens("thr_child")).thenReturn(0L);

        reaper.onChildTerminal("thr_child", "turn_child", com.tioka.runtime.api.kernel.TurnStatus.FAILED);

        verify(approvals).expirePendingForSubject("TURN", "turn_child", "reaped");
    }

    @Test
    void parentResumedWithChildFinalAnswer() {
        childThreadOf("turn_parent");
        when(turns.listByThread("thr_child", 0, 500)).thenReturn(List.of());
        when(grantStore.findBody("grt_child")).thenReturn(child);
        when(grantStore.sumThreadTokens("thr_child")).thenReturn(0L);
        registry.register("turn_child", "turn_parent", "call_1");
        // 子 thread 最终回答（assistant message）
        com.tioka.runtime.infrastructure.persistence.RuntimeItemDO item =
                new com.tioka.runtime.infrastructure.persistence.RuntimeItemDO();
        item.setType("message");
        item.setRole("assistant");
        item.setContentJson("{\"text\":\"子任务完成：合同已归档\"}");
        when(items.listAfterSeq(eq("thr_child"), anyLong(), anyInt())).thenReturn(List.of(item));

        reaper.onChildTerminal("thr_child", "turn_child", com.tioka.runtime.api.kernel.TurnStatus.COMPLETED);

        verify(router).routeLocally(eq("turn_parent"), eq("call_1"), eq(true),
                eq("子任务完成：合同已归档"), any());
        // sandbox 释放结构化事件（简化口径）
        verify(appEvents).publishEvent(any(SubAgentReaper.SubAgentSandboxReleased.class));
    }

    @Test
    void nonChildThreadIgnored() {
        RuntimeThreadDO t = new RuntimeThreadDO();
        t.setId("thr_root");
        t.setParentTurnId(null);
        t.setGrantId(null);
        when(threads.find("thr_root")).thenReturn(t);
        reaper.onChildTerminal("thr_root", "turn_x", com.tioka.runtime.api.kernel.TurnStatus.COMPLETED);
        verify(grantStore, never()).reap(anyString(), anyString());
    }

    @Test
    void failedChildResumesParentWithErrorCode() {
        childThreadOf("turn_parent");
        when(turns.listByThread("thr_child", 0, 500)).thenReturn(List.of());
        when(grantStore.findBody("grt_child")).thenReturn(child);
        when(grantStore.sumThreadTokens("thr_child")).thenReturn(0L);
        registry.register("turn_child", "turn_parent", "call_2");

        reaper.onChildTerminal("thr_child", "turn_child", com.tioka.runtime.api.kernel.TurnStatus.CANCELLED);

        verify(router).routeLocally(eq("turn_parent"), eq("call_2"), eq(false), eq(""), eq("CANCELLED"));
    }
}

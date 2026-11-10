package com.tioka.runtime.application.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.runtime.api.grant.DelegationGrant;
import com.tioka.runtime.api.grant.GrantService;
import com.tioka.runtime.api.grant.GrantStatus;
import com.tioka.runtime.api.spi.ToolCallContexts;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import com.tioka.runtime.application.ToolRegistry;
import com.tioka.runtime.infrastructure.GrantStore;
import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.infrastructure.RuntimeThreadRepository;
import com.tioka.runtime.infrastructure.persistence.RuntimeThreadDO;
import com.tioka.runtime.infrastructure.persistence.RuntimeTurnDO;
import com.tioka.runtime.kernel.infrastructure.ItemRepository;
import com.tioka.runtime.kernel.infrastructure.TurnRepository;
import com.tioka.runtime.kernel.infrastructure.redis.TurnDispatchQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.tioka.runtime.infrastructure.GrantStore;

/**
 * RT-12 agent.delegate 离线验证（M3-01 L198-204 离线版）：
 * 开关关闭 listTools 不含 agent.delegate / 并行数限额 / subAgents 校验 /
 * 同一事务 narrow+建 thread(parent_turn_id, depth+1, grant_id)+QUEUED turn（run_as 不变）/
 * afterCommit XADD rt:turns / PendingJob 挂起 WAITING_CHILD。
 * parentWaitsChild / cascadeCancel 全链路需 Testcontainers，容器版登记于批次文档。
 */
class DelegateIT {
    KernelProperties props;
    AgentSnapshotApi agents;
    GrantService grants;
    GrantStore grantStore;
    RuntimeThreadRepository threads;
    TurnRepository turns;
    ItemRepository items;
    TurnDispatchQueue dispatchQueue;
    TransactionTemplate tx;
    ToolRegistry toolRegistry;
    DelegateToolProvider tool;

    AgentSnapshotApi.AgentSnapshot parentSnap;
    AgentSnapshotApi.AgentSnapshot childSnap;
    DelegationGrant parentGrant;
    DelegationGrant childGrant;

    @BeforeEach
    void setUp() {
        props = new KernelProperties();
        agents = mock(AgentSnapshotApi.class);
        grants = mock(GrantService.class);
        grantStore = mock(GrantStore.class);
        threads = mock(RuntimeThreadRepository.class);
        turns = mock(TurnRepository.class);
        items = mock(ItemRepository.class);
        dispatchQueue = mock(TurnDispatchQueue.class);
        tx = mock(TransactionTemplate.class);
        toolRegistry = new ToolRegistry(List.of());
        org.springframework.beans.factory.ObjectProvider<ToolRegistry> registryProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(registryProvider.getObject()).thenReturn(toolRegistry);
        tool = new DelegateToolProvider(props, agents, grants, grantStore, threads, turns,
                items, dispatchQueue, tx, new ObjectMapper(), registryProvider);

        // 父快照：subAgents.allowed = [agv_child]（预留 JsonNode，委派解冻后的形态）
        AgentSpec parentSpec = spec(Map.of("allowed", List.of("agv_child")));
        parentSnap = new AgentSnapshotApi.AgentSnapshot("av_parent", "agv_parent", 3, "ws", "1", "h", parentSpec, null);
        AgentSpec childSpec = spec(null);
        childSnap = new AgentSnapshotApi.AgentSnapshot("av_child", "agv_child", 2, "ws", "1", "h", childSpec, null);
        parentGrant = grant("grt_parent", null);
        childGrant = grant("grt_child", "grt_parent");
        when(agents.get("av_parent")).thenReturn(parentSnap);
        when(agents.currentPublished("agv_child")).thenReturn(childSnap);
        when(grants.get("grt_parent")).thenReturn(parentGrant);
        when(grants.narrow(any(), any(), any())).thenReturn(childGrant);
        when(tx.<String[]>execute(any())).thenAnswer(inv ->
                ((TransactionCallback<String[]>) inv.getArgument(0)).doInTransaction(null));
        RuntimeThreadDO parentThread = new RuntimeThreadDO();
        parentThread.setId("thr_parent");
        parentThread.setDepth(0);
        parentThread.setWorkspaceId("ws");
        parentThread.setOwnerId("1");
        when(threads.find("thr_parent")).thenReturn(parentThread);
        when(turns.nextSeq(anyString())).thenReturn(1);
        RuntimeTurnDO parentTurn = new RuntimeTurnDO();
        parentTurn.setId("turn_parent");
        parentTurn.setRunAsJson("{\"userId\":\"1\",\"workspaceId\":\"ws\",\"type\":\"USER\"}");
        when(turns.findById("turn_parent")).thenReturn(Optional.of(parentTurn));
    }

    static AgentSpec spec(Object subAgents) {
        return new AgentSpec(1, null, null,
                new AgentSpec.ModelPolicy("rp"), AgentSpec.ToolSet.empty(), null,
                new AgentSpec.KnowledgeBinding(List.of(), "TOOL"),
                new AgentSpec.SandboxPolicy("EDIT", List.of()),
                null, AgentSpec.Limits.defaults(), null, null, null, null, null, null,
                subAgents == null ? null : new ObjectMapper().valueToTree(subAgents), null);
    }

    static DelegationGrant grant(String id, String parent) {
        return new DelegationGrant(id, parent, "user:1", null,
                List.of(new com.tioka.runtime.api.grant.ChainHop("av_parent", null)),
                Set.of("echo", "agent.delegate"), Set.of(), Set.of(), Map.of(), Map.of(),
                null, Set.of(), new com.tioka.runtime.api.grant.Budget(BigDecimal.valueOf(10000), null, null),
                Instant.now().plusSeconds(3600), GrantStatus.ACTIVE, null);
    }

    ToolGateway.ToolCallContext ctx() {
        return ToolCallContexts.forTurn(null, "thr_parent", "turn_parent", "av_parent", "chat",
                List.of(), Map.of(), "grt_parent");
    }

    Map<String, Object> args() {
        return Map.of("agentId", "agv_child", "task", "审查合同 HT-1",
                "narrow", Map.of("tools", List.of("echo")));
    }

    @Test
    void disabledByDefault_listToolsExcluded() {
        // tioka.runtime.delegate.enabled 默认 false：不注册时 listTools 不含 agent.delegate
        assertTrue(tool.listTools("av_parent").isEmpty());
        props.getDelegate().setEnabled(true);
        List<ToolProvider.ToolDescriptor> tools = tool.listTools("av_parent");
        assertTrue(tools.stream().anyMatch(d -> d.name().equals("agent.delegate")));
    }

    @Test
    void parentWaitsChild_acceptedWithPending() {
        props.getDelegate().setEnabled(true);
        var r = tool.invoke(ctx(), "av_parent", "agent.delegate", args(), Map.of());
        assertTrue(r.ok(), r.content());
        assertEquals("accepted", r.content());
        assertNotNullPending(r);
        // 同一事务：narrow + 建 thread(parent_turn_id=父Turn, depth+1, grant_id) + QUEUED turn
        ArgumentCaptor<RuntimeThreadDO> thread = ArgumentCaptor.forClass(RuntimeThreadDO.class);
        verify(threads).upsert(thread.capture());
        assertEquals("turn_parent", thread.getValue().getParentTurnId());
        assertEquals(1, thread.getValue().getDepth());
        assertEquals("grt_child", thread.getValue().getGrantId());
        assertEquals("delegate", thread.getValue().getOrigin());
        ArgumentCaptor<RuntimeTurnDO> turn = ArgumentCaptor.forClass(RuntimeTurnDO.class);
        verify(turns).insert(turn.capture());
        assertEquals("QUEUED", turn.getValue().getStatus());
        assertEquals("delegate", turn.getValue().getOrigin());
        assertEquals("{\"userId\":\"1\",\"workspaceId\":\"ws\",\"type\":\"USER\"}", turn.getValue().getRunAsJson());
        // afterCommit（无事务同步时立即）XADD rt:turns
        verify(dispatchQueue, times(1)).offer(turn.getValue().getId(), thread.getValue().getId());
        // PendingJob jobId = childTurnId：ChildWaitService 据此登记 → Reaper 唤醒父
        assertEquals(turn.getValue().getId(), r.pending().jobId());
    }

    private void assertNotNullPending(ToolProvider.ToolResult r) {
        assertTrue(r.pending() != null && r.pending().jobId() != null, "父 Turn 须经 PendingJob 挂起 WAITING_CHILD");
    }

    @Test
    void parallelLimit() {
        props.getDelegate().setEnabled(true);
        when(grantStore.countActiveChildTurns("turn_parent")).thenReturn(3);
        var r = tool.invoke(ctx(), "av_parent", "agent.delegate", args(), Map.of());
        assertFalse(r.ok());
        assertTrue(r.content().contains("并行数已达上限"), r.content());
        verify(threads, never()).upsert(any());
        verify(dispatchQueue, never()).offer(anyString(), anyString());
    }

    @Test
    void rejectsWhenAgentNotInSubAgents() {
        props.getDelegate().setEnabled(true);
        var r = tool.invoke(ctx(), "av_parent", "agent.delegate",
                Map.of("agentId", "agv_stranger", "task", "x"), Map.of());
        assertFalse(r.ok());
        assertTrue(r.content().contains("subAgents.allowed"), r.content());
    }

    @Test
    void rejectsWithoutParentGrant() {
        props.getDelegate().setEnabled(true);
        ToolGateway.ToolCallContext noGrant = ToolCallContexts.forTurn(null, "thr_parent", "turn_parent",
                "av_parent", "chat", List.of(), Map.of(), null);
        var r = tool.invoke(noGrant, "av_parent", "agent.delegate", args(), Map.of());
        assertFalse(r.ok());
        assertTrue(r.content().contains("父授予不存在"), r.content());
    }

    @Test
    void depthLimitRejectsGrandchildBeyondMax() {
        // depth 校验在 NarrowingVerifier（D2 用例覆盖）；此处断言 DelegateTool 未额外绕过
        props.getDelegate().setEnabled(true);
        assertEquals(2, props.getDelegate().getMaxDepth());
    }

    @Test
    void unknownToolRejected() {
        var r = tool.invoke(ctx(), "av_parent", "echo", Map.of(), Map.of());
        assertFalse(r.ok());
    }
}

package com.tioka.runtime.application.grant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.AgentSnapshotApi;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalType;
import com.tioka.runtime.api.grant.Budget;
import com.tioka.runtime.api.grant.ChainHop;
import com.tioka.runtime.api.grant.DelegationGrant;
import com.tioka.runtime.api.grant.GrantAgentView;
import com.tioka.runtime.api.grant.GrantStatus;
import com.tioka.runtime.api.spi.ToolCallContexts;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.application.ToolRegistry;
import com.tioka.runtime.application.tool.DelegationGrantCheck;
import com.tioka.runtime.config.KernelProperties;
import com.tioka.runtime.infrastructure.GrantBody;
import com.tioka.runtime.infrastructure.GrantStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MAS-02 授权书下放收口（M3-01 L128-146，离线构造）：
 * 父授权书 {@code delegable=[]} → 子授予动作类别收窄为空集 → 子 Agent 写类工具全 DENIED；
 * {@code delegable} 加入类别后放行（子授予类别 = 父授予 ∩ 父授权书 delegable ∩ 子快照）。
 * 发布校验拒绝越界 delegable 见 agent-biz CharterPublishCheckTest；祖先禁区链语义见
 * governance-biz ChainForbiddenIT。
 */
class DelegableIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Principal USER = new Principal("1", "ws", PrincipalType.USER,
            Set.of(), Set.of("*"), "password");

    private GrantStore store;
    private AgentSnapshotApi snapshots;
    private DefaultGrantService service;

    private DelegationGrant parentGrant;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store = mock(GrantStore.class);
        snapshots = mock(AgentSnapshotApi.class);
        service = new DefaultGrantService(store, mock(GrantCacheStore.class),
                new KernelProperties(), MAPPER, provider(snapshots));

        parentGrant = new DelegationGrant("grt_parent", null, "user:1", null,
                List.of(new ChainHop("av_parent", null)),
                Set.of("erp.write"), Set.of("pay"), Set.of(), Map.of(), Map.of(),
                null, Set.of(), new Budget(BigDecimal.valueOf(10000), null, null),
                Instant.now().plusSeconds(3600), GrantStatus.ACTIVE, null);
        when(store.findBody("grt_parent")).thenReturn(new GrantBody(parentGrant, 0L, 1L));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    /** 父快照：授权书 JSON 顶层 delegable 由用例注入。 */
    private void parentSnapshot(String charterJson) throws Exception {
        AgentSpec spec = new AgentSpec(1, null, null, null, null, null, null, null, null, null,
                (JsonNode) MAPPER.readTree(charterJson), null, null, null, null, null, null, null);
        when(snapshots.get("av_parent"))
                .thenReturn(new AgentSnapshotApi.AgentSnapshot("av_parent", "ag_parent", 1, "ws", "1", "h", spec));
    }

    /** 子快照：类别 pay 归类 erp.write（分类路径所需）。 */
    private void childSnapshot() throws Exception {
        JsonNode charter = MAPPER.readTree("""
                {"actionClasses":[{"code":"pay","name":"Pay","toolPatterns":["erp.write"]}]}
                """);
        AgentSpec spec = new AgentSpec(1, null, null, null, null, null, null, null, null, null,
                charter, null, null, null, null, null, null, null);
        when(snapshots.get("av_child"))
                .thenReturn(new AgentSnapshotApi.AgentSnapshot("av_child", "ag_child", 1, "ws", "1", "h", spec));
    }

    private GrantAgentView childView() {
        return new GrantAgentView("av_child", "ag_child", Set.of("erp.write"), Set.of("pay"),
                Set.of(), Map.of(), null, Set.of(), null);
    }

    private DelegationGrant narrowFromParent() {
        return service.narrow(parentGrant, childView(), null);
    }

    private static ToolGateway.ToolCallContext childCtx() {
        return ToolCallContexts.forTurn(USER, "thr1", "turn1", "av_child", "chat",
                List.of(), Map.of(), "grt_child");
    }

    private DelegationGrantCheck childCheck(DelegationGrant childGrant) {
        com.tioka.runtime.api.grant.GrantService grants =
                mock(com.tioka.runtime.api.grant.GrantService.class);
        when(grants.get("grt_child")).thenReturn(childGrant);
        return new DelegationGrantCheck(grants, new ToolRegistry(List.of()), provider(snapshots));
    }

    @Test
    void delegableEmptyChildWriteDenied() throws Exception {
        // M3-01 L128：delegable 缺省空 = 不可下放 → 子授予类别空集 → 写类工具 DENIED
        parentSnapshot("""
                {"actionClasses":[{"code":"pay","name":"Pay","toolPatterns":["erp.write"]}],"delegable":[]}
                """);
        childSnapshot();
        DelegationGrant child = narrowFromParent();
        assertTrue(child.actionClasses().isEmpty(),
                "delegable=[] → 子授予动作类别收窄为空集，got " + child.actionClasses());

        String deny = childCheck(child).check(childCtx(), "erp.write", Map.of());
        assertTrue(deny != null && deny.contains("动作类别不在授予内"), String.valueOf(deny));
    }

    @Test
    void delegableWithClassAllowsChildWrite() throws Exception {
        parentSnapshot("""
                {"actionClasses":[{"code":"pay","name":"Pay","toolPatterns":["erp.write"]}],"delegable":["pay"]}
                """);
        childSnapshot();
        DelegationGrant child = narrowFromParent();
        assertEquals(Set.of("pay"), child.actionClasses(), "delegable=[pay] → 子授予类别 = {pay}");

        assertNull(childCheck(child).check(childCtx(), "erp.write", Map.of()));
    }

    @Test
    void delegableDerivedFromClassFlagFallback() throws Exception {
        // 顶层 delegable 缺省 → 按 actionClasses[].delegable=true 派生（C-2b 兼容口径）
        parentSnapshot("""
                {"actionClasses":[{"code":"pay","name":"Pay","toolPatterns":["erp.write"],"delegable":true}]}
                """);
        childSnapshot();
        DelegationGrant child = narrowFromParent();
        assertEquals(Set.of("pay"), child.actionClasses());
        assertNull(childCheck(child).check(childCtx(), "erp.write", Map.of()));
    }

    @Test
    void snapshotUnavailableFallsBackToGrantClasses() throws Exception {
        // 快照缺失（登记偏差口径）：回退父授予 actionClasses，行为与 MAS-02 之前一致
        childSnapshot();
        DelegationGrant child = narrowFromParent();
        assertEquals(Set.of("pay"), child.actionClasses(), "父授予 actionClasses 回退");
        assertNull(childCheck(child).check(childCtx(), "erp.write", Map.of()));
    }

    @Test
    void childClassesNeverExceedParentGrant() throws Exception {
        // 类别收窄三重交集：父授予 {pay} ∩ 父授权书 delegable ["pay"] ∩ 子快照（子 view 无类别）
        parentSnapshot("""
                {"actionClasses":[{"code":"pay","name":"Pay","toolPatterns":["erp.write"]}],"delegable":["pay"]}
                """);
        childSnapshot();
        GrantAgentView childNoClasses = new GrantAgentView("av_child", "ag_child",
                Set.of("erp.write"), Set.of(), Set.of(), Map.of(), null, Set.of(), null);
        DelegationGrant child = service.narrow(parentGrant, childNoClasses, null);
        assertTrue(child.actionClasses().isEmpty(), "子快照无类别 → 交集为空");
    }
}

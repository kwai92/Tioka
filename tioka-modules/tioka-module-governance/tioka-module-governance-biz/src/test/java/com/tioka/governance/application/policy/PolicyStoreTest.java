package com.tioka.governance.application.policy;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.governance.api.policy.PolicyAdminApi;
import com.tioka.governance.api.policy.PolicyDecision;
import com.tioka.governance.api.policy.PolicyMode;
import com.tioka.governance.infrastructure.persistence.GovPolicyDO;
import com.tioka.governance.infrastructure.policy.GovPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GOV-07 策略存储离线测试（M3-05 §1.1/§1.3）：permit 一律拒绝（策略只能收紧）、
 * @id/@effect 词法解析、charter 整组替换幂等、版本号递增。
 */
class PolicyStoreTest {

    private GovPolicyRepository repo;
    private PolicyStore store;

    /** 典型编译产物同款 forbid 文本。 */
    private static final String FORBID_ESCALATE = """
            @id("charter:av1:pay:escalate-amount")
            @effect("ESCALATE")
            forbid(principal, action in Tioka::Action::"pay", resource)
            when { context.agentVersion == "av1" && resource.amountMicros > 10000 };
            """;

    @BeforeEach
    void setUp() {
        repo = mock(GovPolicyRepository.class);
        store = new PolicyStore(repo, null);
    }

    @Test
    void importPolicyRejectsPermit() {
        BizException e = assertThrows(BizException.class, () -> store.importPolicy(
                new PolicyAdminApi.ImportCommand("ws1", "permit(principal, action, resource);", null, "u1")));
        assertEquals(ErrorCode.BAD_REQUEST, e.getErrorCode());
        // 注解在前、permit 语句在后续行同样拒绝
        assertThrows(BizException.class, () -> store.importPolicy(
                new PolicyAdminApi.ImportCommand("ws1", "@id(\"x\")\npermit(principal, action, resource);", null, "u1")));
        verify(repo, never()).insert(any(GovPolicyDO.class));
    }

    @Test
    void importPolicyRejectsBlankText() {
        assertThrows(BizException.class, () -> store.importPolicy(
                new PolicyAdminApi.ImportCommand("ws1", "  ", null, "u1")));
    }

    @Test
    void importPolicyParsesAnnotationsAndDefaults() {
        when(repo.maxVersion("ws1", PolicyStore.SOURCE_IMPORT)).thenReturn(3);
        GovPolicyDO row = store.importPolicy(new PolicyAdminApi.ImportCommand(
                "ws1", FORBID_ESCALATE, null, "u1"));
        assertEquals("charter:av1:pay:escalate-amount", row.getPolicyId());
        assertEquals(PolicyDecision.ESCALATE.name(), row.getEffect());
        assertEquals(PolicyMode.DRYRUN.name(), row.getMode(), "mode 未指定取导入默认（DRYRUN）");
        assertEquals(4, row.getVersion());
        assertEquals(PolicyStore.SOURCE_IMPORT, row.getSource());
        assertEquals(PolicyStore.STATUS_ACTIVE, row.getStatus());
        assertEquals("u1", row.getCreatedBy());
        verify(repo).insert(row);
    }

    @Test
    void importPolicyExplicitModeAndDefaultActor() {
        when(repo.maxVersion("ws1", PolicyStore.SOURCE_IMPORT)).thenReturn(0);
        GovPolicyDO row = store.importPolicy(new PolicyAdminApi.ImportCommand(
                "ws1", FORBID_ESCALATE, PolicyMode.ENFORCE, " "));
        assertEquals(PolicyMode.ENFORCE.name(), row.getMode());
        assertEquals("system", row.getCreatedBy());
        assertEquals(1, row.getVersion());
    }

    @Test
    void importPolicyWithoutAnnotationsDerivesDefaults() {
        when(repo.maxVersion("ws1", PolicyStore.SOURCE_IMPORT)).thenReturn(0);
        String text = "forbid(principal, action in Tioka::Action::\"pay\", resource);";
        GovPolicyDO row = store.importPolicy(new PolicyAdminApi.ImportCommand("ws1", text, null, "u1"));
        assertEquals(PolicyDecision.DENY.name(), row.getEffect(), "无 @effect 注解按 DENY");
        assertTrue(row.getPolicyId().startsWith("import:"), row.getPolicyId());
    }

    @Test
    void importPolicyInvalidEffectAnnotationFallsBackToDeny() {
        when(repo.maxVersion("ws1", PolicyStore.SOURCE_IMPORT)).thenReturn(0);
        String text = "@effect(\"BOGUS\")\nforbid(principal, action, resource);";
        GovPolicyDO row = store.importPolicy(new PolicyAdminApi.ImportCommand("ws1", text, null, "u1"));
        assertEquals(PolicyDecision.DENY.name(), row.getEffect());
    }

    @Test
    void replaceCharterPoliciesWritesNewVersionEnforce() {
        when(repo.maxVersion("ws1", "charter:av1")).thenReturn(2);
        List<PolicyStore.CedarPolicySpec> specs = List.of(
                new PolicyStore.CedarPolicySpec("charter:av1:pay:escalate-amount",
                        FORBID_ESCALATE, PolicyDecision.ESCALATE));
        int version = store.replaceCharterPolicies("ws1", "av1", specs, "u1");
        assertEquals(3, version);
        verify(repo).disableBySource("ws1", "charter:av1");
        ArgumentCaptor<GovPolicyDO> rows = ArgumentCaptor.forClass(GovPolicyDO.class);
        verify(repo).insert(rows.capture());
        assertEquals(3, rows.getValue().getVersion());
        assertEquals(PolicyMode.ENFORCE.name(), rows.getValue().getMode(), "编译产物一律 ENFORCE");
        assertEquals(PolicyDecision.ESCALATE.name(), rows.getValue().getEffect());
        assertEquals("charter:av1", rows.getValue().getSource());
        assertEquals(PolicyStore.STATUS_ACTIVE, rows.getValue().getStatus());
    }

    @Test
    void replaceCharterPoliciesRejectsPermitBeforeDisabling() {
        List<PolicyStore.CedarPolicySpec> specs = List.of(
                new PolicyStore.CedarPolicySpec("p1", "permit(principal, action, resource);", PolicyDecision.DENY));
        assertThrows(BizException.class, () -> store.replaceCharterPolicies("ws1", "av1", specs, "u1"));
        verify(repo, never()).disableBySource(anyString(), anyString());
        verify(repo, never()).insert(any(GovPolicyDO.class));
    }

    @Test
    void replaceCharterPoliciesEmptySpecsStillDisables() {
        // classes 为 null（版本回退语义）：清空旧策略组
        int version = store.replaceCharterPolicies("ws1", "av1", List.of(), "u1");
        assertEquals(1, version);
        verify(repo).disableBySource("ws1", "charter:av1");
        verify(repo, never()).insert(any(GovPolicyDO.class));
    }

    @Test
    void setModeNotFoundThrows() {
        when(repo.updateMode("pol_x", PolicyMode.ENFORCE.name())).thenReturn(0);
        BizException e = assertThrows(BizException.class, () -> store.setMode("pol_x", PolicyMode.ENFORCE));
        assertEquals(ErrorCode.NOT_FOUND, e.getErrorCode());
    }

    @Test
    void disableNotFoundThrows() {
        when(repo.disable("pol_x")).thenReturn(0);
        BizException e = assertThrows(BizException.class, () -> store.disable("pol_x"));
        assertEquals(ErrorCode.NOT_FOUND, e.getErrorCode());
    }

    @Test
    void setModeReturnsUpdatedRow() {
        when(repo.updateMode("pol_1", PolicyMode.ENFORCE.name())).thenReturn(1);
        GovPolicyDO row = new GovPolicyDO();
        row.setId("pol_1");
        row.setWorkspaceId("ws1");
        row.setVersion(1);
        row.setSource(PolicyStore.SOURCE_IMPORT);
        row.setPolicyId("p1");
        row.setCedarText(FORBID_ESCALATE);
        row.setMode(PolicyMode.ENFORCE.name());
        row.setEffect(PolicyDecision.ESCALATE.name());
        row.setStatus(PolicyStore.STATUS_ACTIVE);
        when(repo.findById("pol_1")).thenReturn(row);
        GovPolicyDO updated = store.setMode("pol_1", PolicyMode.ENFORCE);
        assertEquals(PolicyMode.ENFORCE.name(), updated.getMode());
        assertEquals(PolicyDecision.ESCALATE.name(), updated.getEffect());
        PolicyAdminApi.PolicyView view = store.detail("pol_1");
        assertEquals(PolicyMode.ENFORCE, view.mode());
        assertEquals(PolicyDecision.ESCALATE, view.effect());
    }

    @Test
    void currentSetVersionTakesMaxAcrossSources() {
        GovPolicyDO a = new GovPolicyDO();
        a.setVersion(2);
        GovPolicyDO b = new GovPolicyDO();
        b.setVersion(5);
        when(repo.listActiveForWorkspace("ws1")).thenReturn(List.of(a, b));
        assertEquals(5, store.currentSetVersion("ws1"));
    }

    @Test
    void listWithoutSourceReturnsAllWorkspaceScopedRows() {
        GovPolicyDO scoped = new GovPolicyDO();
        scoped.setWorkspaceId("ws1");
        scoped.setVersion(1);
        scoped.setSource(PolicyStore.SOURCE_IMPORT);
        scoped.setPolicyId("p1");
        scoped.setCedarText(FORBID_ESCALATE);
        scoped.setMode(PolicyMode.DRYRUN.name());
        scoped.setEffect(PolicyDecision.DENY.name());
        scoped.setStatus(PolicyStore.STATUS_ACTIVE);
        GovPolicyDO systemLevel = new GovPolicyDO();
        systemLevel.setWorkspaceId(null);
        when(repo.listActiveForWorkspace("ws1")).thenReturn(List.of(scoped, systemLevel));
        List<PolicyAdminApi.PolicyView> views = store.list("ws1", null);
        assertEquals(1, views.size(), "系统级（workspace_id NULL）不对空间列表可见");
        assertEquals("p1", views.get(0).policyId());
    }

    @Test
    void effectOfAndPolicyIdOfLexicalParsing() {
        assertEquals(PolicyDecision.ESCALATE, PolicyStore.effectOf("@effect(\"ESCALATE\")\nforbid(p, a, r);"));
        assertEquals(PolicyDecision.DENY, PolicyStore.effectOf("forbid(p, a, r);"));
        assertEquals(PolicyDecision.DENY, PolicyStore.effectOf("@effect(\"NOPE\")\nforbid(p, a, r);"));
        assertEquals("pid1", PolicyStore.policyIdOf("@id(\"pid1\")\nforbid(p, a, r);"));
        assertTrue(PolicyStore.policyIdOf("forbid(p, a, r);").startsWith("import:"));
    }
}

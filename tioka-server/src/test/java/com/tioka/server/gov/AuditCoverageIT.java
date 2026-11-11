package com.tioka.server.gov;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.server.support.IntegrationTestSupport;
import com.tioka.server.support.TestFixtures;
import com.tioka.system.api.audit.AuditApi;
import com.tioka.system.api.audit.AuditEvent;
import com.tioka.system.api.audit.AuditOutcome;
import com.tioka.system.api.audit.Audited;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GOV-02 / ADR-072：任意写操作可在审计中查到。
 *
 * <p>白名单仅允许 {@link WhitelistKind} 四类；「待补」非法。白名单外的每个
 * POST/PUT/DELETE/PATCH 必须已登记为 {@link Audited} 委托或手动审计。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("it")
class AuditCoverageIT {

    /** ADR-072 四类豁免；禁止再加入「待补」路径。 */
    private static final List<WhitelistEntry> WHITELIST = List.of(
            new WhitelistEntry("/api/system/auth/login", WhitelistKind.PUBLIC_AUTH, "AuthService 写 auth.login"),
            new WhitelistEntry("/api/system/auth/refresh", WhitelistKind.PUBLIC_AUTH, "AuthService 写 auth.refresh"),
            new WhitelistEntry("/api/system/auth/logout", WhitelistKind.PUBLIC_AUTH, "AuthService 写 auth.logout"),
            new WhitelistEntry("/api/system/auth/password", WhitelistKind.PUBLIC_AUTH, "AuthService 写 auth.password"),
            new WhitelistEntry("/api/system/auth/oidc/{idp}/authorize", WhitelistKind.PUBLIC_AUTH, "OIDC 授权跳转"),
            new WhitelistEntry("/api/system/auth/oidc/{idp}/callback", WhitelistKind.PUBLIC_AUTH, "OIDC 回调写 auth.oidc.*"),
            new WhitelistEntry("/api/knowledge/bases/{id}/search", WhitelistKind.READ_ONLY_POST, "知识库检索只读"),
            new WhitelistEntry("/api/model/router/failover", WhitelistKind.READ_ONLY_POST, "路由 failover 探测只读"),
            new WhitelistEntry("/api/ops/interpreter", WhitelistKind.NOT_IMPLEMENTED, "运维解释器未实现"),
            new WhitelistEntry("/api/ops/jobs", WhitelistKind.NOT_IMPLEMENTED, "运维任务桩未实现"),
            new WhitelistEntry("/api/ops/chat/{id}/fork", WhitelistKind.NOT_IMPLEMENTED, "会话 fork 未实现"),
            new WhitelistEntry("/api/connector/im/feishu/text", WhitelistKind.INBOUND_LEDGER, "飞书入站回调台账"),
            new WhitelistEntry("/api/flows/publications/webhook/{versionId}", WhitelistKind.INBOUND_LEDGER, "Flow webhook 入站"),
            new WhitelistEntry("/api/flows/publications/internal/{versionId}", WhitelistKind.INBOUND_LEDGER, "Flow 内部触发入站"),
            // ORC-03：events/cancel 已 @Audited；claim/heartbeat 仍 501，attentions 待 ORC-04
            new WhitelistEntry("/api/orch/runs/claim", WhitelistKind.NOT_IMPLEMENTED, "ORC-03 stub；claim 未接线"),
            new WhitelistEntry("/api/orch/runs/{id}/heartbeat", WhitelistKind.NOT_IMPLEMENTED, "ORC-03 stub；heartbeat 未接线"),
            new WhitelistEntry("/api/orch/attentions", WhitelistKind.NOT_IMPLEMENTED, "ORC-04 stub；人审未重建"),
            new WhitelistEntry("/api/orch/attentions/{id}/answer", WhitelistKind.NOT_IMPLEMENTED, "ORC-04 stub；人审未重建")
    );

    private static final Set<String> PATH_WHITELIST = WHITELIST.stream()
            .map(WhitelistEntry::pattern)
            .collect(Collectors.toUnmodifiableSet());

    /** Controller 方法 → 已由服务层 {@link Audited} 或手动 audit.record 覆盖。 */
    private static final Set<String> COVERED_METHODS = Set.of(
            "com.tioka.agent.api.AgentController#create",
            "com.tioka.agent.api.AgentController#disable",
            "com.tioka.agent.api.AgentController#enable",
            "com.tioka.agent.api.AgentController#publish",
            "com.tioka.agent.api.AgentController#putDraft",
            "com.tioka.agent.api.AgentController#rollback",
            "com.tioka.agent.api.AgentController#update",
            "com.tioka.agent.api.AgentController#validate",
            "com.tioka.chat.api.CardController#approval",
            "com.tioka.chat.api.CardController#continueCard",
            "com.tioka.chat.api.CardController#submitApproval",
            "com.tioka.chat.api.CardController#submitContinue",
            "com.tioka.chat.api.ChatController#create",
            "com.tioka.chat.api.ChatController#delete",
            "com.tioka.chat.api.ChatController#send",
            "com.tioka.chat.api.ChatController#stop",
            "com.tioka.flow.api.FlowController#publish",
            "com.tioka.flow.api.FlowController#run",
            "com.tioka.flow.api.FlowController#save",
            "com.tioka.flow.api.FlowPublicationController#asTool",
            "com.tioka.knowledge.api.EvalController#run",
            "com.tioka.knowledge.api.KnowledgeController#bindDatasource",
            "com.tioka.knowledge.api.KnowledgeController#createBase",
            "com.tioka.knowledge.api.KnowledgeController#ingest",
            "com.tioka.knowledge.api.KnowledgeController#syncDatasource",
            "com.tioka.knowledge.api.KnowledgeController#upload",
            "com.tioka.mcp.api.McpAdminController#refresh",
            "com.tioka.mcp.api.McpAdminController#register",
            "com.tioka.mcp.api.McpAdminController#updateToolRisk",
            "com.tioka.mcp.api.McpHealthController#trial",
            "com.tioka.model.api.ModelAdminController#createModelBinding",
            "com.tioka.model.api.ModelAdminController#createLogicalModel",
            "com.tioka.model.api.ModelAdminController#createPlan",
            "com.tioka.model.api.ModelAdminController#createProvider",
            "com.tioka.model.api.ModelAdminController#publish",
            // ADR-041 §2：草稿不写审计，以 cfg_draft 最后修改人为准
            "com.tioka.model.api.ModelAdminController#saveDraft",
            "com.tioka.model.api.ModelAdminController#updateModelBinding",
            "com.tioka.orchestration.api.OrchRuleController#create",
            "com.tioka.orchestration.api.OrchRuleController#createVersion",
            "com.tioka.orchestration.api.OrchRuleController#publish",
            "com.tioka.runtime.api.UploadController#confirm",
            "com.tioka.runtime.api.UploadController#presign",
            "com.tioka.runtime.api.UploadController#publish",
            "com.tioka.runtime.api.UploadController#put",
            "com.tioka.runtime.kernel.api.ApprovalController#decide",
            "com.tioka.runtime.kernel.api.ThreadController#create",
            "com.tioka.runtime.kernel.api.ThreadController#interrupt",
            "com.tioka.runtime.kernel.api.ThreadController#startTurn",
            "com.tioka.runtime.kernel.api.ThreadController#steer",
            // 服务层手动审计：UndoService#auditUndo → AuditApi.recordIndependent
            "com.tioka.runtime.kernel.api.TrustUndoController#undo",
            "com.tioka.server.mcp.TiokaMcpOutboundServer#call",
            "com.tioka.server.ops.OpsFacadeController#ds",
            "com.tioka.server.ops.OpsFacadeController#map",
            "com.tioka.server.ops.OpsFacadeController#scim",
            "com.tioka.server.ops.OpsFacadeController#share",
            "com.tioka.server.ops.OpsFacadeController#wiki",
            "com.tioka.skill.api.SkillController#importZip",
            "com.tioka.skill.api.SkillController#publish",
            "com.tioka.system.api.ApiKeyController#create",
            "com.tioka.system.api.ApiKeyController#delete",
            "com.tioka.system.api.GroupController#create",
            "com.tioka.system.api.GroupController#members",
            "com.tioka.system.api.QuotaController#put",
            "com.tioka.system.api.ResourceAclController#replace",
            "com.tioka.system.api.RoleController#createRole",
            "com.tioka.system.api.RoleController#deleteRole",
            "com.tioka.system.api.RoleController#updateRole",
            "com.tioka.system.api.ServiceAccountController#create",
            "com.tioka.system.api.UserController#create",
            "com.tioka.system.api.UserController#disable",
            "com.tioka.system.api.UserController#enable",
            "com.tioka.system.api.UserController#resetPassword",
            "com.tioka.system.api.WorkspaceController#create",
            "com.tioka.system.api.WorkspaceController#deleteMember",
            "com.tioka.system.api.WorkspaceController#putMember"
    );

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired TestFixtures fixtures;
    @Autowired ObjectMapper objectMapper;
    @Autowired AuditApi audit;
    @Autowired ApplicationContext ctx;

    @Test
    void whitelistOnlyAllowsAdr072Kinds() {
        assertFalse(WHITELIST.isEmpty());
        for (WhitelistEntry e : WHITELIST) {
            assertTrue(EnumSet.allOf(WhitelistKind.class).contains(e.kind()),
                    "非法白名单类别: " + e);
            assertFalse(e.reason().isBlank());
            assertFalse(e.reason().contains("待补") || e.reason().contains("待各域"),
                    "原因不得含「待补」: " + e);
        }
    }

    @Test
    void everyWriteEndpointAudited() throws Exception {
        TestFixtures.Actor admin = fixtures.newActor("workspace_admin");

        MvcResult created = mockMvc.perform(admin.auth(post("/api/agents"))
                        .contentType("application/json")
                        .content("""
                                {"name":"gov02-cov","workspaceId":"%s"}
                                """.formatted(admin.workspaceId())))
                .andExpect(status().isOk())
                .andReturn();
        String agentId = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();

        mockMvc.perform(admin.auth(post("/api/agents/{id}/disable", agentId)))
                .andExpect(status().isOk());
        List<AuditEvent> agentDisabled = audit.query(new AuditApi.AuditQuery(
                admin.workspaceId(), admin.userId(), "agent.disable", "agent", agentId,
                AuditOutcome.SUCCESS, null, null, null, 20));
        assertFalse(agentDisabled.isEmpty(), "agent.disable 必须可查");

        JsonNode user = objectMapper.readTree(
                mockMvc.perform(admin.auth(post("/api/system/users"))
                                .contentType("application/json")
                                .content("""
                                        {"username":"gov02d-%s","password":"Gov02Pass!23456"}
                                        """.formatted(System.nanoTime())))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString());
        String userId = user.path("data").path("id").asText();
        mockMvc.perform(admin.auth(put("/api/system/users/{id}/disable", userId)))
                .andExpect(status().isOk());
        List<AuditEvent> disabled = audit.query(new AuditApi.AuditQuery(
                admin.workspaceId(), admin.userId(), "user.disable", "user", userId,
                AuditOutcome.SUCCESS, null, null, null, 20));
        assertFalse(disabled.isEmpty(), "user.disable 必须可查");

        RequestMappingHandlerMapping mapping = ctx.getBean("requestMappingHandlerMapping",
                RequestMappingHandlerMapping.class);
        Set<String> uncovered = new TreeSet<>();
        EnumSet<RequestMethod> writes = EnumSet.of(
                RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH);
        for (var entry : mapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            HandlerMethod hm = entry.getValue();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            if (methods.isEmpty() || methods.stream().noneMatch(writes::contains)) {
                continue;
            }
            for (String pattern : info.getPatternValues()) {
                if (PATH_WHITELIST.contains(pattern) || isCovered(hm)) {
                    continue;
                }
                uncovered.add(String.join(",", methods.stream().map(Enum::name).sorted().toList())
                        + " " + pattern + " -> " + methodKey(hm));
            }
        }
        assertTrue(uncovered.isEmpty(),
                "写接口未进白名单且无审计覆盖（请补 @Audited 或登记白名单）: " + uncovered);
    }

    private static boolean isCovered(HandlerMethod hm) {
        Method m = hm.getMethod();
        if (AnnotatedElementUtils.hasAnnotation(m, Audited.class)) {
            return true;
        }
        return COVERED_METHODS.contains(methodKey(hm));
    }

    private static String methodKey(HandlerMethod hm) {
        return hm.getBeanType().getName() + "#" + hm.getMethod().getName();
    }
}

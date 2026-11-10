package com.tioka.agent.application.publish;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.agent.api.publish.BuiltinToolCatalog;
import com.tioka.agent.api.publish.PublishCheck;
import com.tioka.agent.api.spec.AgentSpec;
import com.tioka.framework.security.AccessLevel;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.system.api.authz.ResourceAclApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharterPublishCheckTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void disabledSkipsEvenWhenCharterSet() {
        CharterPublishCheck check = newCheck(false, builder(), acl(false), name -> false);
        ObjectNode charter = mapper.createObjectNode().put("actingAs", "DELEGATED");
        assertTrue(check.check(spec(charter, List.of("mcp_echo")), ctx()).isEmpty());
    }

    @Test
    void unclassifiedWriteTool() {
        CharterPublishCheck check = newCheck(true, builder(), acl(false), name -> false);
        ObjectNode charter = baseCharter("DELEGATED", null);
        List<PublishCheck.PublishError> errors = check.check(spec(charter, List.of("mcp_echo")), ctx());
        assertTrue(errors.stream().anyMatch(e -> CharterPublishCheck.CODE_UNCLASSIFIED.equals(e.code())));
    }

    @Test
    void serviceWithoutUse() {
        CharterPublishCheck check = newCheck(true, builder(), acl(false), name -> false);
        ObjectNode charter = baseCharter("SERVICE", "sa_1");
        List<PublishCheck.PublishError> errors = check.check(spec(charter, List.of()), ctx());
        assertTrue(errors.stream().anyMatch(e -> CharterPublishCheck.CODE_SA_FORBIDDEN.equals(e.code())));
    }

    @Test
    void ceilingL3RequiresAdmin() {
        CharterPublishCheck check = newCheck(true, builder(), acl(true), name -> false);
        ObjectNode charter = baseCharter("DELEGATED", null);
        ObjectNode ac = charter.putArray("actionClasses").addObject();
        ac.put("code", "x").put("name", "X").put("ceiling", "L3");
        ac.putArray("toolPatterns");
        List<PublishCheck.PublishError> errors = check.check(spec(charter, List.of()), ctx());
        assertTrue(errors.stream().anyMatch(e -> CharterPublishCheck.CODE_CEILING_ADMIN.equals(e.code())));
    }

    @Test
    void classifiedAndServiceUsePasses() {
        CharterPublishCheck check = newCheck(true, builder(), acl(true), name -> false);
        ObjectNode charter = baseCharter("SERVICE", "sa_1");
        ObjectNode ac = charter.putArray("actionClasses").addObject();
        ac.put("code", "echo").put("name", "Echo").put("ceiling", "L2");
        ac.putArray("toolPatterns").add("mcp_echo");
        assertEquals(List.of(), check.check(spec(charter, List.of("mcp_echo")), ctx()));
    }

    // ---------- MAS-02：delegable 范围校验 ----------

    @Test
    void delegableBeyondOwnClassesRejected() {
        CharterPublishCheck check = newCheck(true, builder(), acl(true), name -> false);
        ObjectNode charter = baseCharter("DELEGATED", null);
        ObjectNode ac = charter.putArray("actionClasses").addObject();
        ac.put("code", "echo").put("name", "Echo");
        ac.putArray("toolPatterns").add("mcp_echo");
        charter.putArray("delegable").add("echo").add("pay");
        List<PublishCheck.PublishError> errors = check.check(spec(charter, List.of()), ctx());
        assertTrue(errors.stream().anyMatch(e -> CharterPublishCheck.CODE_DELEGABLE_INVALID.equals(e.code())
                && e.message().contains("pay")), String.valueOf(errors));
        // 本授权书类别 echo 不报错
        assertTrue(errors.stream().noneMatch(e -> e.message().contains("echo")),
                String.valueOf(errors));
    }

    @Test
    void delegableWithinOwnClassesPasses() {
        CharterPublishCheck check = newCheck(true, builder(), acl(true), name -> false);
        ObjectNode charter = baseCharter("DELEGATED", null);
        ObjectNode ac = charter.putArray("actionClasses").addObject();
        ac.put("code", "echo").put("name", "Echo");
        ac.putArray("toolPatterns").add("mcp_echo");
        charter.putArray("delegable").add("echo");
        assertEquals(List.of(), check.check(spec(charter, List.of()), ctx()));
    }

    @Test
    void delegableAbsentDerivesFromFlags() {
        // 顶层 delegable 缺省 → 按 actionClasses[].delegable=true 派生；派生码必属本授权书类别 → 放行
        CharterPublishCheck check = newCheck(true, builder(), acl(true), name -> false);
        ObjectNode charter = baseCharter("DELEGATED", null);
        ObjectNode ac = charter.putArray("actionClasses").addObject();
        ac.put("code", "echo").put("name", "Echo").put("delegable", true);
        ac.putArray("toolPatterns").add("mcp_echo");
        assertEquals(List.of(), check.check(spec(charter, List.of()), ctx()));
    }

    @Test
    void delegableAllowedViaSubAgentsMemberCharter() {
        // subAgents 成员授权书的动作类别并入 allowed（尽力解析）
        CharterPublishCheck check = newCheck(true, builder(), acl(true), name -> false);
        ObjectNode charter = baseCharter("DELEGATED", null);
        ObjectNode ac = charter.putArray("actionClasses").addObject();
        ac.put("code", "echo").put("name", "Echo");
        ac.putArray("toolPatterns").add("mcp_echo");
        charter.putArray("delegable").add("echo").add("pay");
        ObjectNode memberCharter = mapper.createObjectNode();
        ObjectNode mac = memberCharter.putArray("actionClasses").addObject();
        mac.put("code", "pay").put("name", "Pay");
        mac.putArray("toolPatterns");
        com.fasterxml.jackson.databind.node.ArrayNode subAgents = mapper.createArrayNode();
        subAgents.add(mapper.createObjectNode().set("charter", memberCharter));
        assertEquals(List.of(), check.check(spec(charter, List.of(), subAgents), ctx()));
    }

    private static ResourceAclApi acl(boolean allow) {
        return new ResourceAclApi() {
            @Override
            public void ensureOwner(String resourceType, String resourceId, String userId) {}

            @Override
            public List<AclEntry> list(String resourceType, String resourceId) {
                return List.of();
            }

            @Override
            public void replace(String resourceType, String resourceId, List<AclEntry> entries) {}

            @Override
            public boolean aclAllows(String resourceType, String resourceId, AccessLevel level) {
                return allow;
            }
        };
    }

    private ObjectNode baseCharter(String actingAs, String saId) {
        ObjectNode charter = mapper.createObjectNode();
        charter.put("role", "r");
        charter.put("actingAs", actingAs);
        if (saId != null) {
            charter.put("serviceAccountId", saId);
        }
        charter.putArray("actionClasses");
        return charter;
    }

    private static CharterPublishCheck newCheck(boolean enabled, PrincipalHolder holders,
                                                ResourceAclApi acl, BuiltinToolCatalog catalog) {
        return new CharterPublishCheck(enabled, holders, acl, provider(catalog));
    }

    private static ObjectProvider<BuiltinToolCatalog> provider(BuiltinToolCatalog catalog) {
        return new ObjectProvider<>() {
            @Override
            public BuiltinToolCatalog getObject() {
                return catalog;
            }

            @Override
            public BuiltinToolCatalog getObject(Object... args) {
                return catalog;
            }

            @Override
            public BuiltinToolCatalog getIfAvailable() {
                return catalog;
            }

            @Override
            public BuiltinToolCatalog getIfUnique() {
                return catalog;
            }
        };
    }

    private static PrincipalHolder builder() {
        Principal p = new Principal("u1", "ws1", PrincipalType.USER, Set.of("builder"),
                Set.of("agent:publish"), "test");
        return new PrincipalHolder() {
            @Override
            public Principal current() {
                return p;
            }

            @Override
            public java.util.Optional<Principal> find() {
                return java.util.Optional.of(p);
            }

            @Override
            public <T> T runAs(Principal principal, java.util.function.Supplier<T> body) {
                return body.get();
            }
        };
    }

    private static AgentSpec spec(ObjectNode charter, List<String> mcpTools) {
        return spec(charter, mcpTools, null);
    }

    private static AgentSpec spec(ObjectNode charter, List<String> mcpTools,
                                  com.fasterxml.jackson.databind.JsonNode subAgents) {
        List<AgentSpec.ToolSet.McpBinding> mcp = mcpTools.isEmpty()
                ? List.of()
                : List.of(new AgentSpec.ToolSet.McpBinding("mcp1", mcpTools));
        return new AgentSpec(
                1,
                new AgentSpec.Profile("a", null, null, List.of(), "CHAT"),
                new AgentSpec.Instructions(List.of(new AgentSpec.Instructions.Section("main", "t"))),
                new AgentSpec.ModelPolicy("rp", false),
                new AgentSpec.ToolSet(List.of(), mcp, List.of(), List.of()),
                List.of(),
                AgentSpec.KnowledgeBinding.empty(),
                AgentSpec.SandboxPolicy.defaults(),
                AgentSpec.ApprovalSettings.empty(),
                AgentSpec.Limits.defaults(),
                charter, null, null, null, null, null, subAgents, null);
    }

    private static PublishCheck.PublishContext ctx() {
        return new PublishCheck.PublishContext("agt", "u1", false);
    }
}

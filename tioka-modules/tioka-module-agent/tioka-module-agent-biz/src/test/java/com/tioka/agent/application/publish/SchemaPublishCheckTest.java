package com.tioka.agent.application.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.agent.api.publish.PublishCheck;
import com.tioka.agent.api.spec.AgentSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C-20 subAgents 解冻裁决：委派开关关闭时维持 M1 预留（须 null），开启后按
 * {@code {"allowed":[agentId...]}} 做结构校验（M3-01 §RT-12 / ADR-027）。
 */
class SchemaPublishCheckTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String SELF = "agt_self";

    private static List<PublishCheck.PublishError> check(boolean delegateEnabled, JsonNode subAgents) {
        SchemaPublishCheck check = new SchemaPublishCheck(M, false, delegateEnabled);
        return check.check(spec(subAgents), new PublishCheck.PublishContext(SELF, "u1", false));
    }

    private static List<PublishCheck.PublishError> subErrors(boolean delegateEnabled, String json) {
        return check(delegateEnabled, node(json)).stream()
                .filter(e -> e.path().startsWith("/subAgents"))
                .toList();
    }

    private static JsonNode node(String json) {
        try {
            return M.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static AgentSpec spec(JsonNode subAgents) {
        return new AgentSpec(1,
                new AgentSpec.Profile("a", null, null, List.of(), "CHAT"),
                new AgentSpec.Instructions(List.of(new AgentSpec.Instructions.Section("main", "t"))),
                new AgentSpec.ModelPolicy("rp", false),
                new AgentSpec.ToolSet(List.of(), List.of(), List.of(), List.of()),
                List.of(),
                AgentSpec.KnowledgeBinding.empty(),
                AgentSpec.SandboxPolicy.defaults(),
                AgentSpec.ApprovalSettings.empty(),
                AgentSpec.Limits.defaults(),
                null, null, null, null, null, null, subAgents, null);
    }

    @Test
    void delegateDisabledKeepsReservedField() {
        List<PublishCheck.PublishError> errors = check(false, node("{\"allowed\":[\"agv_child\"]}"));
        assertTrue(errors.stream().anyMatch(e -> "/subAgents".equals(e.path())
                        && "RESERVED_FIELD".equals(e.code())),
                () -> "开关关闭时仍须按 M1 预留拒绝，实际：" + errors);
    }

    @Test
    void delegateEnabledAcceptsValidWhitelist() {
        assertEquals(List.of(), check(true, node("{\"allowed\":[\"agv_a\",\"agv_b\"]}")),
                "合法白名单应零错误（含 JSON Schema 校验）");
    }

    @Test
    void delegateEnabledAcceptsNull() {
        assertEquals(List.of(), check(true, null));
    }

    @Test
    void nonObjectRejected() {
        List<PublishCheck.PublishError> errors = subErrors(true, "[\"agv_a\"]");
        assertTrue(errors.stream().anyMatch(e -> "/subAgents".equals(e.path())),
                () -> "subAgents 非对象应拒绝，实际：" + errors);
    }

    @Test
    void allowedMustBePresentArray() {
        assertTrue(subErrors(true, "{}").stream()
                .anyMatch(e -> e.path().startsWith("/subAgents/allowed")));
        assertTrue(subErrors(true, "{\"allowed\":\"agv_a\"}").stream()
                .anyMatch(e -> e.path().startsWith("/subAgents/allowed")));
    }

    @Test
    void emptyAllowedRejected() {
        assertTrue(subErrors(true, "{\"allowed\":[]}").stream()
                .anyMatch(e -> e.message().contains("不得为空")));
    }

    @Test
    void nonTextualAndDuplicateMembersRejected() {
        List<PublishCheck.PublishError> nonTextual = subErrors(true, "{\"allowed\":[7]}");
        assertTrue(nonTextual.stream().anyMatch(e -> "/subAgents/allowed/0".equals(e.path())),
                () -> "非字符串成员应拒绝，实际：" + nonTextual);

        List<PublishCheck.PublishError> duplicate = subErrors(true,
                "{\"allowed\":[\"agv_a\",\"agv_a\"]}");
        assertTrue(duplicate.stream().anyMatch(e -> e.message().contains("重复")),
                () -> "重复成员应拒绝，实际：" + duplicate);
    }

    @Test
    void selfReferenceRejected() {
        List<PublishCheck.PublishError> errors = subErrors(true,
                "{\"allowed\":[\"" + SELF + "\"]}");
        assertTrue(errors.stream().anyMatch(e -> e.message().contains("自委派环")),
                () -> "自身列为子 Agent 应拒绝，实际：" + errors);
    }

    @Test
    void rollbackSkipsAllChecks() {
        SchemaPublishCheck check = new SchemaPublishCheck(M, false, false);
        List<PublishCheck.PublishError> errors = check.check(
                spec(node("{\"allowed\":[\"agv_a\"]}")),
                new PublishCheck.PublishContext(SELF, "u1", true));
        assertFalse(errors.stream().anyMatch(e -> e.path().startsWith("/subAgents")),
                "回滚发布应跳过预留与结构校验");
    }
}

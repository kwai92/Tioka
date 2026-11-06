package com.tioka.agent.api.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** record ↔ agent-spec-v1.json 字段一致；未知字段反序列化失败（AST-01）。 */
class AgentSpecContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonNode schemaNode;
    private static JsonSchema schema;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = AgentSpecContractTest.class.getResourceAsStream("/schema/agent-spec-v1.json")) {
            assertNotNull(in);
            schemaNode = MAPPER.readTree(in);
        }
        schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode);
    }

    @Test
    void recordFieldsMatchSchema() {
        Set<String> schemaProps = new HashSet<>();
        Iterator<String> names = schemaNode.get("properties").fieldNames();
        names.forEachRemaining(schemaProps::add);
        Set<String> javaProps = new HashSet<>();
        for (RecordComponent c : AgentSpec.class.getRecordComponents()) {
            javaProps.add(c.getName());
        }
        assertEquals(schemaProps, javaProps);
    }

    @Test
    void unknownFieldRejected() {
        ObjectMapper strict = AgentSpecJson.strictMapper(MAPPER);
        assertThrows(UnrecognizedPropertyException.class, () ->
                strict.readValue("""
                        {"schemaVersion":1,"profile":{"name":"a","kind":"CHAT"},
                         "instructions":{"sections":[]},"model":{"routingPlanId":"rp"},
                         "tools":{},"skills":[],"knowledge":{},"sandbox":{"level":"EDIT"},
                         "approval":{},"limits":{},"unknownField":true}
                        """, AgentSpec.class));
    }

    @Test
    void minimalSpecPassesSchema() throws Exception {
        AgentSpec spec = new AgentSpec(
                1,
                new AgentSpec.Profile("demo", null, null, null, "CHAT"),
                new AgentSpec.Instructions(java.util.List.of(
                        new AgentSpec.Instructions.Section("main", "hi"))),
                new AgentSpec.ModelPolicy("rp_1", false),
                AgentSpec.ToolSet.empty(),
                java.util.List.of(),
                AgentSpec.KnowledgeBinding.empty(),
                AgentSpec.SandboxPolicy.defaults(),
                AgentSpec.ApprovalSettings.empty(),
                AgentSpec.Limits.defaults(),
                null, null, null, null);
        JsonNode node = AgentSpecJson.strictMapper(MAPPER).valueToTree(spec);
        assertTrue(schema.validate(node).isEmpty(), () -> schema.validate(node).toString());
    }
}

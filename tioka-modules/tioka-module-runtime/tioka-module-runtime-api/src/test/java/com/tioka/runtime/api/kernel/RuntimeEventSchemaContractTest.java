package com.tioka.runtime.api.kernel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** SSE 事件 Schema 与 Java 枚举保持一致（ADR-016、ADR-030）。 */
class RuntimeEventSchemaContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static JsonNode schemaNode;
    private static JsonSchema schema;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = RuntimeEventSchemaContractTest.class.getResourceAsStream("/schema/runtime-event-v1.json")) {
            assertNotNull(in, "runtime-event-v1.json must exist on classpath");
            schemaNode = MAPPER.readTree(in);
        }
        schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode);
    }

    @Test
    void eventTypeEnumMatchesJava() {
        List<String> inSchema = new ArrayList<>();
        schemaNode.at("/properties/type/enum").forEach(n -> inSchema.add(n.asText()));
        List<String> inJava = Arrays.stream(RuntimeEventType.values()).map(RuntimeEventType::wire).toList();
        assertEquals(inJava, inSchema);
    }

    @Test
    void turnStatusEnumMatchesJava() {
        List<String> inSchema = new ArrayList<>();
        schemaNode.at("/$defs/turn/properties/status/enum").forEach(n -> inSchema.add(n.asText()));
        List<String> inJava = Arrays.stream(TurnStatus.values()).map(Enum::name).toList();
        assertEquals(inJava, inSchema);
    }

    @Test
    void itemTypeExamplesMatchJava() {
        List<String> inSchema = new ArrayList<>();
        schemaNode.at("/$defs/item/properties/itemType/examples").forEach(n -> inSchema.add(n.asText()));
        List<String> inJava = Arrays.stream(ItemType.values()).map(ItemType::wire).toList();
        assertEquals(inJava, inSchema);
    }

    @Test
    void acceptsDeltaAndRejectsMissingPayload() throws Exception {
        JsonNode ok = MAPPER.readTree("""
                {"schemaVersion":1,"type":"item.delta","threadId":"t1","turnId":"u1",
                 "ts":"2026-11-05T08:00:00Z","delta":{"itemId":"i1","index":0,"text":"he"}}""");
        assertTrue(schema.validate(ok).isEmpty(), () -> schema.validate(ok).toString());

        JsonNode bad = MAPPER.readTree("""
                {"schemaVersion":1,"type":"item.delta","threadId":"t1","turnId":"u1",
                 "ts":"2026-11-05T08:00:00Z"}""");
        assertFalse(schema.validate(bad).isEmpty());
    }

    @Test
    void acceptsGenericApproval() throws Exception {
        JsonNode ev = MAPPER.readTree("""
                {"schemaVersion":1,"type":"approval.requested","threadId":"t1",
                 "ts":"2026-11-05T08:00:00Z",
                 "approval":{"approvalId":"a1","subjectType":"flow_node_run","subjectId":"n1",
                             "kind":"card","status":"PENDING"}}""");
        assertTrue(schema.validate(ev).isEmpty(), () -> schema.validate(ev).toString());
    }
}

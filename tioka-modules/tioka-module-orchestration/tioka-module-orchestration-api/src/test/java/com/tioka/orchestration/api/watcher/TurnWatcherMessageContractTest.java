package com.tioka.orchestration.api.watcher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * orch-turn-watcher 消息结构 ↔ turn_finished / turn_waiting schema（PK-12a / ORC-03）。
 */
class TurnWatcherMessageContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static Path eventsDir;
    private static JsonSchema finishedSchema;
    private static JsonSchema waitingSchema;
    private static JsonNode finishedNode;
    private static JsonNode waitingNode;

    @BeforeAll
    static void load() throws Exception {
        eventsDir = findRepoRoot().resolve("docs/contracts/events");
        finishedNode = MAPPER.readTree(eventsDir.resolve("tioka.runtime.turn_finished.v1.json").toFile());
        waitingNode = MAPPER.readTree(eventsDir.resolve("tioka.runtime.turn_waiting.v1.json").toFile());
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        finishedSchema = factory.getSchema(finishedNode);
        waitingSchema = factory.getSchema(waitingNode);
    }

    @Test
    void consumerNameIsOrchTurnWatcher() {
        assertEquals("orch-turn-watcher", OrchWatcherConsumers.TURN_WATCHER);
        assertEquals("orch-dispatcher", OrchWatcherConsumers.DISPATCHER);
    }

    @Test
    void turnFinishedRecordFieldsCoverSchemaProperties() {
        assertTrue(recordFields(TurnFinishedMessage.class).containsAll(schemaProps(finishedNode)));
    }

    @Test
    void turnWaitingRecordFieldsCoverSchemaProperties() {
        assertTrue(recordFields(TurnWaitingMessage.class).containsAll(schemaProps(waitingNode)));
    }

    @Test
    void turnFinishedSamplePassesSchemaAndFiltersOrch() throws Exception {
        TurnFinishedMessage msg = new TurnFinishedMessage(
                "turn1", "thr1", "COMPLETED", "orch", "run1", null, "ws1");
        JsonNode tree = MAPPER.valueToTree(msg);
        assertTrue(finishedSchema.validate(tree).isEmpty(), () -> finishedSchema.validate(tree).toString());
        assertTrue(msg.isOrchOrigin());

        TurnFinishedMessage other = new TurnFinishedMessage(
                "turn2", "thr1", "FAILED", "chat", "c1", "ERR", "ws1");
        assertFalse(other.isOrchOrigin());
    }

    @Test
    void turnWaitingSamplePassesSchema() throws Exception {
        TurnWaitingMessage msg = new TurnWaitingMessage(
                "turn1", "thr1", "approval", "orch", "run1", "ws1");
        JsonNode tree = MAPPER.valueToTree(msg);
        assertTrue(waitingSchema.validate(tree).isEmpty(), () -> waitingSchema.validate(tree).toString());
        assertTrue(msg.isOrchOrigin());
    }

    @Test
    void turnFinishedRoundTrip() throws Exception {
        String json = """
                {"turnId":"t1","threadId":"th1","status":"COMPLETED","origin":"orch",
                 "originRef":"run9","errorCode":null,"workspaceId":"ws"}
                """;
        TurnFinishedMessage msg = MAPPER.readValue(json, TurnFinishedMessage.class);
        assertEquals("run9", msg.originRef());
        assertEquals("COMPLETED", msg.status());
    }

    private static Set<String> schemaProps(JsonNode schema) {
        Set<String> props = new HashSet<>();
        Iterator<String> it = schema.get("properties").fieldNames();
        it.forEachRemaining(props::add);
        return props;
    }

    private static Set<String> recordFields(Class<?> type) {
        Set<String> fields = new HashSet<>();
        for (RecordComponent c : type.getRecordComponents()) {
            fields.add(c.getName());
        }
        return fields;
    }

    private static Path findRepoRoot() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 10; i++) {
            if (Files.isDirectory(p.resolve("docs/contracts/events"))) {
                return p;
            }
            p = p.getParent();
            if (p == null) {
                break;
            }
        }
        throw new IllegalStateException("无法定位仓库根（含 docs/contracts/events）");
    }
}

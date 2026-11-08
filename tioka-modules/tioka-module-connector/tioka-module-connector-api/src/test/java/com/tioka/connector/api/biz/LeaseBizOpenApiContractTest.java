package com.tioka.connector.api.biz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BIZ-07：冻结 M3-04 §3 的 operationId / 事件 type；CON-07a 随包快照须与 docs 一致。
 */
class LeaseBizOpenApiContractTest {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Set<String> REQUIRED_OPS = Set.of(
            "getContract",
            "getContractFiles",
            "getStoreModels",
            "getConfirmedModel",
            "getCheckpoints",
            "getThresholds",
            "proposeModelDraft",
            "submitReviewDraft",
            "writeLeaseState"
    );
    private static final Set<String> REQUIRED_EVENT_TYPES = Set.of(
            "lease.contract.submitted",
            "contract.model.confirmed",
            "lease.review.feedback",
            "lease.review.closed",
            "lease.kb.changed",
            "lease.performance.recorded"
    );

    private static JsonNode root;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = LeaseBizOpenApiContractTest.class.getResourceAsStream(
                "/contracts/biz/lease-biz.openapi.yaml")) {
            assertNotNull(in, "classpath snapshot missing");
            root = YAML.readTree(in);
        }
    }

    @Test
    void requiredToolOperationsPresent() {
        Set<String> ops = new HashSet<>();
        JsonNode paths = root.get("paths");
        assertNotNull(paths);
        Iterator<String> pathNames = paths.fieldNames();
        while (pathNames.hasNext()) {
            JsonNode pathItem = paths.get(pathNames.next());
            Iterator<String> methods = pathItem.fieldNames();
            while (methods.hasNext()) {
                String method = methods.next();
                if ("parameters".equals(method) || "summary".equals(method) || "description".equals(method)) {
                    continue;
                }
                JsonNode op = pathItem.get(method);
                if (op != null && op.has("operationId")) {
                    ops.add(op.get("operationId").asText());
                }
            }
        }
        assertTrue(ops.containsAll(REQUIRED_OPS), () -> "missing ops: " + diff(REQUIRED_OPS, ops));
    }

    @Test
    void writeOperationsRequireIdempotencyKey() {
        for (String opId : Set.of("proposeModelDraft", "submitReviewDraft", "writeLeaseState")) {
            JsonNode op = findOperation(opId);
            assertNotNull(op, opId);
            boolean found = false;
            if (op.has("parameters")) {
                for (JsonNode p : op.get("parameters")) {
                    if (p.has("$ref") && p.get("$ref").asText().contains("IdempotencyKey")) {
                        found = true;
                    }
                    if ("Idempotency-Key".equals(text(p, "name")) && "header".equals(text(p, "in"))) {
                        found = true;
                    }
                }
            }
            assertTrue(found, opId + " must require Idempotency-Key");
        }
    }

    @Test
    void eventEnvelopeListsRequiredTypes() {
        JsonNode envelope = root.path("components").path("schemas").path("BizEventEnvelope");
        assertTrue(envelope.isObject());
        JsonNode typeEnum = envelope.path("properties").path("type").path("enum");
        assertTrue(typeEnum.isArray());
        Set<String> types = new HashSet<>();
        typeEnum.forEach(n -> types.add(n.asText()));
        assertTrue(types.containsAll(REQUIRED_EVENT_TYPES),
                () -> "missing event types: " + diff(REQUIRED_EVENT_TYPES, types));
        assertEquals("lease-biz", envelope.path("properties").path("source").path("const").asText());
    }

    @Test
    void classpathSnapshotMatchesDocsContract() throws Exception {
        Path found = findRepoFile(Path.of("docs/contracts/biz/lease-biz.openapi.yaml"));
        assertNotNull(found, "docs/contracts/biz/lease-biz.openapi.yaml not found from " + Path.of(".").toAbsolutePath());
        JsonNode docsRoot = YAML.readTree(Files.readString(found));
        assertEquals(docsRoot, root, "CON-07a 快照必须与 docs/contracts 一致");
    }

    @Test
    void eventPayloadSchemasPresentInDocs() {
        for (String name : Set.of(
                "lease-contract-submitted-v1.json",
                "contract-model-confirmed-v1.json",
                "lease-review-feedback-v1.json")) {
            Path p = findRepoFile(Path.of("docs/contracts/events/" + name));
            assertNotNull(p, "missing event schema " + name);
            assertTrue(Files.isRegularFile(p));
        }
    }

    private static Path findRepoFile(Path relative) {
        Path cur = Path.of(".").toAbsolutePath().normalize();
        for (int i = 0; i < 8; i++) {
            Path candidate = cur.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            Path parent = cur.getParent();
            if (parent == null) {
                break;
            }
            cur = parent;
        }
        return null;
    }

    private static JsonNode findOperation(String operationId) {
        JsonNode paths = root.get("paths");
        Iterator<String> pathNames = paths.fieldNames();
        while (pathNames.hasNext()) {
            JsonNode pathItem = paths.get(pathNames.next());
            Iterator<String> methods = pathItem.fieldNames();
            while (methods.hasNext()) {
                JsonNode op = pathItem.get(methods.next());
                if (op != null && op.has("operationId") && operationId.equals(op.get("operationId").asText())) {
                    return op;
                }
            }
        }
        return null;
    }

    private static String text(JsonNode n, String field) {
        return n.has(field) ? n.get(field).asText() : null;
    }

    private static Set<String> diff(Set<String> required, Set<String> actual) {
        Set<String> d = new HashSet<>(required);
        d.removeAll(actual);
        return d;
    }
}

package com.tioka.framework.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ORC-02：events 目录 schema 合法；每个 {@code tioka.*.v*} 事件名常量有对应 payload schema。
 * {@code orch-receipt-v1.json} 是回执工具参数，不是信封，排除在信封扫入之外。
 */
class EnvelopeContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonSchemaFactory SCHEMA_FACTORY =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    /** 信封文件名；不参与「事件常量 ↔ payload 文件」配对。 */
    private static final String ENVELOPE_FILE = "mq-envelope-v1.json";
    /** 回执工具参数，不是跨域事件信封 / payload。 */
    private static final String ORCH_RECEIPT_FILE = "orch-receipt-v1.json";

    private static final Pattern EVENT_NAME =
            Pattern.compile("^tioka\\.[a-z][a-z0-9_]*\\.[a-z][a-z0-9_.]*\\.v[0-9]+$");
    private static final Pattern STRING_CONST =
            Pattern.compile("\"(tioka\\.[a-z][a-z0-9_]*\\.[a-z][a-z0-9_.]*\\.v[0-9]+)\"");

    private static Path eventsDir;
    private static Path repoRoot;

    @BeforeAll
    static void resolvePaths() {
        repoRoot = findRepoRoot();
        eventsDir = repoRoot.resolve("docs/contracts/events");
        assertTrue(Files.isDirectory(eventsDir), "缺少 docs/contracts/events：" + eventsDir);
    }

    @Test
    void allEventSchemasAreValidJsonSchema() throws Exception {
        List<String> failures = new ArrayList<>();
        try (Stream<Path> stream = Files.list(eventsDir)) {
            List<Path> files = stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
            assertFalse(files.isEmpty(), "events 目录为空");
            for (Path file : files) {
                try {
                    JsonNode node = MAPPER.readTree(file.toFile());
                    assertTrue(node.has("$schema"), file.getFileName() + " 缺少 $schema");
                    assertTrue(node.path("type").asText().equals("object") || node.has("allOf") || node.has("oneOf"),
                            file.getFileName() + " 不是 object / 组合 schema");
                    JsonSchema schema = SCHEMA_FACTORY.getSchema(node);
                    assertNotNull(schema, file.getFileName() + " 无法编译为 JsonSchema");
                } catch (Exception e) {
                    failures.add(file.getFileName() + ": " + e.getMessage());
                }
            }
        }
        assertTrue(failures.isEmpty(), () -> "非法 schema:\n" + String.join("\n", failures));
    }

    @Test
    void envelopeSchemaHasNoRequiredTenantId() throws Exception {
        JsonNode envelope = MAPPER.readTree(eventsDir.resolve(ENVELOPE_FILE).toFile());
        List<String> required = new ArrayList<>();
        envelope.path("required").forEach(n -> required.add(n.asText()));
        assertFalse(required.contains("tenantId"), "信封 required 不得含 tenantId");
        assertFalse(envelope.path("properties").has("tenantId"), "信封 properties 不得含 tenantId");
        JsonNode runAsRequired = envelope.path("properties").path("runAs").path("required");
        List<String> runAsReq = new ArrayList<>();
        runAsRequired.forEach(n -> runAsReq.add(n.asText()));
        assertFalse(runAsReq.contains("tenantId"), "runAs.required 不得含 tenantId");
        assertTrue(envelope.path("properties").has("replayOf"), "信封须有可选 replayOf");
        assertTrue(envelope.path("properties").has("traceparent"), "信封须有可选 traceparent");
        assertTrue(envelope.path("properties").has("workspaceId"), "信封须保留可选 workspaceId");
    }

    @Test
    void orchReceiptIsNotScannedAsEnvelope() throws Exception {
        Path receipt = eventsDir.resolve(ORCH_RECEIPT_FILE);
        assertTrue(Files.isRegularFile(receipt), "orch-receipt-v1.json 应仍存在于 events/");
        Set<String> envelopeCandidates = listEnvelopeScanFiles();
        assertFalse(envelopeCandidates.contains(ORCH_RECEIPT_FILE),
                "orch-receipt-v1.json 不得作为信封扫入：" + envelopeCandidates);
        assertTrue(envelopeCandidates.contains(ENVELOPE_FILE), "信封扫入应包含 mq-envelope-v1.json");
    }

    @Test
    void eachTiokaEventConstantHasSchemaFile() throws Exception {
        Set<String> constants = collectEventConstantsFromMqTopics();
        assertFalse(constants.isEmpty(), "未在 *MqTopics.java 中找到 tioka.*.v* 常量");
        List<String> missing = new ArrayList<>();
        for (String event : constants) {
            Path schema = eventsDir.resolve(event + ".json");
            if (!Files.isRegularFile(schema)) {
                missing.add(event);
            }
        }
        assertTrue(missing.isEmpty(),
                () -> "事件常量缺少 schema 文件 docs/contracts/events/{event}.json：\n" + String.join("\n", missing));
    }

    /** 信封扫入：仅 mq-envelope；明确排除 orch-receipt。 */
    private static Set<String> listEnvelopeScanFiles() throws IOException {
        Set<String> names = new LinkedHashSet<>();
        try (Stream<Path> stream = Files.list(eventsDir)) {
            stream.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .filter(n -> n.contains("envelope") || n.equals(ENVELOPE_FILE))
                    .filter(n -> !n.equals(ORCH_RECEIPT_FILE))
                    .forEach(names::add);
        }
        return names;
    }

    private static Set<String> collectEventConstantsFromMqTopics() throws IOException {
        Set<String> events = new LinkedHashSet<>();
        Path modules = repoRoot.resolve("tioka-modules");
        assertTrue(Files.isDirectory(modules), "缺少 tioka-modules");
        try (Stream<Path> stream = Files.walk(modules)) {
            List<Path> topicFiles = stream
                    .filter(p -> p.getFileName().toString().endsWith("MqTopics.java"))
                    .toList();
            for (Path file : topicFiles) {
                String source = Files.readString(file);
                Matcher m = STRING_CONST.matcher(source);
                while (m.find()) {
                    String value = m.group(1);
                    if (EVENT_NAME.matcher(value).matches()) {
                        events.add(value);
                    }
                }
            }
        }
        return events;
    }

    private static Path findRepoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path cur = dir; cur != null; cur = cur.getParent()) {
            if (Files.isDirectory(cur.resolve("docs/contracts/events"))
                    && Files.isDirectory(cur.resolve("tioka-modules"))) {
                return cur;
            }
        }
        throw new IllegalStateException("无法从 " + dir + " 向上找到仓库根（需含 docs/contracts/events）");
    }
}

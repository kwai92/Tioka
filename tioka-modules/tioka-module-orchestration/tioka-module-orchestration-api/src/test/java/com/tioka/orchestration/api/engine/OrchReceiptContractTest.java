package com.tioka.orchestration.api.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回执工具入参 {@link Receipt} ↔ {@code docs/contracts/events/orch-receipt-v1.json}（PK-12a / ORC-03）。
 */
class OrchReceiptContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);

    private static JsonNode schemaNode;
    private static JsonSchema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        Path schemaPath = findRepoRoot().resolve("docs/contracts/events/orch-receipt-v1.json");
        assertTrue(Files.isRegularFile(schemaPath), "缺少 orch-receipt-v1.json：" + schemaPath);
        schemaNode = MAPPER.readTree(schemaPath.toFile());
        schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaNode);
    }

    @Test
    void outcomeEnumMatchesReceipt() {
        Set<String> inSchema = new java.util.LinkedHashSet<>();
        schemaNode.at("/properties/outcome/enum").forEach(n -> inSchema.add(n.asText()));
        Set<String> inJava = java.util.Arrays.stream(Receipt.Outcome.values())
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        assertEquals(inJava, inSchema);
    }

    @Test
    void toolNameIsOrchReceipt() {
        assertEquals("orch.receipt", OrchTools.RECEIPT);
    }

    @Test
    void minimalDonePassesSchemaAndRoundTrips() throws Exception {
        Receipt receipt = new Receipt(
                Receipt.Outcome.DONE,
                "已处理退款申请",
                Map.of("refundId", "rf-1"),
                List.of(new Receipt.Notice("已完成", "requester")));

        JsonNode tree = MAPPER.valueToTree(receipt);
        Set<ValidationMessage> errors = schema.validate(tree);
        assertTrue(errors.isEmpty(), () -> errors.toString());

        Receipt back = MAPPER.treeToValue(tree, Receipt.class);
        assertEquals(Receipt.Outcome.DONE, back.outcome());
        assertEquals("已处理退款申请", back.summary());
        assertEquals(1, back.actions().size());
        assertInstanceOf(Receipt.Notice.class, back.actions().get(0));
        assertEquals("requester", ((Receipt.Notice) back.actions().get(0)).to());
    }

    @Test
    void allActionKindsRoundTripAndPassSchema() throws Exception {
        Receipt receipt = new Receipt(
                Receipt.Outcome.DONE,
                "多动作回执",
                Map.of(),
                List.of(
                        new Receipt.Notice("通知", "channel"),
                        new Receipt.Ask(
                                "confirm-refund",
                                "确认退款？",
                                List.of(Map.of("name", "approve", "type", "boolean")),
                                Map.of("roles", List.of("workspace_admin")),
                                Duration.ofHours(24),
                                Map.of("default", Map.of("approve", false))),
                        new Receipt.Later(
                                "followup",
                                OffsetDateTime.parse("2026-11-06T09:00:00+08:00"),
                                "跟进"),
                        new Receipt.Emit(
                                "tioka.custom.refund_requested.v1",
                                "order:1",
                                Map.of("k", "v"),
                                Map.of("amount", 1))));

        JsonNode tree = MAPPER.valueToTree(receipt);
        assertEquals("notice", tree.at("/actions/0/kind").asText());
        assertEquals("ask", tree.at("/actions/1/kind").asText());
        assertEquals("later", tree.at("/actions/2/kind").asText());
        assertEquals("emit", tree.at("/actions/3/kind").asText());
        assertTrue(schema.validate(tree).isEmpty(), () -> schema.validate(tree).toString());

        Receipt back = MAPPER.treeToValue(tree, Receipt.class);
        assertEquals(4, back.actions().size());
        assertInstanceOf(Receipt.Ask.class, back.actions().get(1));
        assertInstanceOf(Receipt.Later.class, back.actions().get(2));
        assertInstanceOf(Receipt.Emit.class, back.actions().get(3));
    }

    @Test
    void failedAllowsOnlyNoticeInSchema() throws Exception {
        JsonNode ok = MAPPER.readTree("""
                {"outcome":"FAILED","summary":"失败","actions":[{"kind":"notice","text":"x","to":"requester"}]}
                """);
        assertTrue(schema.validate(ok).isEmpty(), () -> schema.validate(ok).toString());

        JsonNode bad = MAPPER.readTree("""
                {"outcome":"FAILED","summary":"失败","actions":[{"kind":"emit","type":"tioka.x.v1"}]}
                """);
        assertFalse(schema.validate(bad).isEmpty());
    }

    @Test
    void rejectsMissingSummary() throws Exception {
        JsonNode bad = MAPPER.readTree("""
                {"outcome":"DONE"}
                """);
        assertFalse(schema.validate(bad).isEmpty());
    }

    private static Path findRepoRoot() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8; i++) {
            if (Files.isDirectory(p.resolve("docs/contracts/events"))) {
                return p;
            }
            p = p.getParent();
            if (p == null) {
                break;
            }
        }
        // 从模块目录运行：…/tioka-module-orchestration-api
        p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8; i++) {
            Path candidate = p.resolve("docs/contracts/events");
            if (Files.isDirectory(candidate)) {
                return p;
            }
            Path up = p.resolve("../../../../docs/contracts/events").normalize();
            if (Files.isDirectory(up)) {
                return up.getParent().getParent().getParent();
            }
            p = p.getParent();
            if (p == null) {
                break;
            }
        }
        throw new IllegalStateException("无法定位仓库根（含 docs/contracts/events）");
    }
}

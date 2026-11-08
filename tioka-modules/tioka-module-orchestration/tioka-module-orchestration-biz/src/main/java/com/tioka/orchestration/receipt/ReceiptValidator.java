package com.tioka.orchestration.receipt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.tioka.orchestration.api.engine.Receipt;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JSON Schema + 语义校验（M2-02 修订第 4 条）。
 */
@Component
public class ReceiptValidator {

    private final ObjectMapper mapper;
    private JsonSchema schema;

    public ReceiptValidator(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    void loadSchema() throws Exception {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        Path repoSchema = findRepoSchema();
        if (repoSchema != null) {
            schema = factory.getSchema(Files.readString(repoSchema));
            return;
        }
        ClassPathResource fallback = new ClassPathResource("contracts/orch-receipt-v1.json");
        if (fallback.exists()) {
            try (InputStream in = fallback.getInputStream()) {
                schema = factory.getSchema(in);
            }
            return;
        }
        // 无 schema 文件时退化为仅语义校验
        schema = null;
    }

    public ReceiptValidationResult validate(JsonNode tree, Set<String> emitWhitelist) {
        List<String> errors = new ArrayList<>();
        List<ReceiptValidationResult.RejectedEmit> rejected = new ArrayList<>();

        if (tree == null || tree.isNull()) {
            errors.add("回执为空");
            return new ReceiptValidationResult(null, errors, rejected);
        }

        if (schema != null) {
            Set<ValidationMessage> msgs = schema.validate(tree);
            for (ValidationMessage m : msgs) {
                errors.add(m.getMessage());
            }
        }

        Receipt receipt;
        try {
            receipt = mapper.treeToValue(tree, Receipt.class);
        } catch (Exception e) {
            errors.add("无法解析为 Receipt: " + e.getMessage());
            return new ReceiptValidationResult(null, errors, rejected);
        }

        if (receipt.summary() != null && receipt.summary().length() > ReceiptLimits.SUMMARY_MAX_LENGTH) {
            errors.add("summary 超过 " + ReceiptLimits.SUMMARY_MAX_LENGTH);
        }

        if (receipt.outcome() == Receipt.Outcome.FAILED) {
            for (Receipt.Action a : receipt.actions()) {
                if (!(a instanceof Receipt.Notice)) {
                    errors.add("FAILED 结局只允许 notice 动作");
                    break;
                }
            }
        }

        Set<String> whitelist = emitWhitelist == null ? Set.of() : emitWhitelist;
        List<Receipt.Action> kept = new ArrayList<>();
        for (Receipt.Action a : receipt.actions()) {
            if (a instanceof Receipt.Ask ask) {
                Duration timeout = ask.timeout() == null ? Duration.ofHours(24) : ask.timeout();
                if (timeout.compareTo(ReceiptLimits.ASK_TIMEOUT_MAX) > 0) {
                    errors.add("ask.timeout 超过 P7D");
                }
                kept.add(a);
            } else if (a instanceof Receipt.Later later) {
                OffsetDateTime at = later.at();
                OffsetDateTime now = OffsetDateTime.now();
                if (at == null || !at.isAfter(now)) {
                    errors.add("later.at 必须在未来");
                } else if (at.isAfter(now.plusDays(ReceiptLimits.LATER_MAX_DAYS))) {
                    errors.add("later.at 超过 90 天");
                }
                kept.add(a);
            } else if (a instanceof Receipt.Emit emit) {
                if (!whitelist.contains(emit.type())) {
                    rejected.add(new ReceiptValidationResult.RejectedEmit(
                            emit.type(), "不在 emits 白名单"));
                } else {
                    kept.add(a);
                }
            } else {
                kept.add(a);
            }
        }

        Receipt cleaned = new Receipt(receipt.outcome(), receipt.summary(), receipt.data(), kept);
        return new ReceiptValidationResult(cleaned, errors, rejected);
    }

    private static Path findRepoSchema() {
        Path cur = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && cur != null; i++) {
            Path candidate = cur.resolve("docs/contracts/events/orch-receipt-v1.json");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            cur = cur.getParent();
        }
        return null;
    }

    public static Set<String> parseEmitsJson(String emitsJson, ObjectMapper mapper) {
        if (emitsJson == null || emitsJson.isBlank()) {
            return Set.of();
        }
        try {
            JsonNode node = mapper.readTree(emitsJson);
            Set<String> out = new HashSet<>();
            if (node.isArray()) {
                node.forEach(n -> {
                    if (n.isTextual()) {
                        out.add(n.asText());
                    }
                });
            }
            return out;
        } catch (Exception e) {
            return Set.of();
        }
    }
}

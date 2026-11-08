package com.tioka.ontology.application.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.ontology.api.schema.SchemaApi;
import com.tioka.ontology.api.schema.SchemaDef;
import com.tioka.ontology.api.schema.SchemaError;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Schema 登记表（SCH-01）。本阶段内存实现 + 发布校验；持久化随 V171 仓储后续接线。
 * 默认不经 Runtime 调用（特性开关关闭）。
 */
public class SchemaRegistry implements SchemaApi {

    private final ObjectMapper objectMapper;
    private final OutputValidator outputValidator;
    private final ConcurrentMap<String, SchemaDef> store = new ConcurrentHashMap<>();

    public SchemaRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.outputValidator = new OutputValidator();
    }

    public SchemaDef publish(String schemaKey, int version, JsonNode jsonSchema, String ownerPack) {
        return publish(schemaKey, version, jsonSchema, ownerPack, "INTERNAL");
    }

    public SchemaDef publish(
            String schemaKey, int version, JsonNode jsonSchema, String ownerPack, String defaultLabel) {
        List<SchemaPublishValidator.SchemaPublishIssue> issues = SchemaPublishValidator.validate(jsonSchema);
        if (!issues.isEmpty()) {
            throw new SchemaPublishException(issues.stream()
                    .map(i -> new SchemaError(i.pointer(), i.code(), i.message()))
                    .toList());
        }
        String hash = contentHash(jsonSchema);
        String mapKey = mapKey(schemaKey, version);
        SchemaDef existing = store.get(mapKey);
        if (existing != null) {
            if (!existing.contentHash().equals(hash)) {
                throw new SchemaPublishException(List.of(new SchemaError(
                        "",
                        "HASH_CONFLICT",
                        "same schema_key@version already published with different content")));
            }
            return existing;
        }
        SchemaDef def = new SchemaDef(
                schemaKey, version, jsonSchema.deepCopy(), ownerPack, defaultLabel, hash, "PUBLISHED");
        store.put(mapKey, def);
        return def;
    }

    @Override
    public Optional<SchemaDef> get(String ref) {
        return parseRef(ref).flatMap(p -> Optional.ofNullable(store.get(mapKey(p.key(), p.version()))));
    }

    @Override
    public List<SchemaError> validate(String ref, JsonNode payload) {
        Optional<SchemaDef> def = get(ref);
        if (def.isEmpty()) {
            return List.of(new SchemaError("", "SCHEMA_NOT_FOUND", "schema not registered: " + ref));
        }
        return outputValidator.validate(def.get().jsonSchema(), payload);
    }

    @Override
    public List<SchemaError> checkEvidence(String ref, JsonNode payload, List<String> visibleDocIds) {
        // SCH-01：登记表与校验通路先打通；证据绑定完整规则随 DOC 链路补齐
        if (get(ref).isEmpty()) {
            return List.of(new SchemaError("", "SCHEMA_NOT_FOUND", "schema not registered: " + ref));
        }
        return List.of();
    }

    private String contentHash(JsonNode schema) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(schema);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("failed to hash schema", e);
        }
    }

    private static String mapKey(String schemaKey, int version) {
        return schemaKey + "@" + version;
    }

    private static Optional<RefParts> parseRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        int at = ref.lastIndexOf('@');
        if (at <= 0 || at == ref.length() - 1) {
            return Optional.empty();
        }
        try {
            int version = Integer.parseInt(ref.substring(at + 1));
            return Optional.of(new RefParts(ref.substring(0, at), version));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private record RefParts(String key, int version) {
    }
}

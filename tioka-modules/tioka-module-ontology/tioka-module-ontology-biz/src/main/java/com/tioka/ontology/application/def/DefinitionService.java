package com.tioka.ontology.application.def;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.json.CanonicalJson;
import com.tioka.ontology.api.def.DefStatus;
import com.tioka.ontology.api.def.ModelDefinition;
import com.tioka.ontology.api.def.PathCatalogEntry;
import com.tioka.ontology.api.def.PublishedDefinition;
import com.tioka.ontology.api.schema.SchemaDef;
import com.tioka.ontology.application.schema.SchemaRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 模型定义资产：草稿 / 发布 / 回滚 / diff（对齐 ADR-023 资产版本语义；本阶段内存实现，特性开关默认关闭）。
 */
public class DefinitionService {

    private final ObjectMapper objectMapper;
    private final SchemaDeriver schemaDeriver;
    private final ConcurrentMap<String, DraftState> drafts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<Integer, PublishedDefinition>> versions =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Integer> currentPublished = new ConcurrentHashMap<>();

    public DefinitionService(ObjectMapper objectMapper, SchemaRegistry schemaRegistry) {
        this.objectMapper = objectMapper;
        this.schemaDeriver = new SchemaDeriver(objectMapper, schemaRegistry);
    }

    /** 保存 / 覆盖草稿（version=0 占位，发布时分配正式版本号）。 */
    public PublishedDefinition saveDraft(String packKey, String packVersion, ModelDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(definition.defKey(), "defKey");
        List<PathCatalogEntry> catalog = PathCatalogBuilder.build(definition);
        String hash = CanonicalJson.sha256Hex(objectMapper, definition);
        PublishedDefinition draft = new PublishedDefinition(
                definition.defKey(),
                0,
                packKey,
                packVersion,
                definition,
                catalog,
                hash,
                DefStatus.DRAFT);
        drafts.put(definition.defKey(), new DraftState(packKey, packVersion, definition, catalog, hash));
        return draft;
    }

    public Optional<PublishedDefinition> getDraft(String defKey) {
        DraftState d = drafts.get(defKey);
        if (d == null) {
            return Optional.empty();
        }
        return Optional.of(new PublishedDefinition(
                defKey, 0, d.packKey(), d.packVersion(), d.definition(), d.catalog(), d.hash(), DefStatus.DRAFT));
    }

    /**
     * 发布草稿。
     *
     * @param validatorExists         领域包已注册的校验器
     * @param scenarioReferencesPath  已发布场景（审查要点、字段模板）是否引用路径
     */
    public PublishedDefinition publish(
            String defKey,
            Predicate<String> validatorExists,
            Predicate<String> scenarioReferencesPath) {
        DraftState draft = drafts.get(defKey);
        if (draft == null) {
            throw new IllegalStateException("draft not found: " + defKey);
        }
        List<PathCatalogEntry> catalog = draft.catalog();
        Set<String> previousPaths = currentPublishedVersion(defKey)
                .map(p -> p.pathCatalog().stream().map(PathCatalogEntry::path).collect(Collectors.toSet()))
                .orElse(Set.of());

        List<DefinitionPublishIssue> issues = DefinitionPublishValidator.validate(
                draft.definition(),
                catalog,
                validatorExists == null ? id -> false : validatorExists,
                previousPaths,
                scenarioReferencesPath == null ? path -> false : scenarioReferencesPath);
        if (!issues.isEmpty()) {
            throw new DefinitionPublishException(issues);
        }

        int nextVersion = nextVersionNo(defKey);
        List<SchemaDef> schemas = schemaDeriver.deriveAndPublish(
                draft.definition(), nextVersion, draft.packKey());
        if (schemas.isEmpty() && !draft.definition().objectTypes().isEmpty()) {
            throw new IllegalStateException("schema derive produced empty set");
        }

        PublishedDefinition published = new PublishedDefinition(
                defKey,
                nextVersion,
                draft.packKey(),
                draft.packVersion(),
                draft.definition(),
                catalog,
                draft.hash(),
                DefStatus.PUBLISHED);
        versions.computeIfAbsent(defKey, k -> new ConcurrentHashMap<>()).put(nextVersion, published);
        currentPublished.put(defKey, nextVersion);
        return published;
    }

    public PublishedDefinition rollback(String defKey, int version) {
        PublishedDefinition target = getVersion(defKey, version)
                .orElseThrow(() -> new IllegalArgumentException("version not found: " + defKey + "@" + version));
        if (target.status() != DefStatus.PUBLISHED) {
            throw new IllegalArgumentException("can only rollback to PUBLISHED version");
        }
        drafts.put(defKey, new DraftState(
                target.packKey(),
                target.packVersion(),
                target.definition(),
                target.pathCatalog(),
                target.contentHash()));
        currentPublished.put(defKey, version);
        return target;
    }

    public List<Map<String, Object>> diff(String defKey, int fromVersion, int toVersion) {
        PublishedDefinition from = getVersion(defKey, fromVersion)
                .orElseThrow(() -> new IllegalArgumentException("from version missing"));
        PublishedDefinition to = getVersion(defKey, toVersion)
                .orElseThrow(() -> new IllegalArgumentException("to version missing"));
        try {
            JsonNode a = objectMapper.valueToTree(from.definition());
            JsonNode b = objectMapper.valueToTree(to.definition());
            return jsonPatch(a, b, "");
        } catch (Exception e) {
            throw new IllegalStateException("diff failed: " + e.getMessage(), e);
        }
    }

    /** 路径级 diff：返回删除 / 新增的路径（发布校验第 4 条依赖）。 */
    public PathDiff pathDiff(String defKey, int fromVersion, int toVersion) {
        Set<String> from = getVersion(defKey, fromVersion)
                .orElseThrow(() -> new IllegalArgumentException("from version missing"))
                .pathCatalog().stream().map(PathCatalogEntry::path).collect(Collectors.toSet());
        Set<String> to = getVersion(defKey, toVersion)
                .orElseThrow(() -> new IllegalArgumentException("to version missing"))
                .pathCatalog().stream().map(PathCatalogEntry::path).collect(Collectors.toSet());
        List<String> removed = from.stream().filter(p -> !to.contains(p)).sorted().toList();
        List<String> added = to.stream().filter(p -> !from.contains(p)).sorted().toList();
        return new PathDiff(removed, added);
    }

    public Optional<PublishedDefinition> getVersion(String defKey, int version) {
        ConcurrentMap<Integer, PublishedDefinition> map = versions.get(defKey);
        if (map == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(map.get(version));
    }

    public Optional<PublishedDefinition> currentPublishedVersion(String defKey) {
        Integer v = currentPublished.get(defKey);
        if (v == null) {
            return Optional.empty();
        }
        return getVersion(defKey, v);
    }

    private int nextVersionNo(String defKey) {
        ConcurrentMap<Integer, PublishedDefinition> map = versions.get(defKey);
        if (map == null || map.isEmpty()) {
            return 1;
        }
        return map.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
    }

    private static List<Map<String, Object>> jsonPatch(JsonNode a, JsonNode b, String path) {
        List<Map<String, Object>> ops = new ArrayList<>();
        if (a == null || a.isNull()) {
            if (b != null && !b.isNull()) {
                ops.add(op("add", path, b));
            }
            return ops;
        }
        if (b == null || b.isNull()) {
            ops.add(op("remove", path, a));
            return ops;
        }
        if (a.equals(b)) {
            return ops;
        }
        if (a.isObject() && b.isObject()) {
            Map<String, Boolean> keys = new LinkedHashMap<>();
            a.fieldNames().forEachRemaining(k -> keys.put(k, true));
            b.fieldNames().forEachRemaining(k -> keys.put(k, true));
            for (String key : keys.keySet()) {
                String child = path.isEmpty() ? "/" + key : path + "/" + key;
                ops.addAll(jsonPatch(a.get(key), b.get(key), child));
            }
            return ops;
        }
        ops.add(op("replace", path, b));
        return ops;
    }

    private static Map<String, Object> op(String op, String path, JsonNode value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op", op);
        m.put("path", path);
        m.put("value", value);
        return m;
    }

    public record PathDiff(List<String> removed, List<String> added) {
    }

    private record DraftState(
            String packKey,
            String packVersion,
            ModelDefinition definition,
            List<PathCatalogEntry> catalog,
            String hash
    ) {
    }
}

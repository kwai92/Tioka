package com.tioka.ontology.application.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.ontology.api.schema.DataItemReader;
import com.tioka.ontology.api.schema.FallbackDeclaration;
import com.tioka.ontology.api.schema.ProcessedOutput;
import com.tioka.ontology.api.schema.RawModelOutput;
import com.tioka.ontology.api.schema.ReasonCode;
import com.tioka.ontology.api.schema.SchemaApi;
import com.tioka.ontology.api.schema.SchemaDef;
import com.tioka.ontology.api.schema.SchemaError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Schema 登记表（SCH-01）。本阶段内存实现 + 发布校验；持久化随 V171 仓储后续接线。
 * 默认不经 Runtime 调用（特性开关关闭）。
 *
 * <p>SCH-03：{@link #checkEvidence} 真实现——按 schema 的 {@code x-tioka-evidence} 标注
 * 定位证据对象，对其 {@code evidence} / {@code sources} 数组中的引文
 * （{@code unitId} + {@code quote}）逐个调 {@link EvidenceBinder}。
 * EvidenceBinder 经 {@code ObjectProvider} 弱依赖：{@code tioka.ontology.doc.enabled=false}
 * 时无绑定器，返回空清单并在日志标明「证据检查跳过」（双开关降级口径）。
 */
public class SchemaRegistry implements SchemaApi {

    private static final Logger log = LoggerFactory.getLogger(SchemaRegistry.class);

    private final ObjectMapper objectMapper;
    private final OutputValidator outputValidator;
    private final ObjectProvider<EvidenceBinder> evidenceBinder;
    private final ObjectProvider<DataItemReader> dataItemReader;
    private final SchemaRuleEvaluator ruleEvaluator;
    private final RefIntegrityChecker refChecker;
    private final OutputPipeline outputPipeline = new OutputPipeline();
    private final ConcurrentMap<String, SchemaDef> store = new ConcurrentHashMap<>();

    public SchemaRegistry(ObjectMapper objectMapper) {
        this(objectMapper, null, null);
    }

    public SchemaRegistry(ObjectMapper objectMapper, ObjectProvider<EvidenceBinder> evidenceBinder) {
        this(objectMapper, evidenceBinder, null);
    }

    public SchemaRegistry(ObjectMapper objectMapper,
                          ObjectProvider<EvidenceBinder> evidenceBinder,
                          ObjectProvider<DataItemReader> dataItemReader) {
        this.objectMapper = objectMapper;
        this.outputValidator = new OutputValidator();
        this.evidenceBinder = evidenceBinder;
        this.dataItemReader = dataItemReader;
        this.ruleEvaluator = new SchemaRuleEvaluator();
        this.refChecker = new RefIntegrityChecker(buildRefResolvers());
    }

    /** {@code x-tioka-ref} 各类型解析器：存在性由文档层 / data item 读取器提供，缺席即跳过。 */
    private Map<String, RefIntegrityChecker.RefResolver> buildRefResolvers() {
        Map<String, RefIntegrityChecker.RefResolver> resolvers = new LinkedHashMap<>();
        RefIntegrityChecker.RefResolver docResolver = (reference, visible) -> {
            EvidenceBinder binder = evidenceBinder == null ? null : evidenceBinder.getIfAvailable();
            if (binder == null) {
                return Optional.empty();
            }
            return Optional.of(binder.exists(reference, visible));
        };
        resolvers.put("doc.unit", docResolver);
        resolvers.put("doc.clause", docResolver);
        if (dataItemReader != null) {
            resolvers.put("dataitem", (reference, visible) -> {
                DataItemReader reader = dataItemReader.getIfAvailable();
                if (reader == null) {
                    return Optional.empty();
                }
                return Optional.of(reader.read(reference, null).isPresent());
            });
        }
        return resolvers;
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

    /**
     * 严格档发布（SCH-06 / ADR-066 §3）：在子集校验之上追加严格档 S1〜S9 与
     * {@code x-tioka-rules} 发布期编译；任一不通过则整份发布被拒。
     * 声明 {@code x-tioka-tier: relaxed} 的 Schema 跳过严格档（消费方须声明 {@code allowRelaxedSchema}）。
     */
    public SchemaDef publishStrict(String schemaKey, int version, JsonNode jsonSchema, String ownerPack) {
        return publishStrict(schemaKey, version, jsonSchema, ownerPack, "INTERNAL");
    }

    public SchemaDef publishStrict(
            String schemaKey, int version, JsonNode jsonSchema, String ownerPack, String defaultLabel) {
        List<SchemaPublishValidator.SchemaPublishIssue> issues =
                new ArrayList<>(SchemaPublishValidator.validate(jsonSchema));
        String selfRef = "tioka://schema/" + schemaKey + "@" + version;
        issues.addAll(SchemaPublishValidator.validateStrictTier(jsonSchema, selfRef));
        List<SchemaError> ruleIssues = ruleEvaluator.compileAll(jsonSchema);
        if (!issues.isEmpty() || !ruleIssues.isEmpty()) {
            List<SchemaError> errors = new ArrayList<>(issues.stream()
                    .map(i -> new SchemaError(i.pointer(), i.code(), i.message()))
                    .toList());
            errors.addAll(ruleIssues);
            throw new SchemaPublishException(List.copyOf(errors));
        }
        return publish(schemaKey, version, jsonSchema, ownerPack, defaultLabel);
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
        Optional<SchemaDef> def = get(ref);
        if (def.isEmpty()) {
            return List.of(new SchemaError("", "SCHEMA_NOT_FOUND", "schema not registered: " + ref));
        }
        EvidenceBinder binder = evidenceBinder == null ? null : evidenceBinder.getIfAvailable();
        if (binder == null) {
            // 双开关降级：tioka.ontology.doc 未启用 → 证据检查跳过（返回空清单）
            log.info("证据检查跳过：EvidenceBinder 未装配（tioka.ontology.doc.enabled=false）ref={}", ref);
            return List.of();
        }
        List<SchemaError> errors = new ArrayList<>();
        walkEvidence(def.get().jsonSchema(), payload, "", ref, binder, visibleDocIds, errors);
        return List.copyOf(errors);
    }

    @Override
    public List<SchemaError> checkRules(String ref, JsonNode payload) {
        Optional<SchemaDef> def = get(ref);
        if (def.isEmpty()) {
            return List.of(new SchemaError("", "SCHEMA_NOT_FOUND", "schema not registered: " + ref));
        }
        try {
            return List.copyOf(ruleEvaluator.evaluateErrors(def.get().jsonSchema(), payload));
        } catch (RuntimeException e) {
            // ADR-066 §1.3：流水线内异常不向调用方传播，转为 RULE 失败
            log.warn("语义规则执行异常 ref={}：{}", ref, e.toString());
            return List.of(new SchemaError("", "RULE", "语义规则执行异常：" + e.getMessage()));
        }
    }

    @Override
    public List<String> warnPaths(String ref, JsonNode payload) {
        Optional<SchemaDef> def = get(ref);
        if (def.isEmpty()) {
            return List.of();
        }
        try {
            List<String> warns = ruleEvaluator.warnedRules(def.get().jsonSchema(), payload);
            for (String warnPath : warns) {
                log.info("语义规则 WARN（不阻断）ref={} path={}", ref, warnPath);
            }
            return warns;
        } catch (RuntimeException e) {
            log.warn("语义规则 WARN 求值异常 ref={}：{}", ref, e.toString());
            return List.of();
        }
    }

    @Override
    public boolean relaxedTier(String ref) {
        return get(ref).map(SchemaDef::jsonSchema)
                .map(SchemaPublishValidator::tierOf)
                .map(tier -> tier == SchemaPublishValidator.SchemaTier.RELAXED)
                .orElse(false);
    }

    @Override
    public List<FallbackDeclaration> fallbackDeclarations(String ref) {
        Optional<SchemaDef> def = get(ref);
        if (def.isEmpty()) {
            return List.of();
        }
        List<FallbackDeclaration> out = new ArrayList<>();
        collectFallbacks(def.get().jsonSchema(), "", out);
        return List.copyOf(out);
    }

    /** 递归收集对象属性上的 {@code x-tioka-fallback} 声明（数组元素兜底未在 SCH-09 范围）。 */
    private void collectFallbacks(JsonNode schemaNode, String pointer, List<FallbackDeclaration> out) {
        if (schemaNode == null || !schemaNode.isObject()) {
            return;
        }
        JsonNode props = schemaNode.get("properties");
        if (props == null || !props.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = props.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            JsonNode prop = e.getValue();
            String ptr = pointer + "/" + e.getKey();
            JsonNode fb = prop == null ? null : prop.get("x-tioka-fallback");
            if (fb != null && !fb.isNull()) {
                out.add(new FallbackDeclaration(ptr, fb));
            }
            collectFallbacks(prop, ptr, out);
        }
    }

    @Override
    public ProcessedOutput process(String ref, RawModelOutput raw) {
        JsonNode schema = get(ref).map(SchemaDef::jsonSchema).orElse(null);
        try {
            return outputPipeline.process(schema, raw);
        } catch (RuntimeException e) {
            // ADR-066 §1.3：任何输入都以 ProcessedOutput 返回，异常转 INTERNAL
            log.warn("输出流水线内部异常 ref={}：{}", ref, e.toString());
            return ProcessedOutput.failed(ReasonCode.INTERNAL,
                    new SchemaError("", ReasonCode.INTERNAL.name(), "输出流水线内部异常：" + e.getMessage()),
                    null);
        }
    }

    @Override
    public List<SchemaError> checkRefs(String ref, JsonNode payload, List<String> visibleDocIds) {
        Optional<SchemaDef> def = get(ref);
        if (def.isEmpty()) {
            return List.of(new SchemaError("", "SCHEMA_NOT_FOUND", "schema not registered: " + ref));
        }
        try {
            return List.copyOf(refChecker.check(def.get().jsonSchema(), payload, visibleDocIds));
        } catch (RuntimeException e) {
            log.warn("引用完整性检查异常 ref={}：{}", ref, e.toString());
            return List.of(new SchemaError("", "REF_NOT_FOUND", "引用完整性检查异常：" + e.getMessage()));
        }
    }

    /** schema/payload 同步遍历；x-tioka-evidence 标注对象上的 evidence/sources 引文逐个绑定。 */
    private void walkEvidence(JsonNode schemaNode, JsonNode payloadNode, String pointer, String ref,
                              EvidenceBinder binder, List<String> visibleDocIds, List<SchemaError> errors) {
        if (schemaNode == null || payloadNode == null
                || payloadNode.isMissingNode() || payloadNode.isNull()) {
            return;
        }
        if (schemaNode.isObject() && payloadNode.isObject()) {
            if (schemaNode.has("x-tioka-evidence")) {
                checkCitations(payloadNode, pointer, binder, visibleDocIds, errors);
            }
            JsonNode props = schemaNode.get("properties");
            if (props != null && props.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = props.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> e = fields.next();
                    walkEvidence(e.getValue(), payloadNode.get(e.getKey()),
                            pointer + "/" + e.getKey(), ref, binder, visibleDocIds, errors);
                }
            }
            return;
        }
        JsonNode items = schemaNode.isObject() ? schemaNode.get("items") : null;
        if (items != null && payloadNode.isArray()) {
            for (int i = 0; i < payloadNode.size(); i++) {
                walkEvidence(items, payloadNode.get(i), pointer + "/" + i, ref,
                        binder, visibleDocIds, errors);
            }
        }
    }

    /** 引文字段约定：{@code evidence} / {@code sources} 数组，元素形如 {@code {unitId, quote}}。 */
    private void checkCitations(JsonNode payloadObj, String pointer, EvidenceBinder binder,
                                List<String> visibleDocIds, List<SchemaError> errors) {
        for (String field : List.of("evidence", "sources")) {
            JsonNode arr = payloadObj.get(field);
            if (arr == null || !arr.isArray()) {
                continue;
            }
            for (int i = 0; i < arr.size(); i++) {
                JsonNode citation = arr.get(i);
                if (citation == null || !citation.isObject()) {
                    continue;
                }
                String unitId = citation.path("unitId").asText(null);
                String quote = citation.path("quote").asText(null);
                if (unitId == null || unitId.isBlank()) {
                    errors.add(new SchemaError(pointer + "/" + field + "/" + i,
                            "QUOTE_MISMATCH", "引文缺少 unitId"));
                    continue;
                }
                EvidenceBinder.Violation v = binder
                        .check(unitId, quote == null ? "" : quote, visibleDocIds).orElse(null);
                if (v != null) {
                    errors.add(new SchemaError(pointer + "/" + field + "/" + i,
                            v.code().name(), v.message()));
                }
            }
        }
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

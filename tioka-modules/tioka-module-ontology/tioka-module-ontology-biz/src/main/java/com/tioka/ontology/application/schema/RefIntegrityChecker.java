package com.tioka.ontology.application.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.tioka.ontology.api.schema.ReasonCode;
import com.tioka.ontology.api.schema.SchemaError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code x-tioka-ref} 引用完整性检查（SCH-08 / ADR-066 §5.2）。
 *
 * <p>字符串字段可标 {@code x-tioka-ref}，声明它引用的对象类型：
 * {@code doc.unit}、{@code doc.clause}、{@code kb.chunk}、{@code model.path}
 * （等同 {@code x-tioka-path}）、{@code dataitem}（本 Turn 内的其他 {@code data} item）。
 * 检查被引用对象存在且对本次运行可见，失败记 {@link ReasonCode#REF_NOT_FOUND}。
 *
 * <p>各类型的解析器由调用方按可用性注入；缺省时该类型跳过并记日志（弱依赖降级口径，
 * 与 {@link SchemaRegistry} 对 {@code EvidenceBinder} 的处理一致）。
 */
public class RefIntegrityChecker {

    private static final Logger log = LoggerFactory.getLogger(RefIntegrityChecker.class);

    /**
     * 单类型解析器：{@code reference} 是否存在且对本次运行可见。
     * 返回 {@code Optional.empty()} 表示该类型当前不可判定（跳过，不记失败）。
     */
    @FunctionalInterface
    public interface RefResolver {
        java.util.Optional<Boolean> exists(String reference, List<String> visibleDocIds);
    }

    /** 支持的类型（ADR-066 §5.2）。 */
    public static final Set<String> KNOWN_TYPES =
            Set.of("doc.unit", "doc.clause", "kb.chunk", "model.path", "dataitem");

    private final Map<String, RefResolver> resolvers;
    private final Set<String> missingLogged = new LinkedHashSet<>();

    public RefIntegrityChecker(Map<String, RefResolver> resolvers) {
        this.resolvers = resolvers == null ? Map.of() : Map.copyOf(resolvers);
    }

    public List<SchemaError> check(JsonNode schema, JsonNode payload, List<String> visibleDocIds) {
        List<SchemaError> errors = new ArrayList<>();
        walk(schema, payload, "", visibleDocIds, errors);
        return errors;
    }

    private void walk(JsonNode schemaNode, JsonNode payloadNode, String pointer,
                      List<String> visibleDocIds, List<SchemaError> errors) {
        if (schemaNode == null || payloadNode == null
                || payloadNode.isMissingNode() || payloadNode.isNull()) {
            return;
        }
        if (schemaNode.isObject() && payloadNode.isObject()) {
            JsonNode props = schemaNode.get("properties");
            if (props != null && props.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = props.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> e = fields.next();
                    walk(e.getValue(), payloadNode.get(e.getKey()),
                            pointer + "/" + e.getKey(), visibleDocIds, errors);
                }
            }
            return;
        }
        JsonNode refNode = schemaNode.isObject() ? schemaNode.get("x-tioka-ref") : null;
        if (refNode != null && refNode.isTextual()) {
            checkRef(refNode.asText(), payloadNode, pointer, visibleDocIds, errors);
            return;
        }
        JsonNode items = schemaNode.isObject() ? schemaNode.get("items") : null;
        if (items != null && payloadNode.isArray()) {
            for (int i = 0; i < payloadNode.size(); i++) {
                walk(items, payloadNode.get(i), pointer + "/" + i, visibleDocIds, errors);
            }
        }
    }

    private void checkRef(String type, JsonNode payloadNode, String pointer,
                          List<String> visibleDocIds, List<SchemaError> errors) {
        RefResolver resolver = resolvers.get(type);
        if (resolver == null) {
            if (missingLogged.add(type)) {
                log.info("引用完整性检查跳过：类型 {} 无解析器（ref={}）", type, pointer);
            }
            return;
        }
        if (payloadNode.isArray()) {
            for (int i = 0; i < payloadNode.size(); i++) {
                JsonNode element = payloadNode.get(i);
                if (element != null && element.isTextual()) {
                    checkOne(type, resolver, element.asText(), pointer + "/" + i, visibleDocIds, errors);
                }
            }
            return;
        }
        if (payloadNode.isTextual()) {
            checkOne(type, resolver, payloadNode.asText(), pointer, visibleDocIds, errors);
        }
    }

    private void checkOne(String type, RefResolver resolver, String reference, String pointer,
                          List<String> visibleDocIds, List<SchemaError> errors) {
        java.util.Optional<Boolean> exists = resolver.exists(reference, visibleDocIds);
        if (exists.isPresent() && !exists.get()) {
            errors.add(new SchemaError(pointer, ReasonCode.REF_NOT_FOUND.name(),
                    "引用的 " + type + " 不存在或不可见：" + reference));
        }
    }
}

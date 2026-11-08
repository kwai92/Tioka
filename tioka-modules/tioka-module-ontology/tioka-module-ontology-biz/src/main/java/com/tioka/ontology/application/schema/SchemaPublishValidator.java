package com.tioka.ontology.application.schema;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ADR-037 发布校验：JSON Schema 2020-12 子集 + 仅允许 tioka://schema/{key}@{ver} 的 $ref。
 * 合入 ontology-biz 后由 SchemaRegistry.publish 调用。
 */
public final class SchemaPublishValidator {

    private static final Set<String> ALLOWED_KEYWORDS = Set.of(
            "type", "properties", "required", "enum", "items", "$ref", "oneOf",
            "pattern", "minimum", "maximum",
            "title", "description", "additionalProperties",
            "minItems", "maxItems", "minLength", "maxLength",
            "x-tioka-evidence", "x-tioka-compare", "x-tioka-identity", "x-tioka-path"
    );

    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of(
            "if", "then", "else", "dependentSchemas", "dependentRequired",
            "$dynamicRef", "$recursiveRef"
    );

    private static final Pattern INTERNAL_REF =
            Pattern.compile("^tioka://schema/[A-Za-z0-9._-]+@[1-9][0-9]*$");

    private SchemaPublishValidator() {
    }

    public static List<SchemaPublishIssue> validate(JsonNode schema) {
        List<SchemaPublishIssue> issues = new ArrayList<>();
        walkSchema(schema, "", issues);
        return issues;
    }

    /** node 是一份 schema 对象（或 oneOf/items 里的 schema）。 */
    private static void walkSchema(JsonNode node, String pointer, List<SchemaPublishIssue> issues) {
        if (node == null || !node.isObject()) {
            if (node != null && node.isArray()) {
                for (int i = 0; i < node.size(); i++) {
                    walkSchema(node.get(i), pointer + "/" + i, issues);
                }
            }
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            String key = e.getKey();
            JsonNode value = e.getValue();
            String childPtr = pointer + "/" + escape(key);

            if (FORBIDDEN_KEYWORDS.contains(key)) {
                issues.add(new SchemaPublishIssue(childPtr, "UNSUPPORTED_KEYWORD",
                        "keyword not allowed in Tioka schema subset: " + key));
                continue;
            }
            if (key.startsWith("x-") && !ALLOWED_KEYWORDS.contains(key)) {
                issues.add(new SchemaPublishIssue(childPtr, "UNSUPPORTED_KEYWORD",
                        "unknown extension: " + key));
                continue;
            }
            if (!ALLOWED_KEYWORDS.contains(key)) {
                issues.add(new SchemaPublishIssue(childPtr, "UNSUPPORTED_KEYWORD",
                        "keyword not in allow-list: " + key));
                continue;
            }

            switch (key) {
                case "$ref" -> validateRef(value, childPtr, issues);
                case "properties" -> walkProperties(value, childPtr, issues);
                case "items" -> walkSchema(value, childPtr, issues);
                case "oneOf" -> {
                    if (!value.isArray()) {
                        issues.add(new SchemaPublishIssue(childPtr, "UNSUPPORTED_KEYWORD",
                                "oneOf must be an array"));
                    } else {
                        for (int i = 0; i < value.size(); i++) {
                            walkSchema(value.get(i), childPtr + "/" + i, issues);
                        }
                    }
                }
                case "additionalProperties" -> {
                    if (value.isObject()) {
                        walkSchema(value, childPtr, issues);
                    }
                }
                default -> {
                    // type/required/enum/pattern/minimum/... 叶子，不再下钻为 schema
                }
            }
        }
    }

    private static void walkProperties(JsonNode properties, String pointer, List<SchemaPublishIssue> issues) {
        if (properties == null || !properties.isObject()) {
            issues.add(new SchemaPublishIssue(pointer, "UNSUPPORTED_KEYWORD", "properties must be an object"));
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = properties.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            // 属性名任意；值必须是子 schema
            walkSchema(e.getValue(), pointer + "/" + escape(e.getKey()), issues);
        }
    }

    private static void validateRef(JsonNode refNode, String pointer, List<SchemaPublishIssue> issues) {
        if (refNode == null || !refNode.isTextual()) {
            issues.add(new SchemaPublishIssue(pointer, "REMOTE_REF", "$ref must be a string"));
            return;
        }
        String ref = refNode.asText();
        if (!INTERNAL_REF.matcher(ref).matches()) {
            issues.add(new SchemaPublishIssue(pointer, "REMOTE_REF",
                    "$ref must match tioka://schema/{key}@{ver}, got: " + ref));
        }
    }

    private static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    public record SchemaPublishIssue(String pointer, String code, String message) {
    }
}

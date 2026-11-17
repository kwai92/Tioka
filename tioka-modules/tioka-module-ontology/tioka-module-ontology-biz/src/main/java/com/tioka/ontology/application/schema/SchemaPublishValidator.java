package com.tioka.ontology.application.schema;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ADR-037 / ADR-066 发布校验：
 * <ol>
 *   <li>{@link #validate(JsonNode)}：JSON Schema 2020-12 子集 + 仅允许
 *       {@code tioka://schema/{key}@{ver}} 的 {@code $ref}（ADR-037）。</li>
 *   <li>{@link #validateStrictTier(JsonNode, String)}：严格档 S1〜S9（ADR-066 §3），
 *       用于模型产出的 Schema；声明 {@code x-tioka-tier: relaxed} 的 Schema 跳过严格档，
 *       但只能被声明了 {@code allowRelaxedSchema} 的 Agent 引用。</li>
 * </ol>
 */
public final class SchemaPublishValidator {

    /** x-tioka-* 扩展关键字白名单（ADR-037 + ADR-066 §2/§3/§5）。 */
    private static final Set<String> ALLOWED_KEYWORDS = Set.of(
            // JSON Schema 子集
            "type", "properties", "required", "enum", "items", "$ref", "oneOf",
            "pattern", "minimum", "maximum",
            "title", "description", "additionalProperties",
            "minItems", "maxItems", "minLength", "maxLength",
            // ADR-037 扩展
            "x-tioka-evidence", "x-tioka-compare", "x-tioka-identity", "x-tioka-path",
            // ADR-066 §2/§3/§5 扩展
            "x-tioka-rules", "x-tioka-ref", "x-tioka-fallback", "x-tioka-coerce",
            "x-tioka-tier", "x-tioka-group", "x-tioka-reasoning",
            "x-tioka-enum-doc", "x-tioka-open-enum"
    );

    private static final Set<String> FORBIDDEN_KEYWORDS = Set.of(
            "if", "then", "else", "dependentSchemas", "dependentRequired",
            "$dynamicRef", "$recursiveRef"
    );

    // S8：长度、范围、pattern 等约束允许出现，但只在权威校验中生效（不要求删除），
    // 因此严格档不做额外检查——「编译掉的约束仍由平台校验拦住」由权威 Schema 校验保证。

    private static final Pattern INTERNAL_REF =
            Pattern.compile("^tioka://schema/[A-Za-z0-9._-]+@[1-9][0-9]*$");

    /** S9：枚举值只允许小写字母、数字、下划线。 */
    private static final Pattern ENUM_LOWER = Pattern.compile("^[a-z0-9_]+$");

    /** S4 规模上限（ADR-066 §3）。 */
    static final int MAX_DEPTH = 5;
    static final int MAX_PROPERTIES = 100;
    static final int MAX_ENUM_SIZE = 200;
    static final int MAX_UNION_FIELDS = 16;

    /** S7 兜底值形态：未知 / 待复核类取值。 */
    private static final Pattern FALLBACK_VALUE = Pattern.compile(".*(unknown|pending|unset|na|待|未知).*");

    private SchemaPublishValidator() {
    }

    // ==================== 子集校验（ADR-037） ====================

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

    // ==================== 严格档（ADR-066 §3） ====================

    /** 严格档 / 非严格档（{@code x-tioka-tier}）。 */
    public enum SchemaTier {
        STRICT,
        RELAXED
    }

    /** 读取 Schema 声明的档位；缺省为严格档。 */
    public static SchemaTier tierOf(JsonNode schema) {
        JsonNode tier = schema == null ? null : schema.get("x-tioka-tier");
        if (tier != null && tier.isTextual() && "relaxed".equalsIgnoreCase(tier.asText())) {
            return SchemaTier.RELAXED;
        }
        return SchemaTier.STRICT;
    }

    /**
     * 严格档校验（S1〜S9）。
     *
     * @param schema  权威 Schema
     * @param selfRef 本 Schema 的 {@code tioka://schema/{key}@{ver}}；用于 S3 自引用检测，未知传 null
     */
    public static List<SchemaPublishIssue> validateStrictTier(JsonNode schema, String selfRef) {
        List<SchemaPublishIssue> issues = new ArrayList<>();
        if (schema == null || !schema.isObject()) {
            return issues;
        }
        if (tierOf(schema) == SchemaTier.RELAXED) {
            // S: 声明非严格档 → 跳过严格档检查（消费方须声明 allowRelaxedSchema）
            return issues;
        }
        Ctx ctx = new Ctx(selfRef);
        walkStrict(schema, "", 1, ctx, issues);

        // S4：属性总数 / 联合类型字段数
        if (ctx.propertyCount > MAX_PROPERTIES) {
            issues.add(new SchemaPublishIssue("", "STRICT_S4",
                    "属性总数 " + ctx.propertyCount + " 超过上限 " + MAX_PROPERTIES));
        }
        if (ctx.unionFieldCount > MAX_UNION_FIELDS) {
            issues.add(new SchemaPublishIssue("", "STRICT_S4",
                    "可空或联合类型字段数 " + ctx.unionFieldCount + " 超过上限 " + MAX_UNION_FIELDS));
        }
        return issues;
    }

    public static List<SchemaPublishIssue> validateStrictTier(JsonNode schema) {
        return validateStrictTier(schema, null);
    }

    private static final class Ctx {
        final String selfRef;
        int propertyCount;
        int unionFieldCount;

        Ctx(String selfRef) {
            this.selfRef = selfRef;
        }
    }

    private static void walkStrict(JsonNode node, String pointer, int depth, Ctx ctx,
                                   List<SchemaPublishIssue> issues) {
        if (node == null || !node.isObject()) {
            if (node != null && node.isArray()) {
                for (int i = 0; i < node.size(); i++) {
                    walkStrict(node.get(i), pointer + "/" + i, depth, ctx, issues);
                }
            }
            return;
        }
        // S4：嵌套深度
        if (depth > MAX_DEPTH) {
            issues.add(new SchemaPublishIssue(pointer, "STRICT_S4",
                    "嵌套深度 " + depth + " 超过上限 " + MAX_DEPTH));
        }
        // S3：自引用
        if (ctx.selfRef != null && node.has("$ref")
                && ctx.selfRef.equals(node.get("$ref").asText(null))) {
            issues.add(new SchemaPublishIssue(pointer + "/$ref", "STRICT_S3",
                    "严格档不允许自引用（递归）"));
        }

        JsonNode props = node.get("properties");
        if (props != null && props.isObject()) {
            validateObjectStrict(node, props, pointer, depth, ctx, issues);
        }
        JsonNode items = node.get("items");
        if (items != null) {
            walkStrict(items, pointer + "/items", depth + 1, ctx, issues);
        }
        JsonNode oneOf = node.get("oneOf");
        if (oneOf != null && oneOf.isArray()) {
            for (int i = 0; i < oneOf.size(); i++) {
                walkStrict(oneOf.get(i), pointer + "/oneOf/" + i, depth + 1, ctx, issues);
            }
        }
        JsonNode ap = node.get("additionalProperties");
        if (ap != null && ap.isObject()) {
            walkStrict(ap, pointer + "/additionalProperties", depth + 1, ctx, issues);
        }
    }

    private static void validateObjectStrict(JsonNode node, JsonNode props, String pointer, int depth,
                                             Ctx ctx, List<SchemaPublishIssue> issues) {
        Set<String> required = new LinkedHashSet<>();
        JsonNode reqNode = node.get("required");
        if (reqNode != null && reqNode.isArray()) {
            for (JsonNode r : reqNode) {
                required.add(r.asText());
            }
        }
        // S1：全部必填
        Iterator<String> names = props.fieldNames();
        List<String> propertyNames = new ArrayList<>();
        while (names.hasNext()) {
            propertyNames.add(names.next());
        }
        ctx.propertyCount += propertyNames.size();
        for (String name : propertyNames) {
            if (!required.contains(name)) {
                issues.add(new SchemaPublishIssue(pointer + "/properties/" + escape(name), "STRICT_S1",
                        "严格档要求所有属性出现在 required 中（可缺省请用可空类型）：" + name));
            }
        }
        // S2：封闭对象
        JsonNode ap = node.get("additionalProperties");
        if (ap == null || !ap.isBoolean() || ap.asBoolean()) {
            issues.add(new SchemaPublishIssue(pointer, "STRICT_S2",
                    "严格档要求 additionalProperties: false"));
        }
        // S6：理由在前——标了 x-tioka-reasoning 的字段须排在非理由字段之前
        boolean seenNonReasoning = false;
        for (String name : propertyNames) {
            JsonNode prop = props.get(name);
            boolean reasoning = prop != null && prop.path("x-tioka-reasoning").asBoolean(false);
            if (reasoning && seenNonReasoning) {
                issues.add(new SchemaPublishIssue(pointer + "/properties/" + escape(name),
                        "STRICT_S6", "x-tioka-reasoning 字段必须排在结论字段之前：" + name));
            }
            if (!reasoning) {
                seenNonReasoning = true;
            }
        }
        // 逐属性检查
        for (String name : propertyNames) {
            JsonNode prop = props.get(name);
            String propPtr = pointer + "/properties/" + escape(name);
            // S5：字段说明
            if (prop.path("description").asText("").isBlank()) {
                issues.add(new SchemaPublishIssue(propPtr, "STRICT_S5", "严格档要求每个属性有 description：" + name));
            }
            // S4：联合类型字段计数
            if (isUnionOrNullable(prop)) {
                ctx.unionFieldCount++;
            }
            validateEnum(prop, propPtr, issues);
            walkStrict(prop, propPtr, depth + 1, ctx, issues);
        }
    }

    private static boolean isUnionOrNullable(JsonNode prop) {
        if (prop == null) {
            return false;
        }
        if (prop.has("oneOf")) {
            return true;
        }
        JsonNode type = prop.get("type");
        if (type != null && type.isArray()) {
            for (JsonNode t : type) {
                if ("null".equals(t.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void validateEnum(JsonNode prop, String propPtr, List<SchemaPublishIssue> issues) {
        JsonNode en = prop == null ? null : prop.get("enum");
        if (en == null || !en.isArray()) {
            return;
        }
        // S4：枚举规模
        if (en.size() > MAX_ENUM_SIZE) {
            issues.add(new SchemaPublishIssue(propPtr, "STRICT_S4",
                    "枚举项数 " + en.size() + " 超过上限 " + MAX_ENUM_SIZE));
        }
        boolean openEnum = prop.path("x-tioka-open-enum").asBoolean(false);
        JsonNode enumDoc = prop.get("x-tioka-enum-doc");
        for (JsonNode v : en) {
            if (!v.isTextual()) {
                continue;
            }
            String value = v.asText();
            // S9：枚举值规范（小写字母 / 数字 / 下划线，或中文词）
            if (!ENUM_LOWER.matcher(value).matches() && !isChineseWord(value)) {
                issues.add(new SchemaPublishIssue(propPtr, "STRICT_S9",
                        "枚举值只允许小写字母、数字、下划线或固定中文词：" + value));
            }
            // S5：枚举每个值要有说明（关闭枚举除外）
            if (!openEnum && (enumDoc == null || !enumDoc.has(value))) {
                issues.add(new SchemaPublishIssue(propPtr, "STRICT_S5",
                        "严格档要求枚举值有 x-tioka-enum-doc 说明：" + value));
            }
        }
        // S7：声明兜底
        JsonNode fallback = prop.get("x-tioka-fallback");
        if (fallback != null && !fallback.isNull()) {
            String fb = fallback.asText();
            boolean declared = false;
            for (JsonNode v : en) {
                if (v.isTextual() && v.asText().equals(fb)) {
                    declared = true;
                    break;
                }
            }
            if (!declared) {
                issues.add(new SchemaPublishIssue(propPtr, "STRICT_S7",
                        "x-tioka-fallback 取值必须在 enum 中：" + fb));
            } else if (!FALLBACK_VALUE.matcher(fb).matches()) {
                issues.add(new SchemaPublishIssue(propPtr, "STRICT_S7",
                        "兜底值须为「未知 / 待复核」类状态：" + fb));
            }
        }
    }

    /** S9：固定中文词（全部为 CJK 字符）。 */
    private static boolean isChineseWord(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (Character.UnicodeScript.of(s.charAt(i)) != Character.UnicodeScript.HAN) {
                return false;
            }
        }
        return true;
    }

    private static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    public record SchemaPublishIssue(String pointer, String code, String message) {
    }
}

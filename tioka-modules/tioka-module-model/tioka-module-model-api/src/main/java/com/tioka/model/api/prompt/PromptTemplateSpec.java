package com.tioka.model.api.prompt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AST-07（ADR-041 §4）：Prompt 模板的发布型 spec（内容存 {@code cfg_*}，身份表 {@code prm_template} 只存名称与作用域）。
 *
 * <p>纯数据 + 序列化，无 Spring/JDBC 依赖；放在 model-api 以便运行时发布、GOV-12 发布校验、
 * 内置模板种子（Java 迁移）共用同一口径。
 *
 * @param name            稳定名称：{@code sys.*} / {@code pack.{包}.{名}} / 空间级 / 个人级
 * @param scope           作用域：{@link #SCOPE_SYSTEM} / {@link #SCOPE_PACK} / {@link #SCOPE_WORKSPACE} / {@link #SCOPE_PERSONAL}
 * @param scopeRef        作用域归属：包名 / 空间 ID / 用户 ID；系统级为 null
 * @param purpose         用途：{@link #PURPOSES}
 * @param sections        分段正文（按角色 system / user）
 * @param variables       变量定义
 * @param outputFormat    {@code text} 或 {@code json}
 * @param schemaRef       {@code outputFormat=json} 时引用的结构化输出 Schema（ADR-037）
 * @param suggestedAlias  编辑器「试运行」默认使用的模型别名
 * @param sampleVariables 样例变量（发布校验渲染测试与编辑器预览）
 */
public record PromptTemplateSpec(
        String name,
        String scope,
        String scopeRef,
        String purpose,
        List<Section> sections,
        List<Variable> variables,
        String outputFormat,
        String schemaRef,
        String suggestedAlias,
        Map<String, Object> sampleVariables) {

    public static final String SCOPE_SYSTEM = "system";
    public static final String SCOPE_PACK = "pack";
    public static final String SCOPE_WORKSPACE = "workspace";
    public static final String SCOPE_PERSONAL = "personal";

    public static final String FORMAT_TEXT = "text";
    public static final String FORMAT_JSON = "json";

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";

    public static final String TYPE_STRING = "string";
    public static final String TYPE_NUMBER = "number";
    public static final String TYPE_BOOLEAN = "boolean";
    public static final String TYPE_LIST = "list";
    public static final String TYPE_OBJECT = "object";

    /** 用途枚举（ADR-041 §4）。 */
    public static final Set<String> PURPOSES = Set.of(
            "agent", "flow", "decision", "summarize", "extract", "chat_snippet");

    /** 占位符：{@code {{name}}} 或条件段/列表段 {@code {{#name}}}。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([#^]?)([A-Za-z_][A-Za-z0-9_]*)\\}\\}");

    /** 按角色分段的正文。 */
    public record Section(String role, String body) {}

    /**
     * 变量定义。
     *
     * @param name         变量名（占位符里的标识符）
     * @param type         {@link PromptTemplateSpec#TYPE_STRING} 等
     * @param required     是否必填（运行期缺失抛 {@code PROMPT_RENDER_ERROR}）
     * @param defaultValue 默认值（字符串形式；可空）
     * @param description  说明
     */
    public record Variable(String name, String type, boolean required, String defaultValue, String description) {
        public Variable(String name, String type, boolean required, String description) {
            this(name, type, required, null, description);
        }
    }

    public ObjectNode toJson(ObjectMapper mapper) {
        ObjectNode n = mapper.createObjectNode();
        n.put("name", name);
        n.put("scope", scope);
        if (scopeRef != null) {
            n.put("scopeRef", scopeRef);
        }
        if (purpose != null) {
            n.put("purpose", purpose);
        }
        ArrayNode secs = n.putArray("sections");
        if (sections != null) {
            for (Section s : sections) {
                ObjectNode o = secs.addObject();
                o.put("role", s.role());
                o.put("body", s.body());
            }
        }
        ArrayNode vars = n.putArray("variables");
        if (variables != null) {
            for (Variable v : variables) {
                ObjectNode o = vars.addObject();
                o.put("name", v.name());
                o.put("type", v.type());
                o.put("required", v.required());
                if (v.defaultValue() != null) {
                    o.put("defaultValue", v.defaultValue());
                }
                if (v.description() != null) {
                    o.put("description", v.description());
                }
            }
        }
        n.put("outputFormat", outputFormat == null ? FORMAT_TEXT : outputFormat);
        if (schemaRef != null) {
            n.put("schemaRef", schemaRef);
        }
        if (suggestedAlias != null) {
            n.put("suggestedAlias", suggestedAlias);
        }
        ObjectNode samples = n.putObject("sampleVariables");
        if (sampleVariables != null) {
            sampleVariables.forEach((k, v) -> samples.set(k, mapper.valueToTree(v)));
        }
        return n;
    }

    public static PromptTemplateSpec fromJson(ObjectMapper mapper, JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        List<Section> sections = new ArrayList<>();
        JsonNode secs = node.get("sections");
        if (secs != null && secs.isArray()) {
            for (JsonNode s : secs) {
                sections.add(new Section(text(s, "role"), text(s, "body")));
            }
        }
        List<Variable> variables = new ArrayList<>();
        JsonNode vars = node.get("variables");
        if (vars != null && vars.isArray()) {
            for (JsonNode v : vars) {
                variables.add(new Variable(
                        text(v, "name"),
                        text(v, "type") == null ? TYPE_STRING : text(v, "type"),
                        v.path("required").asBoolean(false),
                        v.hasNonNull("defaultValue") ? v.get("defaultValue").asText() : null,
                        text(v, "description")));
            }
        }
        Map<String, Object> samples = new TreeMap<>();
        JsonNode sv = node.get("sampleVariables");
        if (sv != null && sv.isObject()) {
            sv.fields().forEachRemaining(e -> samples.put(e.getKey(), mapper.convertValue(e.getValue(), Object.class)));
        }
        return new PromptTemplateSpec(
                text(node, "name"),
                text(node, "scope") == null ? SCOPE_SYSTEM : text(node, "scope"),
                text(node, "scopeRef"),
                text(node, "purpose"),
                sections,
                variables,
                text(node, "outputFormat") == null ? FORMAT_TEXT : text(node, "outputFormat"),
                text(node, "schemaRef"),
                text(node, "suggestedAlias"),
                samples);
    }

    /** 正文里出现的全部占位符名（{@code {{name}}} 与 {@code {{#name}}} 一视同仁）。 */
    public Set<String> placeholders() {
        Set<String> names = new LinkedHashSet<>();
        if (sections == null) {
            return names;
        }
        for (Section s : sections) {
            if (s.body() == null) {
                continue;
            }
            Matcher m = PLACEHOLDER.matcher(s.body());
            while (m.find()) {
                names.add(m.group(2));
            }
        }
        return names;
    }

    /** 已声明变量名集合。 */
    public Set<String> declaredNames() {
        Set<String> names = new LinkedHashSet<>();
        if (variables != null) {
            for (Variable v : variables) {
                if (v != null && v.name() != null) {
                    names.add(v.name());
                }
            }
        }
        return names;
    }

    /** 规范化作用域（小写）；未知返回原值。 */
    public static String normalizeScope(String scope) {
        return scope == null ? null : scope.trim().toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}

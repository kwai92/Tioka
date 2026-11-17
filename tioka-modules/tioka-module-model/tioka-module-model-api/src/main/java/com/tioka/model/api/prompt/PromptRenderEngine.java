package com.tioka.model.api.prompt;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AST-07（ADR-041 §4）：无逻辑模板渲染。
 *
 * <p>只做三件事：变量替换 {@code {{name}}}、条件段 {@code {{#name}}…{{/name}}}（真值才渲染）、
 * 列表展开（值为列表时对每项重复段体，项内可用 {@code {{.}}} 取当前项）。不执行任何表达式。
 *
 * <p>缺必填变量（{@link PromptTemplateSpec.Variable#required()} 为真且无默认值时）抛
 * {@link PromptRenderException}，由调用方映射为 {@code PROMPT_RENDER_ERROR}。
 */
public final class PromptRenderEngine {

    private PromptRenderEngine() {
    }

    /** 渲染结果：每段角色 + 渲染后正文。 */
    public record RenderedSection(String role, String body) {}

    public static List<RenderedSection> render(PromptTemplateSpec spec, Map<String, ?> variables) {
        Map<String, Object> ctx = new HashMap<>();
        if (variables != null) {
            ctx.putAll(variables);
        }
        Map<String, PromptTemplateSpec.Variable> declared = new LinkedHashMap<>();
        if (spec.variables() != null) {
            for (PromptTemplateSpec.Variable v : spec.variables()) {
                declared.put(v.name(), v);
                if (!ctx.containsKey(v.name()) && v.defaultValue() != null) {
                    ctx.put(v.name(), v.defaultValue());
                }
            }
        }
        String tplName = spec.name() == null ? "(anonymous)" : spec.name();
        List<RenderedSection> out = new ArrayList<>();
        if (spec.sections() == null) {
            return out;
        }
        for (PromptTemplateSpec.Section s : spec.sections()) {
            String body = renderBody(s.body() == null ? "" : s.body(), ctx, declared, tplName);
            out.add(new RenderedSection(s.role(), body));
        }
        return out;
    }

    /** 单段渲染（供校验与测试直接使用）。 */
    public static String renderBody(String template, Map<String, Object> ctx,
                                    Map<String, PromptTemplateSpec.Variable> declared, String templateName) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf("{{", i);
            if (open < 0) {
                sb.append(template, i, template.length());
                break;
            }
            sb.append(template, i, open);
            int close = template.indexOf("}}", open + 2);
            if (close < 0) {
                sb.append(template, open, template.length());
                break;
            }
            String tag = template.substring(open + 2, close).trim();
            i = close + 2;
            if (tag.startsWith("#") || tag.startsWith("^")) {
                boolean inverted = tag.startsWith("^");
                String name = tag.substring(1).trim();
                int end = findClosing(template, i, name);
                if (end < 0) {
                    throw new PromptRenderException(templateName, "未闭合的段: {{" + tag + "}}");
                }
                int endClose = template.indexOf("}}", end + 2);
                String inner = template.substring(i, end);
                i = endClose < 0 ? template.length() : endClose + 2;
                appendSection(sb, name, inner, ctx, declared, templateName, inverted);
            } else if (tag.equals(".")) {
                Object cur = ctx.get(".");
                sb.append(cur == null ? "" : String.valueOf(cur));
            } else if (!tag.isEmpty()) {
                if (!ctx.containsKey(tag)) {
                    PromptTemplateSpec.Variable v = declared.get(tag);
                    if (v != null && v.required()) {
                        throw new PromptRenderException(templateName, "缺少必填变量: " + tag);
                    }
                }
                Object v = ctx.get(tag);
                sb.append(v == null ? "" : String.valueOf(v));
            }
        }
        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, String name, String inner, Map<String, Object> ctx,
                                      Map<String, PromptTemplateSpec.Variable> declared, String templateName,
                                      boolean inverted) {
        Object v = ctx.get(name);
        boolean truthy = isTruthy(v);
        if (inverted) {
            if (!truthy) {
                sb.append(renderBody(inner, ctx, declared, templateName));
            }
            return;
        }
        if (v instanceof Iterable<?> it) {
            for (Object item : it) {
                sb.append(renderBody(inner, childContext(ctx, item), declared, templateName));
            }
            return;
        }
        if (v != null && v.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(v);
            for (int k = 0; k < len; k++) {
                sb.append(renderBody(inner, childContext(ctx, java.lang.reflect.Array.get(v, k)), declared, templateName));
            }
            return;
        }
        if (truthy) {
            sb.append(renderBody(inner, ctx, declared, templateName));
        }
    }

    private static Map<String, Object> childContext(Map<String, Object> parent, Object item) {
        Map<String, Object> child = new HashMap<>(parent);
        child.put(".", item);
        if (item instanceof Map<?, ?> m) {
            m.forEach((k, val) -> child.put(String.valueOf(k), val));
        }
        return child;
    }

    private static boolean isTruthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof CharSequence s) {
            return !s.isEmpty();
        }
        if (v instanceof Iterable<?> it) {
            return it.iterator().hasNext();
        }
        if (v instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0d;
        }
        return true;
    }

    /** 找到与 {@code {{#name}}} 配对的 {@code {{/name}}} 的起始下标（处理嵌套）。 */
    private static int findClosing(String template, int from, String name) {
        int depth = 0;
        int i = from;
        while (i < template.length()) {
            int open = template.indexOf("{{", i);
            if (open < 0) {
                return -1;
            }
            int close = template.indexOf("}}", open + 2);
            if (close < 0) {
                return -1;
            }
            String tag = template.substring(open + 2, close).trim();
            if (tag.equals("#" + name) || tag.equals("^" + name)) {
                depth++;
            } else if (tag.equals("/" + name)) {
                if (depth == 0) {
                    return open;
                }
                depth--;
            }
            i = close + 2;
        }
        return -1;
    }
}

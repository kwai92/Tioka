package com.tioka.flow.application.exec;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PromptNodeExecutor implements NodeExecutor {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");

    @Override
    public String type() { return "Prompt"; }

    @Override
    public Object execute(NodeExecContext ctx, Map<String, Object> node, Object input) {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) node.getOrDefault("config", Map.of());
        String template = String.valueOf(config.getOrDefault("template", "{{text}}"));
        Map<String, Object> vars = new java.util.LinkedHashMap<>(ctx.runInput());
        if (input instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                vars.put(String.valueOf(e.getKey()), e.getValue());
            }
        } else if (input != null) {
            vars.put("input", input);
            vars.put("text", input);
        }
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object val = vars.get(key);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(val == null ? "" : String.valueOf(val)));
        }
        matcher.appendTail(sb);
        return Map.of("prompt", sb.toString(), "text", sb.toString());
    }
}

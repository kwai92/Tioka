package com.tioka.flow.application.exec;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ParserNodeExecutor implements NodeExecutor {
    @Override
    public String type() { return "Parser"; }

    @Override
    public Object execute(NodeExecContext ctx, Map<String, Object> node, Object input) {
        String raw;
        if (input instanceof Map<?, ?> m) {
            Object t = m.containsKey("text") ? m.get("text") : m.get("content");
            raw = t == null ? String.valueOf(input) : String.valueOf(t);
        } else {
            raw = input == null ? "" : String.valueOf(input);
        }
        String normalized = strip(raw);
        return Map.of("text", normalized, "format", "txt");
    }

    static String strip(String raw) {
        if (raw == null) return "";
        return raw
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}

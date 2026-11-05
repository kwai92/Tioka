package com.tioka.flow.application.exec;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class BranchNodeExecutor implements NodeExecutor {
    @Override
    public String type() { return "Branch"; }

    @Override
    public Object execute(NodeExecContext ctx, Map<String, Object> node, Object input) {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) node.getOrDefault("config", Map.of());
        boolean pass = evaluate(config, input, ctx.runInput());
        return Map.of("branch", pass);
    }

    public static boolean evaluate(Map<String, Object> config, Object input, Map<String, Object> runInput) {
        String field = String.valueOf(config.getOrDefault("field", "route"));
        String expect = String.valueOf(config.getOrDefault("equals", "A"));
        Object actual = null;
        if (input instanceof Map<?, ?> m && m.containsKey(field)) {
            actual = m.get(field);
        } else if (input instanceof Map<?, ?> m && m.containsKey("branch")) {
            Object b = m.get("branch");
            if (b instanceof Boolean bool) return bool;
            return "true".equalsIgnoreCase(String.valueOf(b));
        } else if (runInput != null) {
            actual = runInput.get(field);
        }
        if (actual == null && input != null && !(input instanceof Map)) {
            actual = input;
        }
        return expect.equals(String.valueOf(actual));
    }
}

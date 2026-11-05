package com.tioka.flow.application.exec;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class LoopNodeExecutor implements NodeExecutor {
    @Override
    public String type() { return "Loop"; }

    @Override
    public Object execute(NodeExecContext ctx, Map<String, Object> node, Object input) {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) node.getOrDefault("config", Map.of());
        int max = 1;
        Object maxObj = config.get("max");
        if (maxObj instanceof Number n) max = n.intValue();
        else if (maxObj != null) {
            try { max = Integer.parseInt(String.valueOf(maxObj)); } catch (NumberFormatException ignored) {}
        }
        max = Math.max(1, Math.min(5, max));
        List<Object> iterations = new ArrayList<>();
        Object acc = input;
        for (int i = 0; i < max; i++) {
            iterations.add(Map.of("i", i, "value", acc == null ? Map.of() : acc));
        }
        return Map.of("count", max, "iterations", iterations, "output", acc == null ? Map.of() : acc);
    }
}

package com.tioka.flow.application.exec;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class IoNodeExecutor implements NodeExecutor {
    @Override
    public String type() { return "IO"; }

    @Override
    public Object execute(NodeExecContext ctx, Map<String, Object> node, Object input) {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) node.getOrDefault("config", Map.of());
        Object mapCfg = config.get("map");
        if (mapCfg instanceof Map<?, ?> projection && input instanceof Map<?, ?> src) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : projection.entrySet()) {
                String targetKey = String.valueOf(e.getKey());
                String sourceKey = String.valueOf(e.getValue());
                out.put(targetKey, src.get(sourceKey));
            }
            return out;
        }
        if (mapCfg instanceof List<?> fields && input instanceof Map<?, ?> src) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Object f : fields) {
                String key = String.valueOf(f);
                out.put(key, src.get(key));
            }
            return out;
        }
        return input == null ? Map.of() : input;
    }
}

package com.tioka.connector.infrastructure;

import com.tioka.system.api.spi.BizSystemAdapter;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class ExampleBizSystemAdapter implements BizSystemAdapter {
    @Override public String systemType() { return "example-erp"; }
    @Override public Map<String, Object> invoke(String action, Map<String, Object> payload) {
        return Map.of("action", action, "echo", payload == null ? Map.of() : payload, "ok", true);
    }
}

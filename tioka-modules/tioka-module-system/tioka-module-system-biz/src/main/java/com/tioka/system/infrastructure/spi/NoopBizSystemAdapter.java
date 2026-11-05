package com.tioka.system.infrastructure.spi;

import com.tioka.system.api.spi.BizSystemAdapter;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

@Component
public class NoopBizSystemAdapter implements BizSystemAdapter {
    @Override public String systemType() { return "noop"; }
    @Override public Map<String, Object> invoke(String action, Map<String, Object> payload) {
        return Collections.emptyMap();
    }
}

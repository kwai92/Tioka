package com.tioka.system.infrastructure.spi;

import com.tioka.system.api.spi.ContentSafetyHook;
import org.springframework.stereotype.Component;

@Component
public class NoopContentSafetyHook implements ContentSafetyHook {
    @Override public String sanitizeInput(String text) { return text; }
    @Override public String sanitizeOutput(String text) { return text; }
}

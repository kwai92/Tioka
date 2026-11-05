package com.tioka.system.api.spi;

public interface ContentSafetyHook {
    String sanitizeInput(String text);
    String sanitizeOutput(String text);
}

package com.tioka.system.application.audit;

import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 审计 details 打码：password / secret / token / apiKey 一律 {@code ***}（GOV-02）。
 */
public final class AuditRedaction {
    private static final Set<String> SENSITIVE = Set.of(
            "password", "secret", "token", "apikey", "api_key", "accesstoken", "refreshtoken");

    private AuditRedaction() {}

    public static boolean isSensitiveName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String n = name.replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
        return SENSITIVE.contains(n) || n.endsWith("password") || n.endsWith("secret")
                || n.endsWith("token") || n.endsWith("apikey");
    }

    @SuppressWarnings("unchecked")
    public static Object redact(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                out.put(key, isSensitiveName(key) ? "***" : redact(e.getValue()));
            }
            return out;
        }
        if (value instanceof Collection<?> col) {
            return col.stream().map(AuditRedaction::redact).toList();
        }
        if (value.getClass().isRecord()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (RecordComponent c : value.getClass().getRecordComponents()) {
                try {
                    Object v = c.getAccessor().invoke(value);
                    out.put(c.getName(), isSensitiveName(c.getName()) ? "***" : redact(v));
                } catch (ReflectiveOperationException ignored) {
                    out.put(c.getName(), "***");
                }
            }
            return out;
        }
        return value;
    }

    public static Map<String, Object> pickAndRedact(Object source, String[] include) {
        if (source == null || include == null || include.length == 0) {
            return Map.of();
        }
        Map<String, Object> raw;
        if (source instanceof Map<?, ?> map) {
            raw = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                raw.put(String.valueOf(e.getKey()), e.getValue());
            }
        } else if (source.getClass().isRecord()) {
            raw = new LinkedHashMap<>();
            for (RecordComponent c : source.getClass().getRecordComponents()) {
                try {
                    raw.put(c.getName(), c.getAccessor().invoke(source));
                } catch (ReflectiveOperationException ignored) {
                    // skip
                }
            }
        } else {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String field : include) {
            if (field == null || field.isBlank() || !raw.containsKey(field)) {
                continue;
            }
            Object v = raw.get(field);
            out.put(field, isSensitiveName(field) ? "***" : redact(v));
        }
        return out;
    }
}

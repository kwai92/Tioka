package com.tioka.governance.application.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 规范化 JSON（排序键）并剔除凭证类字段；计算 content hash。
 */
public class ConfigSpecNormalizer {

    private static final Set<String> SECRET_KEYS = Set.of(
            "apikey", "api_key", "secret", "password", "token", "credential", "credentials",
            "accesskey", "access_key", "secretkey", "secret_key", "privatekey", "private_key");

    private final ObjectMapper sortedMapper;

    public ConfigSpecNormalizer(ObjectMapper objectMapper) {
        this.sortedMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public JsonNode stripAndNormalize(JsonNode source) {
        if (source == null || source.isNull()) {
            return sortedMapper.nullNode();
        }
        return sort(stripSecrets(source.deepCopy()));
    }

    public String toCanonicalJson(JsonNode normalized) {
        try {
            return sortedMapper.writeValueAsString(normalized);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize config spec", e);
        }
    }

    public String contentHash(JsonNode normalized) {
        try {
            byte[] bytes = toCanonicalJson(normalized).getBytes(StandardCharsets.UTF_8);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public JsonNode readTree(String json) {
        try {
            return sortedMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("invalid config json", e);
        }
    }

    private JsonNode stripSecrets(JsonNode node) {
        if (node == null || !node.isObject()) {
            if (node != null && node.isArray()) {
                ArrayNode arr = sortedMapper.createArrayNode();
                for (JsonNode child : node) {
                    arr.add(stripSecrets(child));
                }
                return arr;
            }
            return node;
        }
        ObjectNode out = sortedMapper.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            if (SECRET_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.set(key, stripSecrets(entry.getValue()));
        }
        return out;
    }

    private JsonNode sort(JsonNode node) {
        if (node == null || !node.isObject()) {
            if (node != null && node.isArray()) {
                ArrayNode arr = sortedMapper.createArrayNode();
                for (JsonNode child : node) {
                    arr.add(sort(child));
                }
                return arr;
            }
            return node;
        }
        TreeMap<String, JsonNode> ordered = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            ordered.put(entry.getKey(), sort(entry.getValue()));
        }
        ObjectNode out = sortedMapper.createObjectNode();
        ordered.forEach(out::set);
        return out;
    }
}

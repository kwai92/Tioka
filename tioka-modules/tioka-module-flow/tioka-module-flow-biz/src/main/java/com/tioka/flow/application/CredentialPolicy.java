package com.tioka.flow.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** R5-06：节点 config 禁止明文 apiKey/token/password，仅允许 *Ref。 */
public final class CredentialPolicy {
    private static final Set<String> FORBIDDEN_PLAIN = Set.of("apikey", "token", "password", "secret", "authorization");

    private CredentialPolicy() {}

    public static void assertNoWildKeys(Map<String, Object> graph) {
        if (graph == null) return;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.getOrDefault("nodes", List.of());
        for (Map<String, Object> node : nodes) {
            Object cfg = node.get("config");
            if (cfg instanceof Map<?, ?> map) {
                assertConfigClean(String.valueOf(node.getOrDefault("id", "?")), map);
            }
        }
    }

    public static void assertConfigClean(String nodeId, Map<?, ?> config) {
        for (Map.Entry<?, ?> e : config.entrySet()) {
            String key = String.valueOf(e.getKey());
            String lower = key.toLowerCase();
            if (lower.endsWith("ref")) continue;
            if (FORBIDDEN_PLAIN.contains(lower)) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "节点 " + nodeId + " 禁止明文凭据字段 '" + key + "'，请使用 " + key + "Ref / secretRef");
            }
            if (e.getValue() instanceof Map<?, ?> nested) {
                assertConfigClean(nodeId, nested);
            }
        }
    }

    /** apiKeyRef / secretRef 必须像 SecretStore 引用（namespace::key）。 */
    public static void assertSecretRef(String field, String ref) {
        if (ref == null || ref.isBlank()) return;
        if (!ref.contains("::")) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    field + " 必须为 SecretStore 引用（namespace::key），收到: " + ref);
        }
    }
}

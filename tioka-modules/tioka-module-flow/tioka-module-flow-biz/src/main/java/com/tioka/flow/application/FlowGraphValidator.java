package com.tioka.flow.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;

import java.util.*;

public final class FlowGraphValidator {
    private FlowGraphValidator() {}

    public static void validate(Map<String, Object> graph, Collection<String> knownTypes) {
        if (graph == null || !graph.containsKey("nodes")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "图缺少 nodes");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.getOrDefault("nodes", List.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) graph.getOrDefault("edges", List.of());

        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> node : nodes) {
            String id = String.valueOf(node.getOrDefault("id", ""));
            if (id.isBlank()) throw new BizException(ErrorCode.BAD_REQUEST, "节点缺少 id");
            if (!ids.add(id)) throw new BizException(ErrorCode.BAD_REQUEST, "重复节点 id: " + id);
            String type = String.valueOf(node.get("type"));
            if (!knownTypes.contains(type)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "未知节点类型: " + type);
            }
            Object cfg = node.get("config");
            if (cfg instanceof Map<?, ?> map) {
                CredentialPolicy.assertConfigClean(id, map);
                Object apiKeyRef = map.get("apiKeyRef");
                if (apiKeyRef != null) CredentialPolicy.assertSecretRef("apiKeyRef", String.valueOf(apiKeyRef));
                Object secretRef = map.get("secretRef");
                if (secretRef != null) CredentialPolicy.assertSecretRef("secretRef", String.valueOf(secretRef));
            }
        }

        for (Map<String, Object> e : edges) {
            String from = String.valueOf(e.get("from"));
            String to = String.valueOf(e.get("to"));
            if (!ids.contains(from) || !ids.contains(to)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "悬空边: " + from + " -> " + to);
            }
        }

        detectCycle(ids, edges);
        CredentialPolicy.assertNoWildKeys(graph);
    }

    private static void detectCycle(Set<String> ids, List<Map<String, Object>> edges) {
        Map<String, List<String>> adj = new HashMap<>();
        for (String id : ids) adj.put(id, new ArrayList<>());
        for (Map<String, Object> e : edges) {
            adj.get(String.valueOf(e.get("from"))).add(String.valueOf(e.get("to")));
        }
        Set<String> visiting = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (String id : ids) {
            if (dfsCycle(id, adj, visiting, visited)) {
                throw new BizException(ErrorCode.BAD_REQUEST, "图存在环");
            }
        }
    }

    private static boolean dfsCycle(String cur, Map<String, List<String>> adj,
                                   Set<String> visiting, Set<String> visited) {
        if (visited.contains(cur)) return false;
        if (!visiting.add(cur)) return true;
        for (String next : adj.getOrDefault(cur, List.of())) {
            if (dfsCycle(next, adj, visiting, visited)) return true;
        }
        visiting.remove(cur);
        visited.add(cur);
        return false;
    }
}

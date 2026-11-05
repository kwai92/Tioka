package com.tioka.flow.application.exec;

import com.tioka.knowledge.api.KnowledgeSearchApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class RetrieveNodeExecutor implements NodeExecutor {
    private final ObjectProvider<KnowledgeSearchApi> knowledgeSearch;

    public RetrieveNodeExecutor(ObjectProvider<KnowledgeSearchApi> knowledgeSearch) {
        this.knowledgeSearch = knowledgeSearch;
    }

    @Override
    public String type() { return "Retrieve"; }

    @Override
    public Object execute(NodeExecContext ctx, Map<String, Object> node, Object input) {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) node.getOrDefault("config", Map.of());
        String kbId = firstNonBlank(config.get("kbId"), mapGet(input, "kbId"));
        String query = firstNonBlank(config.get("query"), mapGet(input, "query"), mapGet(input, "text"));
        int topK = 3;
        Object topKObj = config.getOrDefault("topK", mapGet(input, "topK"));
        if (topKObj instanceof Number n) topK = n.intValue();
        else if (topKObj != null) {
            try { topK = Integer.parseInt(String.valueOf(topKObj)); } catch (NumberFormatException ignored) {}
        }

        KnowledgeSearchApi api = knowledgeSearch.getIfAvailable();
        if (api == null) {
            return Map.of("error", "KnowledgeSearchApi unavailable", "kbId", kbId == null ? "" : kbId,
                    "query", query == null ? "" : query, "hits", List.of());
        }
        if (kbId == null || kbId.isBlank()) {
            return Map.of("error", "kbId required", "hits", List.of());
        }
        List<KnowledgeSearchApi.Hit> hits = api.searchKnowledge(kbId, query == null ? "" : query, topK);
        List<Map<String, Object>> mapped = hits.stream().map(KnowledgeSearchApi.Hit::toMap).toList();
        return Map.of("kbId", kbId, "query", query == null ? "" : query, "hits", mapped);
    }

    private static Object mapGet(Object input, String key) {
        if (input instanceof Map<?, ?> m) return m.get(key);
        return null;
    }

    private static String firstNonBlank(Object... values) {
        for (Object v : values) {
            if (v != null && !String.valueOf(v).isBlank()) return String.valueOf(v);
        }
        return null;
    }
}

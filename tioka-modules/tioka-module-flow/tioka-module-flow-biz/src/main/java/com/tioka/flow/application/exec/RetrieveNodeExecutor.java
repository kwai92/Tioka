package com.tioka.flow.application.exec;

import com.tioka.knowledge.api.KnowledgeSearchApi;
import com.tioka.runtime.api.grant.DelegationGrant;
import com.tioka.runtime.api.grant.GrantService;
import com.tioka.flow.application.publish.FlowCapabilityChecker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Flow 知识检索节点。DAT-04：调用方授予存在时，只允许检索授予内的知识库
 * （FlowGrantIT#nodesUseCallerGrant）；无授予（API 根路径旧口径）保持原行为。
 */
@Component
public class RetrieveNodeExecutor implements NodeExecutor {
    private final ObjectProvider<KnowledgeSearchApi> knowledgeSearch;
    private final ObjectProvider<GrantService> grants;

    public RetrieveNodeExecutor(ObjectProvider<KnowledgeSearchApi> knowledgeSearch,
                                ObjectProvider<GrantService> grants) {
        this.knowledgeSearch = knowledgeSearch;
        this.grants = grants;
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

        // DAT-04：kbId 必须在调用方授予内（grant 存在时）
        DelegationGrant grant = resolveGrant(ctx);
        if (grant != null && !FlowCapabilityChecker.resourceGranted(grant, "kb", kbId)) {
            return Map.of("kbId", kbId == null ? "" : kbId,
                    "query", query == null ? "" : query,
                    "hits", List.of(),
                    "error", "kb 不在调用方授予内: " + kbId);
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

    private DelegationGrant resolveGrant(NodeExecContext ctx) {
        if (ctx.grantId() == null || ctx.grantId().isBlank()) {
            return null;
        }
        GrantService gs = grants.getIfAvailable();
        if (gs == null) {
            return null;
        }
        try {
            return gs.get(ctx.grantId());
        } catch (RuntimeException e) {
            return null;
        }
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

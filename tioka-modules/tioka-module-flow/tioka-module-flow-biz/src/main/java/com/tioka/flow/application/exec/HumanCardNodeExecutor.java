package com.tioka.flow.application.exec;

import com.tioka.orchestration.api.OrchApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class HumanCardNodeExecutor implements NodeExecutor {
    private final ObjectProvider<OrchApi> orchApi;

    public HumanCardNodeExecutor(ObjectProvider<OrchApi> orchApi) {
        this.orchApi = orchApi;
    }

    @Override
    public String type() { return "HumanCard"; }

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(NodeExecContext ctx, Map<String, Object> node, Object input) {
        Map<String, Object> config = (Map<String, Object>) node.getOrDefault("config", Map.of());
        String title = String.valueOf(config.getOrDefault("title", "Human attention"));
        Map<String, Object> card = config.get("card") instanceof Map<?, ?> m
                ? (Map<String, Object>) m
                : Map.of("input", input == null ? Map.of() : input);

        OrchApi orch = orchApi.getIfAvailable();
        String attentionId;
        if (orch != null) {
            OrchApi.AttentionView view = orch.openAttention(ctx.runId(), title, card);
            attentionId = view.id();
        } else {
            attentionId = "attn_local_" + ctx.runId();
        }
        // 最小：记录 attentionId 并继续（也可标 SUSPENDED）
        ctx.markSuspended(attentionId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attentionId", attentionId);
        out.put("title", title);
        out.put("input", input == null ? Map.of() : input);
        return out;
    }
}

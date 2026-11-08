package com.tioka.flow.application.exec;

import com.tioka.orchestration.api.OrchAttentionApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class HumanCardNodeExecutor implements NodeExecutor {
    private final ObjectProvider<OrchAttentionApi> orchAttention;

    public HumanCardNodeExecutor(ObjectProvider<OrchAttentionApi> orchAttention) {
        this.orchAttention = orchAttention;
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

        OrchAttentionApi orch = orchAttention.getIfAvailable();
        String attentionId;
        if (orch != null) {
            OrchAttentionApi.AttentionView view = orch.openAttention(ctx.runId(), title, card);
            attentionId = view.id();
        } else {
            attentionId = "attn_local_" + ctx.runId();
        }
        ctx.markSuspended(attentionId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attentionId", attentionId);
        out.put("title", title);
        out.put("input", input == null ? Map.of() : input);
        return out;
    }
}

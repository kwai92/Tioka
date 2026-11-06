package com.tioka.flow.application.exec;

import com.tioka.framework.id.Ids;
import com.tioka.framework.security.Principal;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.model.api.gateway.ModelPurpose;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FLW-02：LLM 节点仅经 {@link ModelGateway}；节点配置用 {@code routingPlanId}
 *（空则 FLOW 默认方案），禁止 baseUrl/apiKey/model 直连。
 */
@Component
public class LlmNodeExecutor implements NodeExecutor {
    private final ObjectProvider<ModelGateway> modelGateway;

    public LlmNodeExecutor(ObjectProvider<ModelGateway> modelGateway) {
        this.modelGateway = modelGateway;
    }

    @Override
    public String type() { return "LLM"; }

    @Override
    public Object execute(NodeExecContext ctx, Map<String, Object> node, Object input) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) node.getOrDefault("config", Map.of());
        String routingPlanId = config.get("routingPlanId") == null
                ? null : String.valueOf(config.get("routingPlanId"));
        if (routingPlanId != null && routingPlanId.isBlank()) {
            routingPlanId = null;
        }
        String prompt = extractPrompt(input);
        List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", prompt));

        ModelGateway gateway = modelGateway.getIfAvailable();
        if (gateway == null) {
            return Map.of("content", "mock-llm:" + prompt, "model", "mock", "mock", true);
        }

        Principal principal = ctx.runAs();
        String nodeRunId = ctx.nodeRunId();
        if (nodeRunId == null || nodeRunId.isBlank()) {
            nodeRunId = Ids.newId("fnr");
        }
        ModelCallContext callCtx = new ModelCallContext(
                principal, ctx.runId(), nodeRunId, null, routingPlanId, ModelPurpose.FLOW);
        ModelGateway.ChatResult result = gateway.chat(
                callCtx,
                new ModelGateway.ChatRequest(messages, List.of(), null, null),
                d -> {});
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", result.content());
        out.put("model", result.modelRef());
        out.put("bindingId", result.bindingId());
        out.put("promptTokens", result.promptTokens());
        out.put("completionTokens", result.completionTokens());
        out.put("nodeRunId", nodeRunId);
        return out;
    }

    private static String extractPrompt(Object input) {
        if (input == null) return "";
        if (input instanceof Map<?, ?> m) {
            if (m.containsKey("prompt")) return String.valueOf(m.get("prompt"));
            if (m.containsKey("text")) return String.valueOf(m.get("text"));
            if (m.containsKey("content")) return String.valueOf(m.get("content"));
        }
        return String.valueOf(input);
    }
}

package com.tioka.flow.application.exec;

import com.tioka.flow.application.CredentialPolicy;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.model.api.gateway.ModelPurpose;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MH-04：LLM 节点经 {@link ModelGateway}；FLW-02 再把直连字段改为 routingPlanId。
 */
@Component
public class LlmNodeExecutor implements NodeExecutor {
    private final ObjectProvider<ModelGateway> modelGateway;
    private final ObjectProvider<PrincipalHolder> principals;

    public LlmNodeExecutor(ObjectProvider<ModelGateway> modelGateway,
                           ObjectProvider<PrincipalHolder> principals) {
        this.modelGateway = modelGateway;
        this.principals = principals;
    }

    @Override
    public String type() { return "LLM"; }

    @Override
    public Object execute(NodeExecContext ctx, Map<String, Object> node, Object input) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) node.getOrDefault("config", Map.of());
        String apiKeyRef = config.get("apiKeyRef") == null ? null : String.valueOf(config.get("apiKeyRef"));
        if (apiKeyRef != null && !apiKeyRef.isBlank()) {
            CredentialPolicy.assertSecretRef("apiKeyRef", apiKeyRef);
        }
        String routingPlanId = config.get("routingPlanId") == null ? null : String.valueOf(config.get("routingPlanId"));
        if (routingPlanId != null && routingPlanId.isBlank()) {
            routingPlanId = null;
        }
        String nodeId = node.get("id") == null ? null : String.valueOf(node.get("id"));
        String prompt = extractPrompt(input);
        List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", prompt));

        ModelGateway gateway = modelGateway.getIfAvailable();
        if (gateway == null) {
            return Map.of("content", "mock-llm:" + prompt, "model", "mock", "mock", true);
        }
        Principal principal = principals.getIfAvailable() == null
                ? SystemPrincipals.system()
                : principals.getIfAvailable().find().orElse(SystemPrincipals.system());
        ModelCallContext callCtx = new ModelCallContext(
                principal, ctx.runId(), nodeId, null, routingPlanId, ModelPurpose.FLOW);
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

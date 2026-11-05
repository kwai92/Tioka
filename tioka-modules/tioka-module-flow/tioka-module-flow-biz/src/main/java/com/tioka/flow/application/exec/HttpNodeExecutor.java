package com.tioka.flow.application.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.flow.application.CredentialPolicy;
import com.tioka.flow.infrastructure.FlowSecretBridge;
import com.tioka.system.api.spi.EgressGuard;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class HttpNodeExecutor implements NodeExecutor {
    private final ObjectProvider<FlowSecretBridge> secrets;
    private final ObjectProvider<EgressGuard> egressGuard;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public HttpNodeExecutor(ObjectProvider<FlowSecretBridge> secrets,
                            ObjectProvider<EgressGuard> egressGuard,
                            ObjectMapper mapper) {
        this.secrets = secrets;
        this.egressGuard = egressGuard;
        this.mapper = mapper;
    }

    @Override
    public String type() { return "HTTP"; }

    @Override
    public Object execute(NodeExecContext ctx, Map<String, Object> node, Object input) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) node.getOrDefault("config", Map.of());
        String url = String.valueOf(config.getOrDefault("url", ""));
        if (url.isBlank() || "null".equals(url)) {
            return Map.of("skipped", true, "reason", "url empty");
        }
        String method = String.valueOf(config.getOrDefault("method", "GET")).toUpperCase();
        String apiKeyRef = config.get("apiKeyRef") != null ? String.valueOf(config.get("apiKeyRef"))
                : (config.get("secretRef") != null ? String.valueOf(config.get("secretRef")) : null);
        if (apiKeyRef != null && !apiKeyRef.isBlank()) {
            CredentialPolicy.assertSecretRef("apiKeyRef", apiKeyRef);
        }

        EgressGuard guard = egressGuard.getIfAvailable();
        if (guard != null) guard.assertAllowed(url);

        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15));
        if (apiKeyRef != null && !apiKeyRef.isBlank()) {
            FlowSecretBridge bridge = secrets.getIfAvailable();
            if (bridge == null) throw new IllegalStateException("FlowSecretBridge unavailable");
            String token = bridge.resolve(apiKeyRef);
            b.header("Authorization", "Bearer " + token);
        }
        if ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method)) {
            String body = input == null ? "{}" : String.valueOf(input instanceof String s ? s : toJsonish(input));
            b.method(method, HttpRequest.BodyPublishers.ofString(body));
            b.header("Content-Type", "application/json");
        } else {
            b.GET();
        }
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", resp.statusCode());
        out.put("body", resp.body());
        return out;
    }

    private String toJsonish(Object input) {
        if (input instanceof Map || input instanceof java.util.List) {
            try {
                return mapper.writeValueAsString(input);
            } catch (Exception e) {
                throw new com.tioka.framework.error.BizException(
                        com.tioka.framework.error.ErrorCode.BAD_REQUEST, "HTTP body JSON 序列化失败");
            }
        }
        return String.valueOf(input);
    }
}

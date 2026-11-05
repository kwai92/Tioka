package com.tioka.flow.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.flow.infrastructure.FlowRepository;
import com.tioka.flow.infrastructure.FlowSecretBridge;
import com.tioka.flow.infrastructure.persistence.FlowToolPubDO;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.runtime.api.spi.ToolProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FlowPublicationService implements ToolProvider {
    private final FlowService flowService;
    private final FlowRepository repo;
    private final ObjectProvider<FlowSecretBridge> secretBridge;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String webhookSecretRef;
    private final String webhookSecretFallback;

    public FlowPublicationService(FlowService flowService,
                                  FlowRepository repo,
                                  ObjectProvider<FlowSecretBridge> secretBridge,
                                  @Value("${tioka.flow.webhook.secret-ref:flow-webhook::hmac}") String webhookSecretRef,
                                  @Value("${tioka.flow.webhook.secret:gate-test-secret}") String webhookSecretFallback) {
        this.flowService = flowService;
        this.repo = repo;
        this.secretBridge = secretBridge;
        this.webhookSecretRef = webhookSecretRef;
        this.webhookSecretFallback = webhookSecretFallback;
    }

    public String publishAsTool(String versionId, String toolName) {
        String name = (toolName == null || toolName.isBlank())
                ? "flow.run:" + versionId
                : toolName;
        repo.upsertToolPub(name, versionId);
        return name;
    }

    public Map<String, Object> invokeInternalApi(String versionId, Map<String, Object> input) {
        var run = flowService.run(versionId, input == null ? Map.of() : input);
        return toRunPayload(run);
    }

    public void verifyWebhook(String signature, String payload) {
        if (signature == null || !signature.startsWith("sha256=")) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "Webhook 验签失败");
        }
        String expectedHex = signature.substring("sha256=".length()).trim();
        String secret = resolveWebhookSecret();
        String actualHex = hmacSha256Hex(secret, payload == null ? "" : payload);
        if (!constantTimeEquals(expectedHex, actualHex)) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "Webhook 验签失败");
        }
    }

    private String resolveWebhookSecret() {
        FlowSecretBridge bridge = secretBridge.getIfAvailable();
        if (bridge != null && webhookSecretRef != null && webhookSecretRef.contains("::")) {
            String fromStore = bridge.resolveOrNull(webhookSecretRef);
            if (fromStore != null && !fromStore.isBlank()) return fromStore;
        }
        if (webhookSecretFallback != null && !webhookSecretFallback.isBlank()) {
            return webhookSecretFallback;
        }
        throw new BizException(ErrorCode.UNAUTHORIZED, "Webhook secret 未配置");
    }

    public static String hmacSha256Hex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        if (x.length != y.length) return false;
        int r = 0;
        for (int i = 0; i < x.length; i++) r |= x[i] ^ y[i];
        return r == 0;
    }

    private Map<String, Object> toRunPayload(FlowService.FlowRun run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", run.id());
        m.put("status", run.status());
        m.put("output", run.output());
        m.put("trace", run.trace());
        return m;
    }

    @Override
    public String name() { return "flow-publication"; }

    @Override
    public List<ToolDescriptor> listTools(String agentVersionId) {
        return repo.listToolPubs().stream()
                .map(r -> new ToolDescriptor(
                        r.getToolName(),
                        "Flow-as-Tool publication for version " + r.getVersionId(),
                        Map.of(
                                "type", "object",
                                "properties", Map.of("input", Map.of("type", "object")),
                                "additionalProperties", true
                        )))
                .toList();
    }

    @Override
    public ToolResult invoke(String toolName, Map<String, Object> arguments) {
        FlowToolPubDO pub = repo.findToolPub(toolName);
        if (pub == null && toolName != null && toolName.startsWith("flow.run:")) {
            // 允许直接按规范名查找
            pub = repo.findToolPub(toolName);
        }
        if (pub == null) return new ToolResult(false, "unknown flow tool");
        var run = flowService.run(pub.getVersionId(), arguments == null ? Map.of() : arguments);
        try {
            return new ToolResult(true, mapper.writeValueAsString(toRunPayload(run)));
        } catch (Exception e) {
            return new ToolResult(true, run.id() + ":" + run.status());
        }
    }
}

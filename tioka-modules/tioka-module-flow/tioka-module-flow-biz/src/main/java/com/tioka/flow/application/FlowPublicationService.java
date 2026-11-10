package com.tioka.flow.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.flow.application.publish.CapabilityCollector;
import com.tioka.flow.api.FlowInvokeApi;
import com.tioka.flow.infrastructure.FlowRepository;
import com.tioka.flow.infrastructure.FlowSecretBridge;
import com.tioka.flow.infrastructure.persistence.FlowPublicationDO;
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
public class FlowPublicationService implements ToolProvider, FlowInvokeApi {
    private final FlowService flowService;
    private final FlowRepository repo;
    private final ObjectProvider<FlowSecretBridge> secretBridge;
    /** DAT-04：启动前授予检查（runtime-api GrantService；缺失 = 旧路径不过滤）。 */
    private final ObjectProvider<com.tioka.runtime.api.grant.GrantService> grants;
    private final ObjectMapper mapper;
    private final String webhookSecretRef;
    private final String webhookSecretFallback;

    public FlowPublicationService(FlowService flowService,
                                  FlowRepository repo,
                                  ObjectProvider<FlowSecretBridge> secretBridge,
                                  ObjectProvider<com.tioka.runtime.api.grant.GrantService> grants,
                                  @Value("${tioka.flow.webhook.secret-ref:flow-webhook::hmac}") String webhookSecretRef,
                                  @Value("${tioka.flow.webhook.secret:gate-test-secret}") String webhookSecretFallback,
                                  ObjectMapper mapper) {
        this.mapper = mapper;
        this.flowService = flowService;
        this.repo = repo;
        this.secretBridge = secretBridge;
        this.grants = grants;
        this.webhookSecretRef = webhookSecretRef;
        this.webhookSecretFallback = webhookSecretFallback;
    }

    public String publishAsTool(String versionId, String toolName) {
        String name = (toolName == null || toolName.isBlank())
                ? "flow.run:" + versionId
                : toolName;
        // DAT-04：发布时计算 requiredCapabilities 写入发布记录（ADR-029 第 47 条）
        com.tioka.flow.infrastructure.persistence.FlowVersionDO ver = repo.findVersion(versionId);
        CapabilityCollector.RequiredCapabilities caps = ver == null
                ? new CapabilityCollector.RequiredCapabilities(List.of(), List.of(), List.of(), List.of())
                : CapabilityCollector.collect(parseGraph(ver.getGraphJson()));
        repo.upsertToolPub(name, versionId, CapabilityCollector.toJson(caps, mapper));
        return name;
    }

    @Override
    public Map<String, Object> invokeInternalApi(String versionId, Map<String, Object> input) {
        var run = flowService.run(versionId, input == null ? Map.of() : input);
        return toRunPayload(run);
    }

    /** FND-06 webhook：由 version 解析 draft（资源 ACL 主体）。 */
    public String requireDraftId(String versionId) {
        var ver = repo.findVersion(versionId);
        if (ver == null || !Boolean.TRUE.equals(ver.getPublished())) {
            throw new BizException(ErrorCode.NOT_FOUND, "Flow 未发布");
        }
        return ver.getDraftId();
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
        FlowPublicationDO pub = repo.findToolPub(toolName);
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

    /**
     * DAT-04：Agent 工具形态调用（带 ToolCallContext）——启动前检查
     * requiredCapabilities ⊆ 调用方 root grant，超授予启动即拒；Flow Run 写 grant_id。
     */
    @Override
    public ToolResult invoke(com.tioka.runtime.api.spi.ToolGateway.ToolCallContext ctx,
                             String agentVersionId, String toolName, Map<String, Object> arguments,
                             Map<String, Object> agentToolConfig) {
        FlowPublicationDO pub = repo.findToolPub(toolName == null ? "" : toolName);
        if (pub == null) {
            return new ToolResult(false, "unknown flow tool");
        }
        String grantId = ctx == null ? null : ctx.grantId();
        if (grantId != null && !grantId.isBlank()) {
            var grantService = this.grants.getIfAvailable();
            if (grantService != null) {
                try {
                    var grant = grantService.get(grantId);
                    CapabilityCollector.RequiredCapabilities caps =
                            CapabilityCollector.fromJson(pub.getRequiredCapabilitiesJson(), mapper);
                    com.tioka.flow.application.publish.FlowCapabilityChecker.assertWithinGrant(caps, grant);
                } catch (com.tioka.framework.error.BizException e) {
                    return new ToolResult(false, e.getMessage());
                } catch (RuntimeException e) {
                    return new ToolResult(false, "授予不可用: " + e.getMessage());
                }
            }
        }
        var run = flowService.run(pub.getVersionId(), arguments == null ? Map.of() : arguments, grantId);
        try {
            return new ToolResult(true, mapper.writeValueAsString(toRunPayload(run)));
        } catch (Exception e) {
            return new ToolResult(true, run.id() + ":" + run.status());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseGraph(String graphJson) {
        try {
            return mapper.readValue(graphJson == null ? "{}" : graphJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}

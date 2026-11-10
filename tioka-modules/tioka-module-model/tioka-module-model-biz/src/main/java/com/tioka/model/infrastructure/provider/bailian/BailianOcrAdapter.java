package com.tioka.model.infrastructure.provider.bailian;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.model.api.capability.OcrRequest;
import com.tioka.model.api.capability.OcrResult;
import com.tioka.model.infrastructure.provider.OcrEngine;
import com.tioka.system.api.spi.EgressClient;
import com.tioka.system.api.spi.SecretStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 百炼 OCR 适配器（DOC-02 / ADR-060 §4 m3-baseline：文字识别 = 百炼 {@code qwen-vl-ocr}）。
 * 调外部 HTTP 必须经 {@link EgressClient}（47-GL §1.8，HttpOAuthTokenClient 先例）。
 * <p>endpoint / 模型名配置化（{@code tioka.model.ocr.endpoint} / {@code tioka.model.ocr.model}）。
 * mock 模式或 endpoint 未配置且 mock 关闭时：mock 返回确定性桩行；未配置抛
 * {@code ocr_unavailable}（调用方将 OCR 队列任务标 FAILED）。本批不接真实百炼密钥。
 */
@Component
public class BailianOcrAdapter implements OcrEngine {

    private final EgressClient egress;
    private final SecretStore secretStore;
    private final ObjectMapper mapper;
    private final String endpoint;
    private final String defaultModel;
    private final boolean mock;

    public BailianOcrAdapter(EgressClient egress,
                             SecretStore secretStore,
                             ObjectMapper mapper,
                             @Value("${tioka.model.ocr.endpoint:}") String endpoint,
                             @Value("${tioka.model.ocr.model:qwen-vl-ocr}") String defaultModel,
                             @Value("${tioka.model.mock:true}") boolean mock) {
        this.egress = egress;
        this.secretStore = secretStore;
        this.mapper = mapper;
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.defaultModel = defaultModel;
        this.mock = mock;
    }

    @Override
    public OcrResult recognize(String baseUrl, String apiKeyRef, String model, OcrRequest request)
            throws Exception {
        String resolvedModel = model == null || model.isBlank() ? defaultModel : model;
        if (mock || isBlank(baseUrl)) {
            return stubResult(resolvedModel);
        }
        if (isBlank(endpoint)) {
            throw new BizException(ErrorCode.DEPENDENCY,
                    "ocr_unavailable: OCR endpoint 未配置（tioka.model.ocr.endpoint）");
        }
        String apiKey = resolveApiKey(apiKeyRef);
        ObjectNode body = mapper.createObjectNode();
        body.put("model", resolvedModel);
        ArrayNode messages = body.putArray("messages");
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        ArrayNode content = msg.putArray("content");
        ObjectNode img = content.addObject();
        img.put("type", "image_url");
        img.putObject("image_url").put("url",
                "data:image/png;base64," + Base64.getEncoder().encodeToString(request.imagePng()));
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = egress.send(req);
        if (resp.statusCode() >= 300) {
            throw new com.tioka.model.infrastructure.provider.ProviderHttpException(resp.statusCode(),
                    "bailian ocr error: " + resp.statusCode() + " " + resp.body(), resp.statusCode() >= 500, null);
        }
        return parse(resp.body(), resolvedModel);
    }

    /** 解析 OpenAI 兼容 vision 响应：content 文本按行输出，无框坐标（坐标参照缺失，仅 TEXT_OCR 第二意见用）。 */
    private OcrResult parse(String json, String model) throws Exception {
        JsonNode root = mapper.readTree(json);
        String text = root.path("choices").path(0).path("message").path("content").asText("");
        List<OcrResult.OcrLine> lines = new ArrayList<>();
        String[] parts = text.split("\n");
        for (int i = 0; i < parts.length; i++) {
            String line = parts[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            // 供应商自行渲染页面且未返回页尺寸的引擎只能产出无坐标行（ADR-061 §3 坐标换算）
            double y0 = (double) i / Math.max(1, parts.length);
            double y1 = (double) (i + 1) / Math.max(1, parts.length);
            lines.add(new OcrResult.OcrLine(line, 0, y0, 1, y1, 0.9d));
        }
        return new OcrResult(lines, null, model);
    }

    /** 确定性桩：单行占位文本，供离线联调。 */
    private static OcrResult stubResult(String model) {
        return new OcrResult(List.of(new OcrResult.OcrLine("【mock】OCR", 0, 0, 1, 1, 1.0d)), null, model);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank() || "mock://openai".equals(s);
    }

    private String resolveApiKey(String apiKeyRef) {
        if (apiKeyRef == null || apiKeyRef.isBlank()) {
            return "";
        }
        String ns = "model-provider";
        String key = apiKeyRef;
        if (apiKeyRef.contains("::")) {
            String[] parts = apiKeyRef.split("::", 2);
            ns = parts[0];
            key = parts[1];
        }
        String apiKey = secretStore.get(ns, key);
        return apiKey == null ? "" : apiKey;
    }
}

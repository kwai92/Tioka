package com.tioka.model.infrastructure.provider;

import com.tioka.model.api.capability.OcrRequest;
import com.tioka.model.api.capability.OcrResult;

/**
 * OCR 引擎（ADR-061 第 6 节 {@code model.vision}）：网关按绑定解析出的 baseUrl/apiKey/model
 * 调用具体适配器。适配器只负责「请求怎么发、结果怎么映射」；异步任务/转存/计量由平台统一做。
 */
public interface OcrEngine {

    OcrResult recognize(String baseUrl, String apiKeyRef, String model, OcrRequest request) throws Exception;
}

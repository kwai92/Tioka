package com.tioka.model.api.capability;

/**
 * OCR 能力请求（DOC-02 / ADR-061 第 6 节 {@code model.vision} 适配器）。
 * 输入为页渲染 PNG（200 DPI）；{@code dataLabel}/{@code spaceId} 随请求传递供路由与去向过滤使用。
 */
public record OcrRequest(
        /** 强制使用的模型绑定；null 用空间默认解析。 */
        String bindingId,
        byte[] imagePng,
        /** 文档数据标签（如 CONFIDENTIAL）；去向许可机制（ADR-040）启用后参与路由过滤。 */
        String dataLabel,
        /** 所属空间（workspaceId 权威口径）。 */
        String spaceId
) {}

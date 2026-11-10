package com.tioka.ontology.application.doc;

import com.tioka.ontology.api.docparse.ParsedLine;

import java.util.List;

/**
 * OCR 页识别出口（DOC-02）：文档层 → ModelGateway.ocr 的适配点。
 * 生产装配 {@link ModelGatewayOcrClient}；测试注入桩。失败抛 BizException
 * （原因含 {@code ocr_unavailable} 时 DocumentService 据此归因）。
 */
@FunctionalInterface
public interface OcrClient {

    /**
     * 识别一页（页渲染 PNG，200 DPI），返回 OCR 行（页内 0〜1 归一化框 + 置信度）。
     */
    List<ParsedLine> ocrPage(int pageNo, byte[] pagePng, String dataLabel, String spaceId);
}

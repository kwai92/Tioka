package com.tioka.ontology.application.doc;

import com.tioka.ontology.api.docparse.ParseContract;
import com.tioka.ontology.api.docparse.ParsedLine;
import com.tioka.ontology.api.docparse.ParsedRegion;

import java.util.List;

/**
 * ImageParser（DOC-01）：图片全部 OCR（每张一页）。
 */
public class ImageParser {

    private final OcrClient ocrClient;

    public ImageParser(OcrClient ocrClient) {
        this.ocrClient = ocrClient;
    }

    public List<ParsedLine> parse(int pageNo, byte[] imageBytes, String dataLabel, String spaceId) {
        if (ocrClient == null) {
            throw new IllegalStateException("ocr_unavailable: 图片页必须 OCR 且未装配 OCR");
        }
        List<ParsedLine> lines = ocrClient.ocrPage(pageNo, imageBytes, dataLabel, spaceId);
        List<ParsedLine> normalized = new java.util.ArrayList<>();
        for (ParsedLine l : lines) {
            List<ParsedRegion> regions = l.regions().isEmpty()
                    ? List.of(new ParsedRegion(pageNo, 0, 0, 1, 1))
                    : l.regions();
            normalized.add(new ParsedLine(pageNo, l.text(), regions, "PARA", null,
                    ParseContract.S_OCR, l.confidence()));
        }
        return normalized;
    }
}

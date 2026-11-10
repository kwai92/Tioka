package com.tioka.ontology.application.doc;

import com.tioka.ontology.api.docparse.ParseContract;
import com.tioka.ontology.api.docparse.ParsedLine;
import com.tioka.ontology.api.docparse.ParsedRegion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OcrMerger（DOC-02 合并规则，M3-03 §3 冻结）：IoU≥0.5 配对行用文字层；
 * OCR 独有行（签章日期）追加 source=MERGED；OCR 低置信随行携带。
 */
class OcrMergerTest {

    private static ParsedLine textLine(String text, double x0, double y0, double x1, double y1) {
        return new ParsedLine(1, text,
                List.of(new ParsedRegion(1, x0, y0, x1, y1)), "PARA", null,
                ParseContract.S_TEXT_LAYER, null);
    }

    private static ParsedLine ocrLine(String text, double x0, double y0, double x1, double y1,
                                      double confidence) {
        return new ParsedLine(1, text,
                List.of(new ParsedRegion(1, x0, y0, x1, y1)), "PARA", null,
                ParseContract.S_OCR, confidence);
    }

    @Test
    void overlappingLinesKeepTextLayerOnly() {
        List<ParsedLine> merged = OcrMerger.merge(
                List.of(textLine("租金为每月一万元。", 0.1, 0.2, 0.8, 0.3)),
                List.of(ocrLine("租金为每月一万元。", 0.11, 0.21, 0.79, 0.31, 0.95)));
        assertEquals(1, merged.size(), "重叠行只保留一份");
        assertEquals(ParseContract.S_TEXT_LAYER, merged.get(0).source());
        assertEquals("租金为每月一万元。", merged.get(0).text());
    }

    @Test
    void ocrOnlySignatureDateAppendedAsMerged() {
        List<ParsedLine> merged = OcrMerger.merge(
                List.of(textLine("甲方（盖章）：", 0.1, 0.8, 0.4, 0.9)),
                List.of(ocrLine("甲方（盖章）：", 0.1, 0.8, 0.4, 0.9, 0.9),
                        ocrLine("2026年1月1日", 0.5, 0.85, 0.9, 0.95, 0.72)));
        assertEquals(2, merged.size(), "文字层行 + OCR 独有签章日期");
        assertEquals(ParseContract.S_TEXT_LAYER, merged.get(0).source());
        assertEquals(ParseContract.S_MERGED, merged.get(1).source());
        assertEquals("2026年1月1日", merged.get(1).text());
        assertNotNull(merged.get(1).confidence());
        assertTrue(merged.get(1).confidence() < 0.8, "低置信随行携带，供 ocr_conf 落库");
    }

    @Test
    void textOnlyPageKeptAsIs() {
        List<ParsedLine> textLines = List.of(textLine("第一页正文。", 0, 0, 1, 0.1));
        List<ParsedLine> merged = OcrMerger.merge(textLines, List.of());
        assertEquals(1, merged.size());
        assertEquals(ParseContract.S_TEXT_LAYER, merged.get(0).source());
    }

    @Test
    void ocrOnlyPageUsedDirectly() {
        List<ParsedLine> merged = OcrMerger.merge(List.of(),
                List.of(ocrLine("扫描页文字", 0, 0, 1, 0.1, 0.98)));
        assertEquals(1, merged.size());
        assertEquals(ParseContract.S_OCR, merged.get(0).source());
    }

    @Test
    void mergedOutputSortedReadingOrder() {
        List<ParsedLine> merged = OcrMerger.merge(
                List.of(textLine("第二行", 0.1, 0.4, 0.8, 0.5)),
                List.of(ocrLine("第一行", 0.1, 0.1, 0.8, 0.2, 0.9),
                        ocrLine("第三行", 0.1, 0.7, 0.8, 0.8, 0.9)));
        assertEquals(3, merged.size());
        assertEquals("第一行", merged.get(0).text());
        assertEquals("第二行", merged.get(1).text());
        assertEquals("第三行", merged.get(2).text());
    }
}

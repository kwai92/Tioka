package com.tioka.model.api.capability;

import java.util.List;

/**
 * OCR 能力结果（DOC-02）：行文本、页内 0〜1 归一化框、置信度。
 */
public record OcrResult(
        List<OcrLine> lines,
        /** 实际使用的模型绑定。 */
        String bindingId,
        /** 实际使用的模型名。 */
        String modelRef
) {
    public OcrResult {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** @param bbox 页内 0〜1 归一化 [x0,y0,x1,y1] */
    public record OcrLine(String text, double x0, double y0, double x1, double y1, double confidence) {
        public OcrLine {
            if (x1 < x0) {
                double t = x0;
                x0 = x1;
                x1 = t;
            }
            if (y1 < y0) {
                double t = y0;
                y0 = y1;
                y1 = t;
            }
        }

        public List<Double> bbox() {
            return List.of(x0, y0, x1, y1);
        }
    }
}

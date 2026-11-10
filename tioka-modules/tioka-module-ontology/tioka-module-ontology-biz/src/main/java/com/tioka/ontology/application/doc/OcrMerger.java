package com.tioka.ontology.application.doc;

import com.tioka.ontology.api.docparse.ParseContract;
import com.tioka.ontology.api.docparse.ParsedLine;
import com.tioka.ontology.api.docparse.ParsedRegion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * OcrMerger（DOC-02 合并规则，M3-03 §3 冻结）：
 * <ol>
 *   <li>页只有 OCR：直接用 OCR 行。</li>
 *   <li>两者都有：按 bbox IoU ≥ 0.5 配对，配对行用文字层（文字层没有识别错误），
 *       OCR 独有行（印章文字、手写日期）追加，source = MERGED。</li>
 * </ol>
 * OCR 置信度随行携带；&lt; 0.8 的写入单元 ocr_conf。
 */
public final class OcrMerger {

    /** 配对阈值（冻结口径）。 */
    public static final double IOU_THRESHOLD = 0.5d;

    private OcrMerger() {
    }

    /**
     * @param textLines 文字层行（可为空）
     * @param ocrLines  OCR 行（可为空）
     * @return 合并后的行（按页内 y0 再 x0 排序；无坐标的行保持追加顺序在后）
     */
    public static List<ParsedLine> merge(List<ParsedLine> textLines, List<ParsedLine> ocrLines) {
        List<ParsedLine> text = textLines == null ? List.of() : textLines;
        List<ParsedLine> ocr = ocrLines == null ? List.of() : ocrLines;
        if (text.isEmpty()) {
            return List.copyOf(ocr);
        }
        if (ocr.isEmpty()) {
            return List.copyOf(text);
        }
        Set<Integer> pairedText = new LinkedHashSet<>();
        List<ParsedLine> ocrOnly = new ArrayList<>();
        for (ParsedLine o : ocr) {
            int bestIdx = -1;
            double bestIou = 0d;
            for (int i = 0; i < text.size(); i++) {
                if (pairedText.contains(i)) {
                    continue;
                }
                double iou = bestIou(o, text.get(i));
                if (iou >= IOU_THRESHOLD && iou > bestIou) {
                    bestIou = iou;
                    bestIdx = i;
                }
            }
            if (bestIdx >= 0) {
                pairedText.add(bestIdx);
            } else {
                // OCR 独有行：追加，source=MERGED
                ocrOnly.add(new ParsedLine(o.pageNo(), o.text(), o.regions(), o.kind(),
                        o.tableRef(), ParseContract.S_MERGED, o.confidence()));
            }
        }
        List<ParsedLine> out = new ArrayList<>(text);
        out.addAll(ocrOnly);
        return sortReadingOrder(out);
    }

    /** 行与行的重叠度：区域集合两两 IoU 取最大。 */
    static double bestIou(ParsedLine a, ParsedLine b) {
        double best = 0d;
        for (ParsedRegion ra : a.regions()) {
            for (ParsedRegion rb : b.regions()) {
                if (ra.pageNo() != rb.pageNo()) {
                    continue;
                }
                best = Math.max(best, ra.iou(rb));
            }
        }
        return best;
    }

    private static List<ParsedLine> sortReadingOrder(List<ParsedLine> lines) {
        List<ParsedLine> withBox = new ArrayList<>();
        List<ParsedLine> withoutBox = new ArrayList<>();
        for (ParsedLine l : lines) {
            if (l.regions().isEmpty()) {
                withoutBox.add(l);
            } else {
                withBox.add(l);
            }
        }
        withBox.sort(Comparator
                .comparingInt((ParsedLine l) -> l.regions().get(0).pageNo())
                .thenComparingDouble(l -> l.regions().get(0).y0())
                .thenComparingDouble(l -> l.regions().get(0).x0()));
        List<ParsedLine> out = new ArrayList<>(withBox);
        out.addAll(withoutBox);
        return out;
    }
}

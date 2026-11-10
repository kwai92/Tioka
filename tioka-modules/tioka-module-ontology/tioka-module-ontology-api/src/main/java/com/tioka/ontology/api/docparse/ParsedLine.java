package com.tioka.ontology.api.docparse;

import java.util.List;

/**
 * 解析器产出的「行」序列（M3-03 §2 解析流程第 3 步：{page, text, bbox?, kind, tableRef?, source}）。
 * 切分（UnitSegmenter）与条款树（ClauseTreeBuilder）以行为输入。
 */
public record ParsedLine(
        int pageNo,
        String text,
        List<ParsedRegion> regions,
        String kind,
        ParsedTableRef tableRef,
        String source,
        /** OCR 行置信度；非 OCR 来源为 null。 */
        Double confidence
) {
    public ParsedLine {
        regions = regions == null ? List.of() : List.copyOf(regions);
    }
}

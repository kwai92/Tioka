package com.tioka.ontology.api.doc;

/**
 * 文档单元（DOC-01）。C-3 批次补充 {@code pageNo}/{@code clausePath}/{@code normText}
 * （M3-03 §1 doc_unit 列对齐）。{@code unitId} 形如 {@code {docId}:{pageNo}:{seq}}。
 */
public record DocUnit(
        String unitId,
        int seq,
        int pageNo,
        String kind,
        String text,
        String normText,
        String clausePath,
        String section
) {}

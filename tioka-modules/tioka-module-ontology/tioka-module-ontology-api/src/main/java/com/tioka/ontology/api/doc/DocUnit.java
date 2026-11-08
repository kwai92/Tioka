package com.tioka.ontology.api.doc;

/**
 * 文档单元（DOC-01）。
 */
public record DocUnit(
        String unitId,
        int seq,
        String kind,
        String text,
        String section
) {}

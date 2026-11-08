package com.tioka.ontology.api.doc;

/**
 * 文档层文档视图（DOC-01）。
 */
public record DocumentView(
        String docId,
        String status,
        String fileName,
        String mimeType,
        String sha256
) {}

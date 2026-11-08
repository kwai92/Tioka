package com.tioka.ontology.api.doc;

/**
 * 文档入库文件引用（DOC-01）。
 */
public record FileRef(
        String storageKey,
        String fileName,
        String mimeType,
        String sha256
) {}

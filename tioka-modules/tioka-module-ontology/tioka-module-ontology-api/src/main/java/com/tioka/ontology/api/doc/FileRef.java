package com.tioka.ontology.api.doc;

/**
 * 文档入库文件引用（DOC-01）。C-3 批次补充空间归属与数据标签（去重键
 * {@code file_hash + parser_version + space_id} 需要 spaceId；dataLabel 缺省 CONFIDENTIAL）。
 */
public record FileRef(
        String storageKey,
        String fileName,
        String mimeType,
        String sha256,
        String spaceId,
        String dataLabel,
        String sourceSystem,
        String sourceRef
) {
    /** 兼容 C-3 之前的四参口径；spaceId 为空时视为默认空间。 */
    public FileRef(String storageKey, String fileName, String mimeType, String sha256) {
        this(storageKey, fileName, mimeType, sha256, null, null, null, null);
    }
}

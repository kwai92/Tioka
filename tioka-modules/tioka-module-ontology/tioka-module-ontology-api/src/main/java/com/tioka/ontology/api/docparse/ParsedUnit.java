package com.tioka.ontology.api.docparse;

import java.util.List;

/**
 * 契约 v1 块（ADR-060 §3.3，M3 核心子集字段）。文档层按 §6 映射到 doc_unit。
 * {@code blockId} 形如 {@code {docId}:{pageNo}:{seq}}，同一文件同一解析器版本下稳定。
 */
public record ParsedUnit(
        String blockId,
        int seq,
        String type,
        String role,
        String parentId,
        int level,
        String listLabel,
        String text,
        List<ParsedRegion> regions,
        String source,
        Double confidence,
        List<String> flags,
        ParsedTableRef tableRef,
        String section
) {
    public ParsedUnit {
        regions = regions == null ? List.of() : List.copyOf(regions);
        flags = flags == null ? List.of() : List.copyOf(flags);
    }
}

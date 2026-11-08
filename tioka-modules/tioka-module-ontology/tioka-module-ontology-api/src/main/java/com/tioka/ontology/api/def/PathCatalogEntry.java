package com.tioka.ontology.api.def;

import java.util.List;

/**
 * 路径目录条目：路径 → 类型、枚举、单位、主题（M3-03 §6）。
 */
public record PathCatalogEntry(
        String path,
        PathValueKind kind,
        List<String> enumValues,
        String unit,
        List<String> topics
) {
    public PathCatalogEntry {
        if (enumValues == null) {
            enumValues = List.of();
        }
        if (topics == null) {
            topics = List.of();
        }
    }
}

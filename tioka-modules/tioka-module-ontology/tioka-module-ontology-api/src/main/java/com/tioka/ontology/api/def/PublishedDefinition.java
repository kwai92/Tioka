package com.tioka.ontology.api.def;

import java.util.List;

/** 已发布（或草稿快照）的模型定义版本。 */
public record PublishedDefinition(
        String defKey,
        int version,
        String packKey,
        String packVersion,
        ModelDefinition definition,
        List<PathCatalogEntry> pathCatalog,
        String contentHash,
        DefStatus status
) {
}

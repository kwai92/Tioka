package com.tioka.ontology.api.doc;

import java.util.List;

/**
 * 条款树节点（DOC-01）。
 */
public record DocClause(
        String clauseId,
        String number,
        String title,
        List<String> unitIds,
        List<DocClause> children
) {}

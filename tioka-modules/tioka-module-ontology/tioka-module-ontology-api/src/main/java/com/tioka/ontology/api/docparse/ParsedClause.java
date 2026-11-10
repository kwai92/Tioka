package com.tioka.ontology.api.docparse;

import java.util.List;

/**
 * 契约 v1 条款树节点（M3-03 doc_clause 列对齐）：{@code clause_path}、父路径、标题、
 * 单元 seq 闭区间、BLANK/MODIFIED 状态与主题标签。
 */
public record ParsedClause(
        String clausePath,
        String parentPath,
        String title,
        int unitFrom,
        int unitTo,
        String status,
        List<String> topics
) {
    public ParsedClause {
        topics = topics == null ? List.of() : List.copyOf(topics);
    }
}

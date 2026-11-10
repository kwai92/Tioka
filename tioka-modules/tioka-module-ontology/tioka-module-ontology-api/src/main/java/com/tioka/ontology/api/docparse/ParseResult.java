package com.tioka.ontology.api.docparse;

import java.util.List;

/**
 * 契约 v1 解析结果（ADR-060 §3，M3 核心子集：页 / 块 / 条款树三类）。
 * 版面引擎与第二意见等其余字段 M4（PRS-01/02）补齐。
 */
public record ParseResult(
        String contract,
        String parseId,
        String fileHash,
        String parserVersion,
        String status,
        int pageCount,
        String language,
        List<ParsedUnit> units,
        List<ParsedClause> clauses,
        List<String> warnings
) {
    public ParseResult {
        if (contract == null || contract.isBlank()) {
            contract = ParseContract.CONTRACT;
        }
        units = units == null ? List.of() : List.copyOf(units);
        clauses = clauses == null ? List.of() : List.copyOf(clauses);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}

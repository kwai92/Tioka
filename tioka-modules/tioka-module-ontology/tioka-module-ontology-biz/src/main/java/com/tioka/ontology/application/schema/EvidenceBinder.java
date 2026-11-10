package com.tioka.ontology.application.schema;

import com.tioka.ontology.api.docparse.ParsedTableRef;
import com.tioka.ontology.domain.TextNormalizer;
import com.tioka.ontology.infrastructure.DocumentRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * EvidenceBinder（DOC-03，M3-03 §0 放 application/schema）：
 * {@code check(unitId, quote, visibleDocIds)} —— {@code TextNormalizer.normalize(quote)}
 * 必须是 {@code doc_unit.norm_text} 的子串；表格单元格允许跨同一行相邻单元格
 * （行内拼接后匹配）。失败三种：UNIT_NOT_FOUND / UNIT_NOT_VISIBLE / QUOTE_MISMATCH。
 */
public class EvidenceBinder {

    public enum Code { UNIT_NOT_FOUND, UNIT_NOT_VISIBLE, QUOTE_MISMATCH }

    public record Violation(Code code, String message) {}

    private final DocumentRepository repository;

    public EvidenceBinder(DocumentRepository repository) {
        this.repository = repository;
    }

    /**
     * @param unitId        引文单元 ID
     * @param quote         原文引用
     * @param visibleDocIds 本次运行可见的文档 ID（UNIT_NOT_VISIBLE 判据）
     * @return 通过返回 empty；失败返回违规
     */
    public Optional<Violation> check(String unitId, String quote, List<String> visibleDocIds) {
        Optional<DocumentRepository.UnitRow> unit = repository.findUnit(unitId);
        if (unit.isEmpty()) {
            return Optional.of(new Violation(Code.UNIT_NOT_FOUND, "unit 不存在: " + unitId));
        }
        DocumentRepository.UnitRow row = unit.get();
        if (visibleDocIds == null || !visibleDocIds.contains(row.docId)) {
            return Optional.of(new Violation(Code.UNIT_NOT_VISIBLE,
                    "unit 不属于本次运行的文档: " + row.docId));
        }
        String normalizedQuote = TextNormalizer.normalize(quote);
        if (normalizedQuote.isEmpty()) {
            return Optional.of(new Violation(Code.QUOTE_MISMATCH, "quote 归一化后为空"));
        }
        if (row.normText != null && row.normText.contains(normalizedQuote)) {
            return Optional.empty();
        }
        // 表格单元格：同一行相邻单元格行内拼接后匹配（M3-03 §4）
        if (row.tableRef != null) {
            String rowText = sameRowConcat(row);
            if (rowText.contains(normalizedQuote)) {
                return Optional.empty();
            }
        }
        return Optional.of(new Violation(Code.QUOTE_MISMATCH,
                "quote 不是 unit norm_text 的子串"));
    }

    /** 同一 tableId 同一 row 的全部单元格 normText 按 col 升序拼接（无分隔）。 */
    private String sameRowConcat(DocumentRepository.UnitRow origin) {
        ParsedTableRef ref = origin.tableRef;
        List<DocumentRepository.UnitRow> cells = repository
                .unitsOfDocument(origin.docId).stream()
                .filter(u -> u.tableRef != null
                        && u.tableRef.tableId() == ref.tableId()
                        && u.tableRef.row() == ref.row())
                .sorted(Comparator.comparingInt(u -> u.tableRef.col()))
                .toList();
        StringBuilder sb = new StringBuilder();
        for (DocumentRepository.UnitRow u : cells) {
            sb.append(u.normText == null ? "" : u.normText);
        }
        return sb.toString();
    }
}

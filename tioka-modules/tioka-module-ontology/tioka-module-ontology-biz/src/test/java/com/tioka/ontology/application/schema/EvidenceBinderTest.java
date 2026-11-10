package com.tioka.ontology.application.schema;

import com.tioka.ontology.api.docparse.ParsedTableRef;
import com.tioka.ontology.domain.TextNormalizer;
import com.tioka.ontology.infrastructure.DocumentRepository;
import com.tioka.ontology.infrastructure.InMemoryDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EvidenceBinder（DOC-03）：全半角、空格、换行差异通过；改一个数字不通过；三错误码。
 */
class EvidenceBinderTest {

    private DocumentRepository repository;
    private EvidenceBinder binder;

    @BeforeEach
    void setUp() {
        repository = new InMemoryDocumentRepository();
        seed();
        binder = new EvidenceBinder(repository);
    }

    private void seed() {
        List<DocumentRepository.UnitRow> units = new ArrayList<>();
        units.add(unit("doc1:1:1", 1, 1, "PARA", "租金为每月１０，０００元（大写：壹万元整）。", null, null));
        units.add(unit("doc1:1:2", 1, 2, "PARA",
                "乙方应于每月五日前\n支付租金至甲方指定账户。", null, null));
        units.add(unit("doc1:2:3", 2, 3, "CELL", "月租金", null, new ParsedTableRef(1, 0, 0)));
        units.add(unit("doc1:2:4", 2, 4, "CELL", "10000元/月", null, new ParsedTableRef(1, 0, 1)));
        units.add(unit("doc1:2:5", 2, 5, "CELL", "押金", null, new ParsedTableRef(1, 1, 0)));
        units.add(unit("doc1:2:6", 2, 6, "CELL", "20000元", null, new ParsedTableRef(1, 1, 1)));

        DocumentRepository.DocumentRow doc = new DocumentRepository.DocumentRow(
                "doc1", "hash-doc1", "1.0.0", "application/pdf", "doc1.pdf",
                null, null, "k/doc1", null, "ws1");
        doc.parseStatus = "PENDING";
        repository.persistParsed(doc,
                List.of(new DocumentRepository.PageRow("doc1", 1, "TEXT", "text", 100.0, null),
                        new DocumentRepository.PageRow("doc1", 2, "MERGED", "merged", 12.0, "qwen-vl-ocr")),
                units,
                List.of());
    }

    private DocumentRepository.UnitRow unit(String unitId, int pageNo, int seq, String kind,
                                            String text, String clausePath, ParsedTableRef tableRef) {
        DocumentRepository.UnitRow u = new DocumentRepository.UnitRow();
        u.unitId = unitId;
        u.docId = "doc1";
        u.pageNo = pageNo;
        u.seq = seq;
        u.kind = kind;
        u.text = text;
        u.normText = TextNormalizer.normalize(text);
        u.clausePath = clausePath;
        u.tableRef = tableRef;
        return u;
    }

    @Test
    void fullWidthSpaceAndNewlineDifferencesPass() {
        // 原文全角数字/全角逗号 → 引文用半角
        Optional<EvidenceBinder.Violation> v1 = binder.check("doc1:1:1", "租金为每月10,000元",
                List.of("doc1"));
        assertTrue(v1.isEmpty(), () -> v1.map(Object::toString).orElse(""));
        // 空格差异 + 换行差异
        Optional<EvidenceBinder.Violation> v2 = binder.check("doc1:1:2",
                "乙方应于每月五日前 支付租金至甲方指定账户。", List.of("doc1"));
        assertTrue(v2.isEmpty(), () -> v2.map(Object::toString).orElse(""));
    }

    @Test
    void changedDigitFails() {
        Optional<EvidenceBinder.Violation> v = binder.check("doc1:1:1", "租金为每月10,001元",
                List.of("doc1"));
        assertTrue(v.isPresent());
        assertEquals(EvidenceBinder.Code.QUOTE_MISMATCH, v.get().code());
    }

    @Test
    void unitNotFound() {
        Optional<EvidenceBinder.Violation> v = binder.check("nope:9:9", "任意", List.of("doc1"));
        assertEquals(EvidenceBinder.Code.UNIT_NOT_FOUND, v.get().code());
    }

    @Test
    void unitNotVisible() {
        Optional<EvidenceBinder.Violation> v = binder.check("doc1:1:1", "租金", List.of("doc-other"));
        assertEquals(EvidenceBinder.Code.UNIT_NOT_VISIBLE, v.get().code());
    }

    @Test
    void tableCellQuoteMatchesAcrossSameRowAdjacentCells() {
        // 引文跨同行相邻单元格：「月租金10000元/月」
        Optional<EvidenceBinder.Violation> v = binder.check("doc1:2:3", "月租金10000元/月",
                List.of("doc1"));
        assertTrue(v.isEmpty(), () -> v.map(Object::toString).orElse(""));
        // 跨行拼接不允许（押金 20000元 在另一行）
        Optional<EvidenceBinder.Violation> v2 = binder.check("doc1:2:5", "押金月租金",
                List.of("doc1"));
        assertTrue(v2.isPresent());
    }

    @Test
    void lowConfidenceOcrUnitVisible() {
        // merge 后 MERGED 行上的单元同样可引
        Optional<EvidenceBinder.Violation> v = binder.check("doc1:2:6", "２００００元", List.of("doc1"));
        assertTrue(v.isEmpty(), () -> v.map(Object::toString).orElse(""));
    }
}

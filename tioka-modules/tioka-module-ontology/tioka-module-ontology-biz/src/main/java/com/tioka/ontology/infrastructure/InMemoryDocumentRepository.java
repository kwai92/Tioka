package com.tioka.ontology.infrastructure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 进程内文档层仓储（DOC-01 本批实现）。persistParsed 以单一锁段模拟「一事务」：
 * 四表写入原子可见（JDBC 实现落地前登记偏差）。
 */
public class InMemoryDocumentRepository implements DocumentRepository {

    private final Map<String, DocumentRepository.DocumentRow> documents = new LinkedHashMap<>();
    private final Map<String, DocumentRepository.PageRow> pages = new LinkedHashMap<>();
    private final Map<String, DocumentRepository.UnitRow> units = new LinkedHashMap<>();
    private final Map<String, DocumentRepository.ClauseRow> clauses = new LinkedHashMap<>();
    private final Map<String, DocumentRepository.DocumentRow> dedup = new LinkedHashMap<>();
    private final ReentrantLock persistLock = new ReentrantLock();

    @Override
    public Optional<DocumentRepository.DocumentRow> findDocument(String docId) {
        return Optional.ofNullable(documents.get(docId));
    }

    @Override
    public Optional<DocumentRepository.DocumentRow> findByDedupKey(String fileHash,
                                                                   String parserVersion, String spaceId) {
        return Optional.ofNullable(dedup.get(dedupKey(fileHash, parserVersion, spaceId)));
    }

    @Override
    public void saveDocument(DocumentRepository.DocumentRow row) {
        documents.put(row.docId, row);
        dedup.put(dedupKey(row.fileHash, row.parserVersion, row.spaceId), row);
    }

    static String dedupKey(String fileHash, String parserVersion, String spaceId) {
        return fileHash + "|" + parserVersion + "|" + (spaceId == null ? "" : spaceId);
    }

    @Override
    public void persistParsed(DocumentRepository.DocumentRow doc,
                              List<DocumentRepository.PageRow> pageRows,
                              List<DocumentRepository.UnitRow> unitRows,
                              List<DocumentRepository.ClauseRow> clauseRows) {
        persistLock.lock();
        try {
            doc.pageCount = pageRows.size();
            doc.parseStatus = "READY";
            doc.parseError = null;
            documents.put(doc.docId, doc);
            dedup.put(dedupKey(doc.fileHash, doc.parserVersion, doc.spaceId), doc);
            pages.values().removeIf(p -> p.docId.equals(doc.docId));
            units.values().removeIf(u -> u.docId.equals(doc.docId));
            clauses.values().removeIf(c -> c.docId.equals(doc.docId));
            for (DocumentRepository.PageRow p : pageRows) {
                pages.put(p.docId + ":" + p.pageNo, p);
            }
            for (DocumentRepository.UnitRow u : unitRows) {
                units.put(u.unitId, u);
            }
            for (DocumentRepository.ClauseRow c : clauseRows) {
                clauses.put(c.docId + ":" + c.clausePath, c);
            }
        } finally {
            persistLock.unlock();
        }
    }

    @Override
    public Optional<DocumentRepository.UnitRow> findUnit(String unitId) {
        return Optional.ofNullable(units.get(unitId));
    }

    @Override
    public List<DocumentRepository.UnitRow> units(String docId, int fromSeq, int toSeq) {
        List<DocumentRepository.UnitRow> out = new ArrayList<>();
        for (DocumentRepository.UnitRow u : units.values()) {
            if (!u.docId.equals(docId)) {
                continue;
            }
            if (fromSeq > 0 && u.seq < fromSeq) {
                continue;
            }
            if (toSeq > 0 && u.seq > toSeq) {
                continue;
            }
            out.add(u);
        }
        out.sort(Comparator.comparingInt(u -> u.seq));
        return out;
    }

    @Override
    public List<DocumentRepository.UnitRow> unitsOfDocument(String docId) {
        return units(docId, 0, 0);
    }

    @Override
    public List<DocumentRepository.ClauseRow> clauses(String docId) {
        List<DocumentRepository.ClauseRow> out = new ArrayList<>();
        for (DocumentRepository.ClauseRow c : clauses.values()) {
            if (c.docId.equals(docId)) {
                out.add(c);
            }
        }
        out.sort(Comparator.comparing(c -> c.clausePath));
        return out;
    }

    @Override
    public List<DocumentRepository.PageRow> pages(String docId) {
        List<DocumentRepository.PageRow> out = new ArrayList<>();
        for (DocumentRepository.PageRow p : pages.values()) {
            if (p.docId.equals(docId)) {
                out.add(p);
            }
        }
        out.sort(Comparator.comparingInt(p -> p.pageNo));
        return out;
    }

    /** 测试与运维自省用：全部文档行。 */
    public List<DocumentRepository.DocumentRow> allDocuments() {
        return List.copyOf(documents.values());
    }
}

package com.tioka.ontology.infrastructure;

import com.tioka.ontology.api.docparse.ParsedRegion;
import com.tioka.ontology.api.docparse.ParsedTableRef;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 文档层仓储（DOC-01）。M3 批次 C-3 提供进程内实现
 * {@link InMemoryDocumentRepository}；MyBatis 持久化接线随 SCH 持久化批次补齐
 * （登记偏差）。{@link #persistParsed} 实现类必须在单事务内完成四表写入。
 */
public interface DocumentRepository {

    Optional<DocumentRow> findDocument(String docId);

    Optional<DocumentRow> findByDedupKey(String fileHash, String parserVersion, String spaceId);

    void saveDocument(DocumentRow row);

    /** 一个事务批量写 doc_page / doc_unit / doc_clause，并置 doc_document = READY。 */
    void persistParsed(DocumentRow doc, List<PageRow> pages, List<UnitRow> units,
                       List<ClauseRow> clauses);

    Optional<UnitRow> findUnit(String unitId);

    /** seq 闭区间；fromSeq &lt;= 0 表示从头。 */
    List<UnitRow> units(String docId, int fromSeq, int toSeq);

    List<UnitRow> unitsOfDocument(String docId);

    List<ClauseRow> clauses(String docId);

    List<PageRow> pages(String docId);

    // ---- 行模型（与 V170 四表列对齐） ----

    final class DocumentRow {
        public String docId;
        public String fileHash;
        public String parserVersion;
        public String mime;
        public String fileName;
        public String sourceSystem;
        public String sourceRef;
        public String objectKey;
        public int pageCount;
        public String parseStatus;
        public String parseError;
        public String dataLabel;
        public String spaceId;
        public Instant createdAt;

        public DocumentRow(String docId, String fileHash, String parserVersion, String mime,
                           String fileName, String sourceSystem, String sourceRef, String objectKey,
                           String dataLabel, String spaceId) {
            this.docId = docId;
            this.fileHash = fileHash;
            this.parserVersion = parserVersion;
            this.mime = mime;
            this.fileName = fileName;
            this.sourceSystem = sourceSystem;
            this.sourceRef = sourceRef;
            this.objectKey = objectKey;
            this.dataLabel = dataLabel == null || dataLabel.isBlank() ? "CONFIDENTIAL" : dataLabel;
            this.spaceId = spaceId;
            this.parseStatus = "PENDING";
            this.createdAt = Instant.now();
        }
    }

    final class PageRow {
        public String docId;
        public int pageNo;
        /** TEXT / OCR / MERGED。 */
        public String source;
        public String textLayer;
        public Double charDensity;
        public String ocrModel;

        public PageRow(String docId, int pageNo, String source, String textLayer,
                       Double charDensity, String ocrModel) {
            this.docId = docId;
            this.pageNo = pageNo;
            this.source = source;
            this.textLayer = textLayer;
            this.charDensity = charDensity;
            this.ocrModel = ocrModel;
        }
    }

    final class UnitRow {
        public String unitId;
        public String docId;
        public int pageNo;
        public int seq;
        /** PARA / HEADING / CELL / SIGN / BLANK_FIELD / COMMENT。 */
        public String kind;
        public String text;
        public String normText;
        public String clausePath;
        public String section;
        public List<ParsedRegion> bbox = List.of();
        public ParsedTableRef tableRef;
        public Double ocrConf;
    }

    final class ClauseRow {
        public String docId;
        public String clausePath;
        public String parentPath;
        public String title;
        public int unitFrom;
        public int unitTo;
        /** VALID / BLANK / MODIFIED。 */
        public String status = "VALID";
        public List<String> topics = List.of();

        public ClauseRow(String docId, String clausePath, String parentPath, String title,
                         int unitFrom, int unitTo, String status) {
            this.docId = docId;
            this.clausePath = clausePath;
            this.parentPath = parentPath;
            this.title = title;
            this.unitFrom = unitFrom;
            this.unitTo = unitTo;
            this.status = status;
        }
    }
}

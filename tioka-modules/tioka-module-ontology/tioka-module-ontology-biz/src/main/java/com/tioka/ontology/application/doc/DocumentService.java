package com.tioka.ontology.application.doc;

import com.tioka.framework.id.Ids;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.infra.api.ObjectStorageApi;
import com.tioka.ontology.api.doc.DocumentApi;
import com.tioka.ontology.api.doc.DocClause;
import com.tioka.ontology.api.doc.DocUnit;
import com.tioka.ontology.api.doc.DocumentView;
import com.tioka.ontology.api.doc.FileRef;
import com.tioka.ontology.api.docparse.ParseContract;
import com.tioka.ontology.api.docparse.ParsedClause;
import com.tioka.ontology.api.docparse.ParsedUnit;
import com.tioka.ontology.api.mq.OntologyMqTopics;
import com.tioka.ontology.domain.TextNormalizer;
import com.tioka.ontology.infrastructure.DocumentRepository;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DocumentService（DOC-01）：去重（file_hash + parser_version + space_id）、状态机
 * PENDING/PARSING/READY/FAILED、对象存储读写、UnitSegmenter → ClauseTreeBuilder →
 * 一事务批量写四表 → READY + 经 outbox 发 DOCUMENT_PARSED / DOCUMENT_READY。
 */
public class DocumentService implements DocumentApi {

    /** 解析器版本：切分规则或契约变更时递增（去重键成分）。 */
    public static final String PARSER_VERSION = "1.0.0";

    private final DocumentRepository repository;
    private final ParserRouter router;
    private final ObjectStorageApi storage;
    private final EventPublisher publisher;
    private final String systemAlias;

    public DocumentService(DocumentRepository repository,
                           ParserRouter router,
                           ObjectStorageApi storage,
                           EventPublisher publisher) {
        this(repository, router, storage, publisher, "default");
    }

    public DocumentService(DocumentRepository repository,
                           ParserRouter router,
                           ObjectStorageApi storage,
                           EventPublisher publisher,
                           String spaceAlias) {
        this.repository = repository;
        this.router = router;
        this.storage = storage;
        this.publisher = publisher;
        this.systemAlias = spaceAlias;
    }

    @Override
    public String ingest(FileRef fileRef) {
        if (fileRef == null || fileRef.sha256() == null || fileRef.sha256().isBlank()) {
            throw new IllegalArgumentException("sha256 必填（预检阶段计算）");
        }
        String spaceId = fileRef.spaceId() == null || fileRef.spaceId().isBlank()
                ? systemAlias : fileRef.spaceId();

        // 1. 去重：同哈希 + 同解析器版本 + 同空间且 READY → 直接返回 docId（M3-03 §2）
        Optional<DocumentRepository.DocumentRow> hit = repository.findByDedupKey(
                fileRef.sha256(), PARSER_VERSION, spaceId);
        if (hit.isPresent() && "READY".equals(hit.get().parseStatus)) {
            return hit.get().docId;
        }

        String docId = newDocId();
        String objectKey = "doc/original/" + docId + "/" + safeName(fileRef.fileName());
        byte[] bytes = storage.get(fileRef.storageKey());
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("storage object 不存在: " + fileRef.storageKey());
        }
        // SHA-256 以存储对象为准（FileRef 声明值校验）
        String actualHash = sha256Hex(bytes);
        if (!actualHash.equalsIgnoreCase(fileRef.sha256())) {
            throw new IllegalArgumentException("sha256 与对象内容不一致");
        }
        storage.put(objectKey, bytes, fileRef.mimeType());

        DocumentRepository.DocumentRow row = new DocumentRepository.DocumentRow(
                docId, actualHash, PARSER_VERSION, fileRef.mimeType(), fileRef.fileName(),
                fileRef.sourceSystem(), fileRef.sourceRef(), objectKey,
                fileRef.dataLabel(), spaceId);
        row.parseStatus = "PARSING";
        repository.saveDocument(row);

        try {
            // 3〜5：解析 → OCR → 合并 → 切分 → 条款树
            ParserRouter.ParseOutput out = router.parse(new ParserRouter.ParseInput(
                    fileRef.fileName(), fileRef.mimeType(), bytes, row.dataLabel, spaceId));
            List<ParsedUnit> rawUnits = UnitSegmenter.segment(flattenLines(out), docId);
            ClauseTreeBuilder.BuildResult tree = ClauseTreeBuilder.build(rawUnits);
            persist(docId, out, rawUnits, tree, row);
            publish(docId, row, out);
            return docId;
        } catch (RuntimeException e) {
            fail(row, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            throw (e instanceof IllegalArgumentException || e instanceof IllegalStateException)
                    ? e : new IllegalStateException(e.getMessage(), e);
        } catch (Exception e) {
            fail(row, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private void persist(String docId, ParserRouter.ParseOutput out, List<ParsedUnit> rawUnits,
                         ClauseTreeBuilder.BuildResult tree, DocumentRepository.DocumentRow row) {
        // 回填 clausePath；归一化 normText；ocr_conf < 0.8 落库（DOC-02 规则 3）
        List<DocumentRepository.UnitRow> unitRows = new ArrayList<>(rawUnits.size());
        for (ParsedUnit u : rawUnits) {
            String clausePath = tree.pathBySeq().get(u.seq());
            Double conf = u.confidence() != null && u.confidence() < 0.8 ? u.confidence() : null;
            DocumentRepository.UnitRow ur = new DocumentRepository.UnitRow();
            ur.unitId = u.blockId();
            ur.docId = docId;
            ur.pageNo = pageOf(u);
            ur.seq = u.seq();
            ur.kind = u.type();
            ur.text = u.text();
            ur.normText = TextNormalizer.normalize(u.text());
            ur.clausePath = clausePath;
            ur.section = u.section();
            ur.bbox = u.regions();
            ur.tableRef = u.tableRef();
            ur.ocrConf = conf;
            unitRows.add(ur);
        }
        List<DocumentRepository.PageRow> pageRows = new ArrayList<>();
        for (ParserRouter.Page p : out.pages()) {
            String source = sourceOf(p);
            StringBuilder text = new StringBuilder();
            for (com.tioka.ontology.api.docparse.ParsedLine l : p.lines()) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(l.text());
            }
            pageRows.add(new DocumentRepository.PageRow(docId, p.pageNo(), source,
                    text.toString(), p.charDensity(), null));
        }
        List<DocumentRepository.ClauseRow> clauseRows = new ArrayList<>();
        for (ParsedClause c : tree.clauses()) {
            clauseRows.add(new DocumentRepository.ClauseRow(docId, c.clausePath(), c.parentPath(),
                    c.title(), c.unitFrom(), c.unitTo(), c.status()));
        }
        repository.persistParsed(row, pageRows, unitRows, clauseRows);
    }

    private static List<com.tioka.ontology.api.docparse.ParsedLine> flattenLines(ParserRouter.ParseOutput out) {
        List<com.tioka.ontology.api.docparse.ParsedLine> all = new ArrayList<>();
        for (ParserRouter.Page p : out.pages()) {
            all.addAll(p.lines());
        }
        return all;
    }

    private static int pageOf(ParsedUnit u) {
        return u.regions().isEmpty() ? DocxParser.WORD_PAGE_NO : u.regions().get(0).pageNo();
    }

    private static String sourceOf(ParserRouter.Page p) {
        boolean hasText = false;
        boolean hasOcr = false;
        for (com.tioka.ontology.api.docparse.ParsedLine l : p.lines()) {
            if (ParseContract.S_OCR.equals(l.source())) {
                hasOcr = true;
            } else {
                hasText = true;
            }
        }
        if (hasText && hasOcr) {
            return "MERGED";
        }
        return hasOcr ? "OCR" : "TEXT";
    }

    private void fail(DocumentRepository.DocumentRow row, String message) {
        row.parseStatus = "FAILED";
        row.parseError = message;
        repository.saveDocument(row);
    }

    private void publish(String docId, DocumentRepository.DocumentRow row,
                         ParserRouter.ParseOutput out) {
        if (publisher == null) {
            return;
        }
        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("documentId", docId);
        ready.put("fileHash", row.fileHash);
        ready.put("parserVersion", row.parserVersion);
        ready.put("status", row.parseStatus);
        ready.put("workspaceId", row.spaceId);
        publisher.publish(new DomainEvent(OntologyMqTopics.DOCUMENT_READY,
                OntologyMqTopics.TOPIC, "doc:" + docId, ready, 0, null, "doc:" + docId));

        Map<String, Object> parsed = new LinkedHashMap<>();
        parsed.put("parseId", docId);
        parsed.put("fileHash", row.fileHash);
        parsed.put("planVersion", row.parserVersion);
        parsed.put("status", "READY");
        parsed.put("workspaceId", row.spaceId);
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("pages", out.pages().size());
        quality.put("warnings", out.warnings());
        parsed.put("qualitySummary", quality);
        publisher.publish(new DomainEvent(OntologyMqTopics.DOCUMENT_PARSED,
                OntologyMqTopics.TOPIC, "doc:" + docId, parsed, 0, null, "doc:" + docId));
    }

    @Override
    public Optional<DocumentView> get(String docId) {
        return repository.findDocument(docId).map(row -> new DocumentView(
                row.docId, row.parseStatus, row.fileName, row.mime, row.fileHash));
    }

    @Override
    public List<DocUnit> units(String docId, int fromSeq, int toSeq) {
        List<DocUnit> out = new ArrayList<>();
        for (DocumentRepository.UnitRow u : repository.units(docId, fromSeq, toSeq)) {
            out.add(new DocUnit(u.unitId, u.seq, u.pageNo, u.kind, u.text, u.normText,
                    u.clausePath, u.section));
        }
        return out;
    }

    @Override
    public List<DocClause> clauses(String docId) {
        return toApiClauses(repository.clauses(docId));
    }

    private List<DocClause> toApiClauses(List<DocumentRepository.ClauseRow> rows) {
        Map<String, List<DocumentRepository.ClauseRow>> byParent = new LinkedHashMap<>();
        List<DocumentRepository.ClauseRow> roots = new ArrayList<>();
        for (DocumentRepository.ClauseRow c : rows) {
            if (c.parentPath == null) {
                roots.add(c);
            } else {
                byParent.computeIfAbsent(c.parentPath, k -> new ArrayList<>()).add(c);
            }
        }
        List<DocClause> out = new ArrayList<>();
        for (DocumentRepository.ClauseRow r : roots) {
            out.add(toApi(r, byParent));
        }
        return out;
    }

    private DocClause toApi(DocumentRepository.ClauseRow row,
                            Map<String, List<DocumentRepository.ClauseRow>> byParent) {
        List<DocClause> children = new ArrayList<>();
        for (DocumentRepository.ClauseRow c : byParent.getOrDefault(row.clausePath, List.of())) {
            children.add(toApi(c, byParent));
        }
        return new DocClause(row.docId + ":" + row.clausePath, row.clausePath, row.title,
                List.of(), children);
    }

    /** doc_id 列宽 26（裸 ULID，V170 注释口径）。 */
    static String newDocId() {
        return Ids.newId("doc").substring(4);
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank()) {
            return "file";
        }
        return name.replaceAll("[^\\w.\\-\\u4e00-\\u9fa5]", "_");
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

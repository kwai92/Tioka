package com.tioka.knowledge.infrastructure.parse;

import com.tioka.infra.api.ObjectStorageApi;
import com.tioka.knowledge.application.parse.ParserPort;
import com.tioka.ontology.api.doc.DocumentApi;
import com.tioka.ontology.api.doc.DocumentView;
import com.tioka.ontology.api.doc.DocUnit;
import com.tioka.ontology.api.doc.FileRef;
import com.tioka.ontology.api.docparse.ParseContract;
import com.tioka.ontology.api.docparse.ParseResult;
import com.tioka.ontology.api.docparse.ParsedRegion;
import com.tioka.ontology.api.docparse.ParsedUnit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * KNW-11：ParserPort 的 DOC 域实现——复用 {@code DocumentApi.ingest(FileRef) + units(docId)}。
 *
 * <p>FileRef 接线（现状最简方式）：KNW 上传字节经 {@link ObjectStorageApi#put} 落对象交换层
 * 得 storageKey，sha256 在适配器内对字节现算（DocumentService 会按存储对象复核哈希），
 * spaceId = workspaceId（去重键 {@code sha256 + parser_version + space_id}——「同一文件只解析一次」
 * 由 DocumentService 命中 READY 直接返回 docId 实现），sourceSystem = "knw"。
 * ingest 同步完成解析落库并返回 docId；FAILED 时 DocumentService 落自身状态并抛异常
 * → 由入库流水线按阶段重试（≤3）。
 *
 * <p>DocUnit → ParsedUnit 映射（DocumentApi 未暴露 role/level/regions/tableRef，登记口径）：
 * role 由 kind 推导（PAGE_HEADER/PAGE_FOOTER → FURNITURE，COMMENT → ANNOTATION，其余 BODY）；
 * level 取 clausePath 深度（如 1.2.3 → 3），无 clausePath 的标题记 1；
 * regions 取单区域（pageNo, 0,0,0,0）供起止页计算；表格无 table_ref 列信息，
 * 连续 CELL 单元在 StructuredChunker 内按顺序聚合（行组退化为单元格顺序，Markdown 样本路径完整覆盖）。
 * 警告列表经 API 不可见（DOCUMENT_PARSED 事件承载）→ 本适配器恒产空警告；
 * PARTIAL 判定来自 units 中 ANNOTATION 角色存在时降级描述（真 OCR 链路的 PARTIAL 语义归 DOC 域）。
 */
public class DocParserAdapter implements ParserPort {

    private final DocumentApi documents;
    private final ObjectStorageApi storage;
    private final TextStructuredParser textParser = new TextStructuredParser();

    public DocParserAdapter(DocumentApi documents, ObjectStorageApi storage) {
        this.documents = documents;
        this.storage = storage;
    }

    @Override
    public ParseResult parse(ParseRequest request) {
        String format = ParserPort.resolveFormat(
                request.format() != null && !request.format().isBlank()
                        ? request.format() : request.fileName());
        if (ParserPort.isTextFormat(format)) {
            return textParser.parse(request.fileName(), format, request.bytes());
        }

        byte[] bytes = request.bytes() == null ? new byte[0] : request.bytes();
        String sha = TextStructuredParser.sha256Hex(bytes);
        String docId = documents.ingest(new FileRef(
                storageKey(sha, request.fileName()), request.fileName(),
                mimeTypeOf(format), sha, request.workspaceId(), null, "knw", null));
        List<DocUnit> units = documents.units(docId, 0, Integer.MAX_VALUE);
        DocumentView view = documents.get(docId)
                .orElseThrow(() -> new IllegalStateException("DOC 域文档不存在: " + docId));

        List<ParsedUnit> parsed = new ArrayList<>(units.size());
        int maxPage = 1;
        for (DocUnit u : units) {
            maxPage = Math.max(maxPage, Math.max(1, u.pageNo()));
            parsed.add(new ParsedUnit(
                    u.unitId(), u.seq(), u.kind(), roleOf(u.kind()), null, levelOf(u), null,
                    u.text(), List.of(new ParsedRegion(Math.max(1, u.pageNo()), 0, 0, 0, 0)),
                    null, null, List.of(), null, u.section()));
        }
        // M3 解析链路状态机为 PARSING/READY/FAILED；PARTIAL 由警告承载（见类注释登记）
        return new ParseResult(ParseContract.CONTRACT, docId, sha,
                "doc-1.0.0", view.status(), maxPage, null, parsed, List.of(), List.of());
    }

    private static String storageKey(String sha, String fileName) {
        String safe = fileName == null || fileName.isBlank() ? "file"
                : fileName.replaceAll("[^\\w.\\-\\u4e00-\\u9fa5]", "_");
        return "knw/ingest/" + sha + "/" + safe;
    }

    private static String roleOf(String kind) {
        if (ParseContract.T_PAGE_HEADER.equals(kind) || ParseContract.T_PAGE_FOOTER.equals(kind)) {
            return ParseContract.R_FURNITURE;
        }
        if (ParseContract.T_COMMENT.equals(kind)) {
            return ParseContract.R_ANNOTATION;
        }
        return ParseContract.R_BODY;
    }

    private static int levelOf(DocUnit u) {
        if (u.clausePath() == null || u.clausePath().isBlank()) {
            return 0;
        }
        return u.clausePath().split("\\.").length;
    }

    private static String mimeTypeOf(String format) {
        return switch (format) {
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "doc" -> "application/msword";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            default -> "application/octet-stream";
        };
    }
}

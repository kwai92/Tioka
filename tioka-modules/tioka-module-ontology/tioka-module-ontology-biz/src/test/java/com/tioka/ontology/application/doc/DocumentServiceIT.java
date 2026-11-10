package com.tioka.ontology.application.doc;

import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.infra.api.ObjectStorageApi;
import com.tioka.ontology.api.doc.FileRef;
import com.tioka.ontology.api.docparse.ParseContract;
import com.tioka.ontology.api.docparse.ParsedLine;
import com.tioka.ontology.api.docparse.ParsedRegion;
import com.tioka.ontology.infrastructure.InMemoryDocumentRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DocumentService（DOC-01）：dedup（同文件两次只解析一次）、状态机、docx/文字型 PDF
 * 解析与 OCR 队列。扫描 PDF / 真实租约样本属 Q8 外部输入 → threeFormats 标环境依赖禁用
 * （登记偏差）。离线运行：POI / PDFBox 进程内，OCR 用桩。
 */
class DocumentServiceIT {

    private InMemoryDocumentRepository repository;
    private MemoryObjectStore storage;
    private RecordingPublisher publisher;
    private CountingRouter router;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryDocumentRepository();
        storage = new MemoryObjectStore();
        publisher = new RecordingPublisher();
        OcrClient unavailable = (pageNo, png, dataLabel, spaceId) -> {
            throw new IllegalStateException("ocr_unavailable: 测试桩未配置 OCR");
        };
        router = new CountingRouter(
                new DefaultParserRouter(new DocxParser(), "soffice-should-not-run", unavailable));
        service = new DocumentService(repository, router, storage, publisher, "ws1");
    }

    /** 工具先例：ObjectStorageApi 内存实现（LocalObjectStore 口径）。 */
    static final class MemoryObjectStore implements ObjectStorageApi {
        final java.util.Map<String, byte[]> objects = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public String put(String key, byte[] content, String contentType) {
            objects.put(key, content);
            return key;
        }

        @Override
        public byte[] get(String key) {
            return objects.get(key);
        }

        @Override
        public DownloadTicket issueDownloadTicket(String objectKey) {
            return new DownloadTicket(objectKey, "t-" + objectKey, 0L);
        }

        @Override
        public byte[] redeemTicket(String ticket) {
            return new byte[0];
        }
    }

    static final class RecordingPublisher implements EventPublisher {
        final List<DomainEvent> events = new ArrayList<>();

        @Override
        public void publish(DomainEvent event) {
            events.add(event);
        }
    }

    /** 统计解析次数：dedup 断言「同文件两次只解析一次」。 */
    static final class CountingRouter implements ParserRouter {
        private final ParserRouter delegate;
        final AtomicInteger counter = new AtomicInteger();

        CountingRouter(ParserRouter delegate) {
            this.delegate = delegate;
        }

        @Override
        public ParseOutput parse(ParseInput input) {
            counter.incrementAndGet();
            return delegate.parse(input);
        }
    }

    private FileRef putFile(String name, String mime, byte[] bytes) {
        String key = "upload/" + name;
        storage.put(key, bytes, mime);
        return new FileRef(key, name, mime, DocumentService.sha256Hex(bytes), "ws1",
                "CONFIDENTIAL", "biz.lease", "ref-1");
    }

    private static byte[] docxBytes(List<String> paragraphs) throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            for (String p : paragraphs) {
                XWPFParagraph x = doc.createParagraph();
                x.createRun().setText(p);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.write(bos);
            return bos.toByteArray();
        }
    }

    /** PDFBox 文字型 PDF：数字条款编号（CLAUSE_NUMERIC 可命中），避免中文字体嵌入。 */
    private static byte[] textPdfBytes(String[] lines) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(PDType1Font.HELVETICA, 11);
                cs.newLineAtOffset(50, 740);
                for (String line : lines) {
                    cs.showText(line);
                    cs.newLineAtOffset(0, -16);
                }
                cs.endText();
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    private static String docxMime() {
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    }

    @Test
    void dedupSameFileParsedOnce() throws Exception {
        byte[] bytes = docxBytes(List.of("第一条 租赁标的", "房屋位于北京市。"));
        FileRef ref = putFile("lease.docx", docxMime(), bytes);

        String docId1 = service.ingest(ref);
        String docId2 = service.ingest(ref);

        assertEquals(docId1, docId2, "同哈希同解析器版本同空间 → 直接返回 docId");
        assertEquals(1, router.counter.get(), "解析只发生一次");
        assertEquals(1, repository.allDocuments().size());
        assertEquals("READY", service.get(docId1).orElseThrow().status());
    }

    @Test
    void docxParsesToReadyWithClauseTree() throws Exception {
        byte[] bytes = docxBytes(List.of(
                "第一条 租赁标的",
                "房屋位于北京市朝阳区。",
                "第二条 租金标准",
                "月租金 10000 元。"));
        FileRef ref = putFile("lease.docx", docxMime(), bytes);

        String docId = service.ingest(ref);

        var view = service.get(docId).orElseThrow();
        assertEquals("READY", view.status());
        var units = service.units(docId, 0, 0);
        assertTrue(units.stream().anyMatch(u -> "HEADING".equals(u.kind())
                && u.text().startsWith("第一条")), "标题单元识别");
        assertTrue(units.stream().allMatch(u -> u.normText() != null && !u.normText().isEmpty()),
                "normText 全量归一化");
        var clauses = service.clauses(docId);
        assertEquals(2, clauses.size(), "条款树两条");
        assertEquals("1", clauses.get(0).number());
        // DOCUMENT_PARSED / DOCUMENT_READY 均经 outbox 发布
        assertTrue(publisher.events.stream().anyMatch(e ->
                "tioka.docparse.document_parsed.v1".equals(e.event())));
        assertTrue(publisher.events.stream().anyMatch(e ->
                "tioka.ontology.document_ready.v1".equals(e.event())));
    }

    @Test
    void textPdfParsesWithTextLayerNoOcr() throws Exception {
        byte[] bytes = textPdfBytes(new String[]{
                "1 Rent and Deposit", "Rent is 10000 yuan per month, paid on the fifth day.",
                "1.1 Late Payment", "Late fee is 0.05 percent per day of the unpaid rent amount."});
        FileRef ref = putFile("lease.pdf", "application/pdf", bytes);

        String docId = service.ingest(ref);
        assertEquals("READY", service.get(docId).orElseThrow().status());
        assertEquals(1, router.counter.get());
        // 文字层可信（≥50 字/页），零 OCR 调用、无 FAILED 文档
        assertTrue(repository.allDocuments().stream()
                .allMatch(d -> "READY".equals(d.parseStatus)));
        assertEquals(1, repository.pages(docId).size());
    }

    @Test
    void scannedPageWithOcrStubBecomesReadyAndMerged() throws Exception {
        // 文字层不可信（<50 字）→ OCR 队列；桩返回一行低置信签章日期
        OcrClient stub = (pageNo, png, dataLabel, spaceId) -> List.of(new ParsedLine(pageNo,
                "2026-01-01", List.of(new ParsedRegion(pageNo, 0.5, 0.85, 0.9, 0.95)), "PARA",
                null, ParseContract.S_OCR, 0.72));
        DocumentService withOcr = new DocumentService(repository,
                new DefaultParserRouter(new DocxParser(), "soffice", stub),
                storage, publisher, "ws1");
        byte[] bytes = textPdfBytes(new String[]{"x"});
        FileRef ref = putFile("scan.pdf", "application/pdf", bytes);

        String docId = withOcr.ingest(ref);
        assertEquals("READY", withOcr.get(docId).orElseThrow().status());
        // OCR 行合并进单元（OCR 独有行追加），低置信 <0.8 随行携带不丢
        assertTrue(withOcr.units(docId, 0, 0).stream()
                .anyMatch(u -> u.text().contains("2026-01-01")));
    }

    @Test
    void ocrUnavailableMarksFailedWithReason() throws Exception {
        byte[] bytes = textPdfBytes(new String[]{"x"});
        FileRef ref = putFile("scan2.pdf", "application/pdf", bytes);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.ingest(ref));
        assertTrue(e.getMessage().contains("ocr_unavailable"));
        assertEquals(1, repository.allDocuments().size());
        assertEquals("FAILED", repository.allDocuments().get(0).parseStatus);
        assertTrue(repository.allDocuments().get(0).parseError.contains("ocr_unavailable"));
    }

    /** M3-03 §2 验收用例：docx、文字型 PDF、扫描 PDF 样本合同全 READY 且条款数与人工标注一致。
     *  扫描 PDF 与真实租约样本属 Q8 外部输入（登记偏差：环境依赖，样本就绪后启用）。 */
    @Test
    @Disabled("Q8 外部输入：扫描 PDF/真实租约样本未就绪（95% 条款识别率验收归环境依赖）")
    void threeFormats() {
        throw new UnsupportedOperationException();
    }
}

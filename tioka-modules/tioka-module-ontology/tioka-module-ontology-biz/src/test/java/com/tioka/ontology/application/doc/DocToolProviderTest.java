package com.tioka.ontology.application.doc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.infra.api.ObjectStorageApi;
import com.tioka.ontology.api.doc.DocUnit;
import com.tioka.ontology.api.doc.FileRef;
import com.tioka.ontology.infrastructure.DocumentRepository;
import com.tioka.ontology.infrastructure.InMemoryDocumentRepository;
import com.tioka.runtime.api.spi.Reversibility;
import com.tioka.runtime.api.spi.ToolProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @doc.unit 工具（DOC-03）：返回单元与前后各 N 个单元的文本、条款路径、页码；只读 NONE 可逆。
 */
class DocToolProviderTest {

    private DocumentService service;
    private DocToolProvider provider;
    private RecordingPublisher publisher;

    @BeforeEach
    void setUp() throws Exception {
        InMemoryDocumentRepository repository = new InMemoryDocumentRepository();
        MemoryObjectStore storage = new MemoryObjectStore();
        publisher = new RecordingPublisher();
        OcrClient unavailable = (pageNo, png, dataLabel, spaceId) -> {
            throw new IllegalStateException("ocr_unavailable");
        };
        ParserRouter router = new DefaultParserRouter(new DocxParser(), "soffice-x", unavailable);
        service = new DocumentService(repository, router, storage, publisher, "ws1");

        // 直接灌入已知单元（绕过真实解析）：seq 1〜5，其中 2〜4 属条款 1
        InMemoryDocumentRepository repo = repository;
        List<DocumentRepository.UnitRow> units = new java.util.ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            DocumentRepository.UnitRow u = new DocumentRepository.UnitRow();
            u.unitId = "docT:1:" + i;
            u.docId = "docT";
            u.pageNo = 1;
            u.seq = i;
            u.kind = i == 2 ? "HEADING" : "PARA";
            u.text = "单元" + i;
            u.normText = "单元" + i;
            u.clausePath = (i >= 2 && i <= 4) ? "1" : null;
            u.section = "GENERAL";
            units.add(u);
        }
        DocumentRepository.DocumentRow doc = new DocumentRepository.DocumentRow(
                "docT", "hash-docT", DocumentService.PARSER_VERSION,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "docT.docx", null, null, "k/docT", "CONFIDENTIAL", "ws1");
        repo.persistParsed(doc,
                List.of(new DocumentRepository.PageRow("docT", 1, "TEXT", "text", 100d, null)),
                units, List.of());

        provider = new DocToolProvider(service, new ObjectMapper());
    }

    static final class RecordingPublisher implements EventPublisher {
        final List<DomainEvent> events = new ArrayList<>();

        @Override
        public void publish(DomainEvent event) {
            events.add(event);
        }
    }

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
            return new DownloadTicket(objectKey, "t", 0L);
        }

        @Override
        public byte[] redeemTicket(String ticket) {
            return new byte[0];
        }
    }

    @Test
    void windowReturnsUnitAndNeighborsWithMetadata() throws Exception {
        var result = provider.invoke("doc.unit",
                Map.of("unitId", "docT:1:3", "before", 2, "after", 2));
        assertTrue(result.ok(), () -> result.content());
        assertTrue(result.content().contains("\"clausePath\""));
        assertTrue(result.content().contains("\"pageNo\""));
        // 窗口 1〜5 共 5 个单元，当前单元标记
        assertTrue(result.content().contains("\"current\":true"));
        assertTrue(result.content().contains("单元1"));
        assertTrue(result.content().contains("单元5"));
    }

    @Test
    void descriptorIsReadOnlyNoneReversible() {
        var tools = provider.listTools("agent-1");
        assertEquals(1, tools.size());
        var d = tools.get(0);
        assertEquals("doc.unit", d.name());
        assertEquals(Reversibility.NONE, d.reversibility());
        assertTrue(d.inputSchema().toString().contains("unitId"));
    }

    @Test
    void unknownUnitReturnsError() {
        var result = provider.invoke("doc.unit", Map.of("unitId", "docX:1:99"));
        assertFalse(result.ok());
        assertTrue(result.content().contains("unit 不存在"));
    }

    @Test
    void documentApiUnitsWindowMatchesRepository() {
        List<DocUnit> units = service.units("docT", 2, 4);
        assertEquals(3, units.size());
        assertEquals("1", units.get(0).clausePath());
        assertEquals(1, units.get(0).pageNo());
    }
}

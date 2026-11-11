package com.tioka.knowledge.application;

import com.tioka.framework.messaging.DomainEvent;
import com.tioka.knowledge.api.mq.KnowledgeMqTopics;
import com.tioka.knowledge.infrastructure.ChunkInsertCommand;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import com.tioka.knowledge.infrastructure.persistence.KbBaseDO;
import com.tioka.knowledge.infrastructure.persistence.KbChunkDO;
import com.tioka.knowledge.infrastructure.persistence.KbDocumentDO;
import com.tioka.ontology.api.docparse.ParseContract;
import com.tioka.ontology.api.docparse.ParseResult;
import com.tioka.ontology.api.docparse.ParsedRegion;
import com.tioka.ontology.api.docparse.ParsedUnit;
import com.tioka.knowledge.application.parse.ParserPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KNW-11 验收（ADR-055 §1，SharedParserIT）：假 ParserPort 离线验证共用解析服务链路——
 * READY 可检索、FAILED 按阶段重试、PARTIAL 警告落库、同一文件二次提交不重解析
 * （断言 ParserPort 实际解析调用计数，去重语义 = DocumentService.findByDedupKey 命中 READY 直返）。
 * 扫描件上传可检索的 OCR 端到端归 DOC 域既有测试/CI（登记口径），此处以假桩覆盖链路语义。
 */
class SharedParserIT {

    private static final String WS = "ws_c15";

    private KnowledgeRepository repo;
    private List<DomainEvent> events;
    private KnowledgeIngestJobService jobService;
    private KnowledgeService asyncSvc;
    private FakeParserPort port;

    @BeforeEach
    void seed() {
        repo = KnowledgeInMemorySupport.repo();
        events = new CopyOnWriteArrayList<>();
        port = new FakeParserPort();
        jobService = KnowledgeInMemorySupport.jobService(repo, events::add, null, port);
        asyncSvc = KnowledgeInMemorySupport.asyncService(repo, jobService);
    }

    private String newKb(String id) {
        KbBaseDO base = new KbBaseDO();
        base.setId(id);
        base.setWorkspaceId(WS);
        repo.insertBase(base);
        return id;
    }

    private String jobIdOfLastEvent() {
        return events.stream()
                .filter(e -> KnowledgeMqTopics.INGEST_REQUESTED.equals(e.event()))
                .reduce((a, b) -> b)
                .orElseThrow()
                .payload().get("jobId").toString();
    }

    private void driveToTermination(KnowledgeIngestJobService service, String jobId) {
        int guard = 0;
        while (service.process(jobId)) {
            assertTrue(++guard < 100, "任务推进未收敛");
        }
    }

    private static final String MARKDOWN_DOC = """
            # 第一章 总则
            %s
            %s
            """.formatted(
            "为规范人工智能系统的治理，本项目确立可信优先原则。所有模型调用必须经过统一网关。".repeat(10),
            "每一次检索引用都必须可追溯至原始文档分块。知识库的分块策略必须保证语义完整性。".repeat(10));

    @Test
    void readyDocumentIsSearchableWithParentContext() {
        String kb = newKb("kb_c15_ready");
        KnowledgeService.Document doc = asyncSvc.ingestUpload(kb, "治理规范",
                MARKDOWN_DOC.getBytes(StandardCharsets.UTF_8), "governance.md");
        String jobId = jobIdOfLastEvent();
        driveToTermination(jobService, jobId);

        // READY：解析产物溯源落文档，检索可见
        assertEquals(1, port.realParses.get());
        KbDocumentDO row = repo.findDocument(doc.id());
        assertEquals("READY", row.getStatus());
        assertEquals("READY", row.getParseStatus());
        assertEquals("fake-1", row.getParseId());
        assertEquals(ParseContract.CONTRACT, row.getParsePlan());
        assertNotNull(row.getQualityMetricsJson());

        List<KbChunkDO> chunks = repo.listChunksByDoc(doc.id());
        assertTrue(chunks.size() >= 3, "应包含父块与多个子块: " + chunks.size());
        assertTrue(chunks.stream().anyMatch(c -> ChunkInsertCommand.TYPE_PARENT.equals(c.getChunkType())));
        assertTrue(chunks.stream().allMatch(c -> c.getDocVersion() == 1));

        // 检索返回子块；同一父块的上下文只随第一个命中返回一次
        List<KnowledgeService.RetrievalHit> hits = asyncSvc.search(kb, "", 20).stream()
                .filter(h -> doc.id().equals(h.docId()))
                .toList();
        long childCount = chunks.stream()
                .filter(c -> ChunkInsertCommand.TYPE_CHILD.equals(c.getChunkType())).count();
        assertEquals(childCount, hits.size());
        assertEquals(1, hits.stream().filter(h -> h.parentText() != null).count(),
                "同一父块只返回一次上下文");
        assertTrue(hits.get(0).parentText() != null && !hits.get(0).parentText().isBlank());
        // 子块引用定位字段：起止页与 blockIds
        assertTrue(hits.stream().allMatch(h -> h.pageStart() != null && h.pageStart() >= 1));
        assertTrue(hits.stream().anyMatch(h -> h.blockIds() != null && !h.blockIds().isEmpty()));
    }

    @Test
    void failedParseRetriesPerStageAndExhaustsToFailed() {
        String kb = newKb("kb_c15_retry");
        // 前两次失败、第三次成功 → 按阶段重试 2 次后 READY
        port.failuresToInject.set(2);
        KnowledgeService.Document doc = asyncSvc.ingestUpload(kb, "重试文档",
                MARKDOWN_DOC.getBytes(StandardCharsets.UTF_8), "retry.md");
        String jobId = jobIdOfLastEvent();
        driveToTermination(jobService, jobId);
        assertEquals("READY", repo.findDocument(doc.id()).getStatus());
        assertEquals(2, repo.findJob(jobId).getAttempts(), "解析失败按阶段重试");
        assertEquals(3, port.realParses.get(), "重试期间重新解析（去重键未命中：文档域侧 FAILED 不算 READY）");

        // 持续失败 → 耗尽 3 次 → FAILED 且文档页可见（不同内容避开去重缓存）
        String kb2 = newKb("kb_c15_fail");
        port.failuresToInject.set(99);
        KnowledgeService.Document doc2 = asyncSvc.ingestUpload(kb2, "失败文档",
                ("持续失败的另一份文档内容。".repeat(10)).getBytes(StandardCharsets.UTF_8), "fail.md");
        String jobId2 = jobIdOfLastEvent();
        driveToTermination(jobService, jobId2);
        assertEquals("FAILED", repo.findJob(jobId2).getStatus());
        assertEquals(3, repo.findJob(jobId2).getAttempts(), "重试最多 3 次");
        assertEquals("FAILED", repo.findDocument(doc2.id()).getStatus());
    }

    @Test
    void partialStatusStillIndexedWithWarningsOnDocument() {
        String kb = newKb("kb_c15_partial");
        port.partialMode = true;
        KnowledgeService.Document doc = asyncSvc.ingestUpload(kb, "降级文档",
                MARKDOWN_DOC.getBytes(StandardCharsets.UTF_8), "partial.md");
        String jobId = jobIdOfLastEvent();
        driveToTermination(jobService, jobId);

        // PARTIAL 照常入库：文档 READY、解析状态 PARTIAL、降级原因（warnings）写到文档上
        assertEquals("READY", repo.findDocument(doc.id()).getStatus());
        KbDocumentDO row = repo.findDocument(doc.id());
        assertEquals("PARTIAL", row.getParseStatus());
        assertNotNull(row.getParseWarnings());
        assertTrue(row.getParseWarnings().contains("page 2 ocr degraded"), row.getParseWarnings());
        List<KnowledgeService.RetrievalHit> hits = asyncSvc.search(kb, "", 10).stream()
                .filter(h -> doc.id().equals(h.docId()))
                .toList();
        assertTrue(hits.size() > 0, "PARTIAL 文档可检索");
    }

    @Test
    void sameFileParsedOnceAcrossKnowledgeBases() {
        String kb1 = newKb("kb_c15_a");
        String kb2 = newKb("kb_c15_b");
        byte[] bytes = MARKDOWN_DOC.getBytes(StandardCharsets.UTF_8);

        KnowledgeService.Document d1 = asyncSvc.ingestUpload(kb1, "同一文件A",
                bytes, "shared.md");
        driveToTermination(jobService, jobIdOfLastEvent());
        KnowledgeService.Document d2 = asyncSvc.ingestUpload(kb2, "同一文件B",
                bytes, "shared.md");
        driveToTermination(jobService, jobIdOfLastEvent());

        assertEquals(1, port.realParses.get(), "同一文件（同 sha256 + 空间）只解析一次");
        assertEquals("fake-1", repo.findDocument(d1.id()).getParseId());
        assertEquals("fake-1", repo.findDocument(d2.id()).getParseId(), "去重命中返回同一 parseId");
        assertTrue(d2.id() != null);

        // 同库重复提交：content_hash 去重跳过，不建新任务、不触发解析
        int eventsBefore = events.size();
        KnowledgeService.Document again = asyncSvc.ingestUpload(kb1, "同一文件A",
                bytes, "shared.md");
        assertEquals(d1.id(), again.id());
        assertEquals(eventsBefore, events.size(), "内容未变应跳过");
        assertEquals(1, port.realParses.get());
    }

    // ------------------------------------------------------------------
    // 假 ParserPort：DOC 域共用解析服务契约的离线替身（dedup 语义对齐 DocumentService）
    // ------------------------------------------------------------------

    private static final class FakeParserPort implements ParserPort {
        final AtomicInteger realParses = new AtomicInteger();
        final AtomicInteger failuresToInject = new AtomicInteger();
        final Map<String, ParseResult> cache = new ConcurrentHashMap<>();
        volatile boolean partialMode;

        @Override
        public ParseResult parse(ParseRequest request) {
            String sha = com.tioka.knowledge.infrastructure.parse.TextStructuredParser
                    .sha256Hex(request.bytes() == null ? new byte[0] : request.bytes());
            ParseResult hit = cache.get(sha);
            if (hit != null) {
                return hit; // 去重：同文件不重解析
            }
            realParses.incrementAndGet();
            if (failuresToInject.getAndDecrement() > 0) {
                throw new IllegalStateException("parse boom: 模拟解析抖动");
            }
            List<ParsedUnit> units = new ArrayList<>();
            int seq = 0;
            for (String line : new String(request.bytes(), StandardCharsets.UTF_8).split("\n")) {
                String text = line.strip();
                if (text.isEmpty()) {
                    continue;
                }
                if (text.startsWith("#")) {
                    int level = 0;
                    while (level < text.length() && text.charAt(level) == '#') {
                        level++;
                    }
                    units.add(new ParsedUnit("blk:" + (++seq), seq, ParseContract.T_HEADING,
                            ParseContract.R_BODY, null, Math.min(level, 6), null,
                            text.substring(level).strip(),
                            List.of(new ParsedRegion(1, 0, 0, 0, 0)), null, null,
                            List.of(), null, null));
                } else {
                    units.add(new ParsedUnit("blk:" + (++seq), seq, ParseContract.T_PARAGRAPH,
                            ParseContract.R_BODY, null, 0, null, text,
                            List.of(new ParsedRegion(1, 0, 0, 0, 0)), null, null,
                            List.of(), null, null));
                }
            }
            String status = partialMode ? ParseContract.ST_PARTIAL : ParseContract.ST_READY;
            List<String> warnings = partialMode
                    ? List.of("page 2 ocr degraded", "page 3 low confidence") : List.of();
            ParseResult result = new ParseResult(ParseContract.CONTRACT, "fake-1", sha,
                    "fake-1.0.0", status, 1, null, units, List.of(), warnings);
            cache.put(sha, result);
            return result;
        }
    }
}

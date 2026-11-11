package com.tioka.knowledge.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.knowledge.api.IngestChangeSource;
import com.tioka.knowledge.api.mq.KnowledgeMqTopics;
import com.tioka.knowledge.infrastructure.ChunkInsertCommand;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import com.tioka.knowledge.infrastructure.embedding.HashEmbeddingClient;
import com.tioka.knowledge.infrastructure.persistence.KbBaseDO;
import com.tioka.knowledge.infrastructure.persistence.KbChunkDO;
import com.tioka.knowledge.infrastructure.persistence.KbDocumentDO;
import com.tioka.knowledge.infrastructure.persistence.KbIngestJobDO;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KNW-05 验收（M3-06 §3.1）：
 * {@code #asyncReturnsImmediately} 提交同事务登记并立即返回；
 * {@code #retryPerStage} 按阶段重试最多 3 次；
 * {@code #atomicSwitch} 版本切换过程中并发检索看不到半个版本。
 * 离线优先：内存 Repository 替身 + 哈希嵌入（同 {@code UndoIT} 做法，不碰 DB/Broker）。
 */
class IngestJobIT {

    private static final String KB = "kb_c13";
    private static final String WS = "ws_c13";

    private KnowledgeRepository repo;
    private List<DomainEvent> events;
    private KnowledgeIngestJobService jobService;
    private KnowledgeService asyncSvc;

    @BeforeEach
    void seed() {
        repo = KnowledgeInMemorySupport.repo();
        KbBaseDO base = new KbBaseDO();
        base.setId(KB);
        base.setWorkspaceId(WS);
        repo.insertBase(base);

        events = new CopyOnWriteArrayList<>();
        jobService = KnowledgeInMemorySupport.jobService(repo, events::add, null);
        asyncSvc = KnowledgeInMemorySupport.asyncService(repo, jobService);
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

    private long ingestEventCount() {
        return events.stream().filter(e -> KnowledgeMqTopics.INGEST_REQUESTED.equals(e.event())).count();
    }

    @Test
    void asyncReturnsImmediately() {
        String content = "KNW-05 异步入库验收内容。".repeat(30);

        KnowledgeService.Document doc = asyncSvc.ingest(KB, "验收文档", content, "upload", "txt");

        // 提交即返回：文档 QUEUED、任务 PARSE/QUEUED、outbox 事件已捕获
        assertEquals("QUEUED", doc.status());
        KbDocumentDO docRow = repo.findDocument(doc.id());
        assertNotNull(docRow);
        assertEquals("QUEUED", docRow.getStatus());
        assertNotNull(docRow.getContentHash());
        assertEquals(0, docRow.getActiveVersion());
        assertEquals(1, ingestEventCount());

        String jobId = jobIdOfLastEvent();
        KbIngestJobDO job = repo.findJob(jobId);
        assertNotNull(job);
        assertEquals("PARSE", job.getStage());
        assertEquals("QUEUED", job.getStatus());
        assertEquals(0, job.getAttempts());
        assertEquals(1, job.getDocVersion());

        // 消费者驱动：阶段推进到 READY，检索可见
        // KNW-03：kb_chunk 现为父子块结构（父块无向量仅供上下文），检索只返回子块——
        // 断言对切块粒度不敏感：总行数 >= 2、版本一致、检索命中数 == 子块数
        driveToTermination(jobService, jobId);
        assertEquals("READY", repo.findDocument(doc.id()).getStatus());
        KbIngestJobDO done = repo.findJob(jobId);
        assertEquals("DONE", done.getStatus());
        assertEquals("SWAP", done.getStage());
        List<KbChunkDO> chunks = repo.listChunksByDoc(doc.id());
        assertTrue(chunks.size() >= 2, "切块应产生多个分块");
        assertTrue(chunks.stream().allMatch(c -> c.getDocVersion() == 1));
        assertEquals(1, repo.findDocument(doc.id()).getActiveVersion());
        long childCount = chunks.stream()
                .filter(c -> ChunkInsertCommand.TYPE_CHILD.equals(c.getChunkType())).count();
        assertEquals(childCount, asyncSvc.search(KB, "", 50).size());

        // content_hash 去重：同内容再次提交不建新任务
        KnowledgeService.Document again = asyncSvc.ingest(KB, "验收文档", content, "upload", "txt");
        assertEquals(doc.id(), again.id());
        assertEquals(1, ingestEventCount(), "内容未变应跳过");
    }

    @Test
    void retryPerStage() {
        // EMBED 阶段前两次失败、第三次成功（按阶段重试）
        AtomicInteger embedCalls = new AtomicInteger();
        ModelGateway hashGateway = hashGateway();
        ModelGateway flaky = wrapEmbed(hashGateway, () -> {
            if (embedCalls.incrementAndGet() <= 2) {
                throw new BizException(ErrorCode.DEPENDENCY, "嵌入服务抖动");
            }
        });

        KnowledgeIngestJobService flakyJobs = KnowledgeInMemorySupport.jobService(repo, events::add, flaky);
        KnowledgeService flakySvc = KnowledgeInMemorySupport.asyncService(repo, flakyJobs);

        KnowledgeService.Document doc = flakySvc.ingest(KB, "重试文档", "内容A。".repeat(50), "upload", "txt");
        String jobId = jobIdOfLastEvent();
        driveToTermination(flakyJobs, jobId);

        assertEquals("READY", repo.findDocument(doc.id()).getStatus());
        assertEquals(2, repo.findJob(jobId).getAttempts(), "按阶段重试 2 次后成功");
        assertTrue(embedCalls.get() >= 3);
        assertTrue(repo.listChunksByDoc(doc.id()).stream().allMatch(c -> c.getDocVersion() == 1));

        // 耗尽 3 次 → FAILED 且文档页可见
        AtomicInteger alwaysFail = new AtomicInteger();
        ModelGateway broken = wrapEmbed(hashGateway, () -> {
            alwaysFail.incrementAndGet();
            throw new BizException(ErrorCode.DEPENDENCY, "嵌入服务持续不可用");
        });
        KnowledgeIngestJobService brokenJobs = KnowledgeInMemorySupport.jobService(repo, events::add, broken);
        KnowledgeService brokenSvc = KnowledgeInMemorySupport.asyncService(repo, brokenJobs);
        KnowledgeService.Document doc2 = brokenSvc.ingest(KB, "失败文档", "内容B。".repeat(50), "upload", "txt");
        String jobId2 = jobIdOfLastEvent();
        driveToTermination(brokenJobs, jobId2);

        assertEquals("FAILED", repo.findJob(jobId2).getStatus());
        assertEquals(3, repo.findJob(jobId2).getAttempts(), "重试最多 3 次");
        assertEquals("FAILED", repo.findDocument(doc2.id()).getStatus(), "FAILED 在知识库页可见（状态计数）");
        assertNotEquals(0, repo.countDocumentsByStatus(KB).getOrDefault("FAILED", 0L));
    }

    @Test
    void atomicSwitch() throws Exception {
        // v1 经 ChangeEvent 入库（F39），外部 ID e1 → 后续版本复用同一 doc 行
        KnowledgeService.Document doc = asyncSvc.ingestChange(KB,
                new IngestChangeSource("e1", "UPSERT", "v1", "合同范本", null),
                "旧版本内容 old-chunk-".repeat(20));
        String jobId1 = jobIdOfLastEvent();
        driveToTermination(jobService, jobId1);
        String docId = doc.id();
        assertEquals(1, repo.findDocument(docId).getActiveVersion());
        // KNW-03：检索只看子块文本（父块仅为上下文），版本一致性断言针对子块集合
        Set<String> v1Texts = childTexts(repo, docId);
        assertTrue(repo.listChunksByDoc(docId).stream().allMatch(c -> c.getDocVersion() == 1));

        // v2 提交：EMBED 写 v2 分块（active_version 仍为 1）→ SWAP 原子切换
        // 切换窗口内并发检索：每次快照只能是完整 v1 或完整 v2，绝无混合
        asyncSvc.ingestChange(KB,
                new IngestChangeSource("e1", "UPSERT", "v2", "合同范本", null),
                "新版本内容 new-chunk-".repeat(20));
        String jobId2 = jobIdOfLastEvent();
        assertNotEquals(jobId1, jobId2);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        List<Set<String>> snapshots = new CopyOnWriteArrayList<>();
        pool.execute(() -> {
            for (int i = 0; i < 500; i++) {
                KbIngestJobDO current = repo.findJob(jobId2);
                if (current == null || "DONE".equals(current.getStatus()) || "FAILED".equals(current.getStatus())) {
                    return;
                }
                snapshots.add(asyncSvc.search(KB, "", 100).stream()
                        .filter(h -> docId.equals(h.docId()))
                        .map(KnowledgeService.RetrievalHit::text)
                        .collect(Collectors.toSet()));
            }
        });
        driveToTermination(jobService, jobId2);
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertTrue(snapshots.size() > 0, "切换窗口内应产生并发检索快照");

        Set<String> v2Texts = childTexts(repo, docId);
        assertNotEquals(v1Texts, v2Texts);
        for (Set<String> snapshot : snapshots) {
            assertTrue(snapshot.isEmpty() || snapshot.equals(v1Texts) || snapshot.equals(v2Texts),
                    "并发检索看到半个版本: " + snapshot);
        }
        // 切换完成后：只看 v2，旧版本分块已删除
        assertEquals(2, repo.findDocument(docId).getActiveVersion());
        assertTrue(repo.listChunksByDoc(docId).stream().allMatch(c -> c.getDocVersion() == 2));
        assertEquals(v2Texts, asyncSvc.search(KB, "", 100).stream()
                .filter(h -> docId.equals(h.docId()))
                .map(KnowledgeService.RetrievalHit::text)
                .collect(Collectors.toSet()));
        // F39：externalId / aclPrincipals 落列
        assertEquals("e1", repo.findDocument(docId).getExternalId());
        assertEquals("v2", repo.findDocument(docId).getExternalVersion());
    }

    /** KNW-03：子块文本集合（父块无向量不参与检索）。 */
    private static Set<String> childTexts(KnowledgeRepository repo, String docId) {
        return repo.listChunksByDoc(docId).stream()
                .filter(c -> ChunkInsertCommand.TYPE_CHILD.equals(c.getChunkType()))
                .map(KbChunkDO::getText).collect(Collectors.toSet());
    }

    // ------------------------------------------------------------------
    // 测试支撑
    // ------------------------------------------------------------------

    private interface EmbedHook {
        void before();
    }

    private static ModelGateway wrapEmbed(ModelGateway delegate, EmbedHook hook) {
        return new ModelGateway() {
            @Override
            public ChatResult chat(ModelCallContext ctx, ChatRequest request,
                                   java.util.function.Consumer<StreamDelta> onDelta) {
                return delegate.chat(ctx, request, onDelta);
            }

            @Override
            public com.tioka.model.api.capability.OcrResult ocr(ModelCallContext ctx,
                    com.tioka.model.api.capability.OcrRequest request) {
                return delegate.ocr(ctx, request);
            }

            @Override
            public EmbeddingResult embed(ModelCallContext ctx, EmbeddingRequest request) {
                hook.before();
                return delegate.embed(ctx, request);
            }

            @Override
            public RerankResult rerank(ModelCallContext ctx, RerankRequest request) {
                return delegate.rerank(ctx, request);
            }
        };
    }

    private static ModelGateway hashGateway() {
        HashEmbeddingClient hash = new HashEmbeddingClient(32);
        return new ModelGateway() {
            @Override
            public ChatResult chat(ModelCallContext ctx, ChatRequest request,
                                   java.util.function.Consumer<StreamDelta> onDelta) {
                throw new UnsupportedOperationException();
            }

            @Override
            public com.tioka.model.api.capability.OcrResult ocr(ModelCallContext ctx,
                    com.tioka.model.api.capability.OcrRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public EmbeddingResult embed(ModelCallContext ctx, EmbeddingRequest request) {
                List<float[]> vectors = new java.util.ArrayList<>();
                for (String text : request.inputs()) {
                    double[] d = hash.embed(text);
                    float[] f = new float[d.length];
                    for (int i = 0; i < d.length; i++) {
                        f[i] = (float) d[i];
                    }
                    vectors.add(f);
                }
                return new EmbeddingResult(vectors, hash.dimension(), request.inputs().size(), "bnd_hash");
            }

            @Override
            public RerankResult rerank(ModelCallContext ctx, RerankRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }
}

package com.tioka.knowledge.infrastructure.parse;

import com.tioka.infra.api.ObjectStorageApi;
import com.tioka.knowledge.application.parse.ParserPort;
import com.tioka.ontology.api.doc.DocumentApi;
import com.tioka.ontology.api.doc.DocClause;
import com.tioka.ontology.api.doc.DocUnit;
import com.tioka.ontology.api.doc.DocumentView;
import com.tioka.ontology.api.doc.FileRef;
import com.tioka.ontology.api.docparse.ParseContract;
import com.tioka.ontology.api.docparse.ParseResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KNW-11：{@link DocParserAdapter} 直测（C-15 复核补齐——原实现缺失存储写入零覆盖）。
 * 假 DocumentApi 复刻 DocumentService.ingest 消费语义（dedup READY 直返 → storage.get 取字节
 * → sha256 复核），假 ObjectStorageApi 为内存 map：
 * ①二进制格式真调 storage.put 且 storageKey 传入 FileRef；②sha256 与字节一致；
 * ③dedup 二次调用不再写 storage（同键同内容跳过）；④文本格式不走 DocumentApi；
 * 另覆盖 put 失败 / ingest 失败异常向上传播（交 PARSE 阶段按阶段重试）。
 */
class DocParserAdapterTest {

    private static final String WS = "ws_c15_parser";

    private final StubStorage storage = new StubStorage();
    private final StubDocumentApi documents = new StubDocumentApi(storage);
    private final DocParserAdapter adapter = new DocParserAdapter(documents, storage);

    @Test
    void binaryFormatStoresBytesThenIngests() {
        byte[] bytes = "PK\u0003\u0004 fake-docx-bytes-for-parse".getBytes(StandardCharsets.UTF_8);

        ParseResult result = adapter.parse(
                new ParserPort.ParseRequest(WS, "治理规范.docx", null, bytes));

        String sha = TextStructuredParser.sha256Hex(bytes);
        // ① 真调 storage.put，键 = knw/ingest/{sha256}/{filename}，storageKey 传入 FileRef
        assertEquals(1, storage.putCalls.get());
        String expectedKey = "knw/ingest/" + sha + "/治理规范.docx";
        assertEquals(List.of(expectedKey), storage.putKeys);
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                storage.putContentTypes.get(0));
        assertTrue(java.util.Arrays.equals(bytes, storage.objects.get(expectedKey)),
                "存储对象内容与上传字节一致");
        assertEquals(1, documents.ingestCalls.get());
        FileRef ref = documents.ingestedRefs.get(0);
        assertEquals(expectedKey, ref.storageKey());
        assertEquals("治理规范.docx", ref.fileName());
        assertEquals(WS, ref.spaceId());
        assertEquals("knw", ref.sourceSystem());
        // ② sha256 与字节一致（假桩按 DocumentService 口径复核过，此处双断言口径）
        assertEquals(sha, ref.sha256());
        assertEquals(sha, result.fileHash());
        // 产物：parseId = docId，状态来自 DocumentView
        assertEquals("doc-stub-1", result.parseId());
        assertEquals(ParseContract.ST_READY, result.status());
        assertEquals("doc-1.0.0", result.parserVersion());
        assertEquals(1, result.units().size());
    }

    @Test
    void dedupSecondParseSkipsStorageWriteAndReusesDocId() {
        byte[] bytes = "same-binary-content".getBytes(StandardCharsets.UTF_8);

        ParseResult first = adapter.parse(
                new ParserPort.ParseRequest(WS, "report.pdf", null, bytes));
        ParseResult second = adapter.parse(
                new ParserPort.ParseRequest(WS, "report.pdf", null, bytes));

        // dedup 二次调用：ingest 再走（DocumentService 命中 READY 返回同一 docId），storage 不再写
        assertEquals(2, documents.ingestCalls.get());
        assertEquals(1, storage.putCalls.get(), "同键同内容跳过重复写入");
        assertEquals(first.parseId(), second.parseId());
        assertEquals("doc-stub-1", second.parseId());
        assertEquals(documents.ingestedRefs.get(0).storageKey(),
                documents.ingestedRefs.get(1).storageKey());
    }

    @Test
    void textFormatBypassesDocumentApiAndStorage() {
        byte[] bytes = "# 标题\n\n正文段落。".getBytes(StandardCharsets.UTF_8);

        ParseResult result = adapter.parse(
                new ParserPort.ParseRequest(WS, "note.md", null, bytes));

        assertEquals(0, documents.ingestCalls.get(), "文本格式不走 DocumentApi");
        assertEquals(0, storage.putCalls.get(), "文本格式不经对象交换层");
        assertEquals("text-1.0.0", result.parserVersion());
        assertTrue(result.parseId().startsWith("local:"));
        assertTrue(!result.units().isEmpty());
    }

    @Test
    void storagePutFailurePropagatesForStageRetry() {
        storage.failPut = true;

        RuntimeException e = assertThrows(IllegalStateException.class, () -> adapter.parse(
                new ParserPort.ParseRequest(WS, "scan.png", null, "img".getBytes(StandardCharsets.UTF_8))));
        assertEquals("storage put boom", e.getMessage());
        assertEquals(1, storage.putCalls.get(), "put 先于 ingest，失败即中止");
        assertEquals(0, documents.ingestCalls.get());
    }

    @Test
    void documentApiFailurePropagatesForStageRetry() {
        documents.failuresToInject.set(1);

        RuntimeException e = assertThrows(IllegalStateException.class, () -> adapter.parse(
                new ParserPort.ParseRequest(WS, "report.pdf", null, "doc".getBytes(StandardCharsets.UTF_8))));
        assertEquals("doc ingest boom", e.getMessage());
        assertEquals(1, storage.putCalls.get(), "存储对象已落（重试时同键同内容跳过重复写）");
        assertEquals(1, documents.ingestCalls.get());
    }

    // ------------------------------------------------------------------
    // 假 ObjectStorageApi：内存 map（键 → 字节）
    // ------------------------------------------------------------------

    private static final class StubStorage implements ObjectStorageApi {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        final AtomicInteger putCalls = new AtomicInteger();
        final List<String> putKeys = new CopyOnWriteArrayList<>();
        final List<String> putContentTypes = new CopyOnWriteArrayList<>();
        volatile boolean failPut;

        @Override
        public String put(String key, byte[] content, String contentType) {
            putCalls.incrementAndGet();
            if (failPut) {
                throw new IllegalStateException("storage put boom");
            }
            objects.put(key, content);
            putKeys.add(key);
            putContentTypes.add(contentType);
            return key;
        }

        @Override
        public byte[] get(String key) {
            return objects.get(key);
        }

        @Override
        public DownloadTicket issueDownloadTicket(String objectKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] redeemTicket(String ticket) {
            throw new UnsupportedOperationException();
        }
    }

    // ------------------------------------------------------------------
    // 假 DocumentApi：复刻 DocumentService.ingest 消费语义
    // （dedup READY 直返 → storage.get(storageKey) 取字节 → sha256 复核 → READY）
    // ------------------------------------------------------------------

    private static final class StubDocumentApi implements DocumentApi {
        private static final String DEDUP_PREFIX = "dedup:";

        final StubStorage storage;
        final Map<String, DocumentView> views = new ConcurrentHashMap<>();
        final Map<String, List<DocUnit>> unitsByDoc = new ConcurrentHashMap<>();
        final Map<String, String> readyByDedupKey = new ConcurrentHashMap<>();
        final List<FileRef> ingestedRefs = new CopyOnWriteArrayList<>();
        final AtomicInteger ingestCalls = new AtomicInteger();
        final AtomicInteger failuresToInject = new AtomicInteger();
        int seq;

        StubDocumentApi(StubStorage storage) {
            this.storage = storage;
        }

        @Override
        public String ingest(FileRef ref) {
            ingestCalls.incrementAndGet();
            ingestedRefs.add(ref);
            if (failuresToInject.getAndDecrement() > 0) {
                throw new IllegalStateException("doc ingest boom");
            }
            String space = ref.spaceId() == null || ref.spaceId().isBlank() ? "default" : ref.spaceId();
            String hit = readyByDedupKey.get(DEDUP_PREFIX + ref.sha256() + "|" + space);
            if (hit != null) {
                return hit;
            }
            byte[] bytes = storage.get(ref.storageKey());
            if (bytes == null || bytes.length == 0) {
                throw new IllegalArgumentException("storage object 不存在: " + ref.storageKey());
            }
            String actualHash = TextStructuredParser.sha256Hex(bytes);
            if (!actualHash.equalsIgnoreCase(ref.sha256())) {
                throw new IllegalArgumentException("sha256 与对象内容不一致");
            }
            String docId = "doc-stub-" + (++seq);
            readyByDedupKey.put(DEDUP_PREFIX + ref.sha256() + "|" + space, docId);
            views.put(docId, new DocumentView(docId, ParseContract.ST_READY,
                    ref.fileName(), ref.mimeType(), actualHash));
            unitsByDoc.put(docId, List.of(new DocUnit(
                    "u:1", 0, 1, ParseContract.T_PARAGRAPH, "正文", "正文", null, null)));
            return docId;
        }

        @Override
        public Optional<DocumentView> get(String docId) {
            return Optional.ofNullable(views.get(docId));
        }

        @Override
        public List<DocUnit> units(String docId, int fromSeq, int toSeq) {
            return unitsByDoc.getOrDefault(docId, List.of());
        }

        @Override
        public List<DocClause> clauses(String docId) {
            return List.of();
        }
    }
}

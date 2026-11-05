package com.tioka.knowledge.application;

import com.tioka.framework.id.Ids;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.knowledge.api.KnowledgeSearchApi;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import com.tioka.knowledge.infrastructure.embedding.EmbeddingClient;
import com.tioka.knowledge.infrastructure.parse.DocumentParser;
import com.tioka.knowledge.infrastructure.persistence.KbBaseDO;
import com.tioka.knowledge.infrastructure.persistence.KbChunkDO;
import com.tioka.knowledge.infrastructure.persistence.KbCitationDO;
import com.tioka.knowledge.infrastructure.persistence.KbDocumentDO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class KnowledgeService implements KnowledgeSearchApi {
    private final KnowledgeRepository repo;
    private final EmbeddingClient embeddingClient;
    private final DocumentParser documentParser;
    private final ObjectMapper mapper;
    private final boolean pgvector;
    private final TransactionTemplate tx;

    public KnowledgeService(KnowledgeRepository repo,
                            EmbeddingClient embeddingClient,
                            DocumentParser documentParser,
                            @Value("${tioka.knowledge.vector-backend:local}") String vectorBackend,
                            ObjectMapper mapper,
                            PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.repo = repo;
        this.embeddingClient = embeddingClient;
        this.documentParser = documentParser;
        this.pgvector = "pgvector".equalsIgnoreCase(vectorBackend);
        this.tx = new TransactionTemplate(transactionManager);
    }

    public KnowledgeBase createBase(String workspaceId, String name) {
        String id = Ids.newId("kb");
        KbBaseDO row = new KbBaseDO();
        row.setId(id);
        row.setWorkspaceId(workspaceId);
        row.setName(name);
        repo.insertBase(row);
        return new KnowledgeBase(id, workspaceId, name);
    }

    public Document ingest(String kbId, String title, String content, String sourceType) {
        return ingest(kbId, title, content, sourceType, "txt");
    }

    public Document ingest(String kbId, String title, String content, String sourceType, String format) {
        if (!repo.hasBase(kbId)) throw new BizException(ErrorCode.NOT_FOUND, "知识库不存在");
        String normalized = DocumentParser.normalizeText(content == null ? "" : content, format == null ? "txt" : format);
        return indexDocument(kbId, title, normalized, sourceType, format == null ? "txt" : format);
    }

    /** multipart 上传：真解析后入库。 */
    public Document ingestUpload(String kbId, String title, byte[] bytes, String filename) {
        if (!repo.hasBase(kbId)) throw new BizException(ErrorCode.NOT_FOUND, "知识库不存在");
        String format = DocumentParser.resolveFormat(filename);
        String text = documentParser.parse(bytes, filename);
        String docTitle = (title == null || title.isBlank())
                ? (filename == null || filename.isBlank() ? "upload" : filename)
                : title;
        return indexDocument(kbId, docTitle, text, "upload", format);
    }

    private Document indexDocument(String kbId, String title, String normalized, String sourceType, String format) {
        String id = Ids.newId("doc");
        String fmt = format == null ? "txt" : format;
        tx.executeWithoutResult(status -> {
            KbDocumentDO doc = new KbDocumentDO();
            doc.setId(id);
            doc.setKbId(kbId);
            doc.setTitle(title);
            doc.setSourceType(sourceType);
            doc.setStatus("EMBEDDING");
            doc.setFormat(fmt);
            repo.insertDocument(doc);
        });

        List<PreparedChunk> prepared;
        try {
            prepared = prepareChunks(id, kbId, normalized);
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            tx.executeWithoutResult(status -> markFailed(id, msg));
            throw e instanceof BizException be ? be
                    : new BizException(ErrorCode.INTERNAL, "嵌入失败: " + msg);
        }

        return tx.execute(status -> {
            for (PreparedChunk c : prepared) {
                insertChunk(c.chunkId(), c.docId(), c.kbId(), c.text(), c.ordinal(), c.vec());
            }
            KbDocumentDO doc = repo.findDocument(id);
            if (doc == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "document 不存在");
            }
            doc.setStatus("READY");
            doc.setErrorMessage(null);
            repo.updateDocument(doc);
            return new Document(id, kbId, title, sourceType, "READY", fmt);
        });
    }

    private List<PreparedChunk> prepareChunks(String docId, String kbId, String normalized) {
        List<PreparedChunk> out = new ArrayList<>();
        int size = 200;
        if (normalized == null || normalized.isEmpty()) {
            out.add(new PreparedChunk("chk_" + docId + "_0", docId, kbId, "", 0, embed("")));
            return out;
        }
        for (int i = 0, ordinal = 0; i < normalized.length(); i += size, ordinal++) {
            String text = normalized.substring(i, Math.min(normalized.length(), i + size));
            out.add(new PreparedChunk("chk_" + docId + "_" + ordinal, docId, kbId, text, ordinal, embed(text)));
        }
        return out;
    }

    private void markFailed(String docId, String errorMessage) {
        KbDocumentDO doc = repo.findDocument(docId);
        if (doc == null) {
            return;
        }
        doc.setStatus("FAILED");
        doc.setErrorMessage(errorMessage == null ? "" : errorMessage.substring(0, Math.min(errorMessage.length(), 2000)));
        repo.updateDocument(doc);
    }

    private record PreparedChunk(String chunkId, String docId, String kbId, String text, int ordinal, double[] vec) {}

    @Override
    public List<KnowledgeSearchApi.Hit> searchKnowledge(String kbId, String query, int topK) {
        return search(kbId, query, topK).stream()
                .map(h -> new KnowledgeSearchApi.Hit(h.docId(), h.chunkId(), h.text(), h.score(), h.title(), h.citationId()))
                .toList();
    }

    public List<RetrievalHit> search(String kbId, String query, int topK) {
        if (!repo.hasBase(kbId)) throw new BizException(ErrorCode.NOT_FOUND, "知识库不存在");
        String q = query == null ? "" : query.toLowerCase();
        double[] qVec = embed(q);
        int limit = Math.max(1, topK);

        List<ScoredChunk> keywordRanked = new ArrayList<>();
        List<ScoredChunk> vectorRanked = new ArrayList<>();

        if (pgvector) {
            // pgvector：近邻 SQL，禁止全表加载
            List<Map<String, Object>> rows = repo.searchNearest(kbId, toVectorLiteral(qVec), Math.max(limit * 5, 20));
            for (Map<String, Object> row : rows) {
                ScoredChunk sc = toScored(row, 0);
                double distance = row.get("distance") instanceof Number n ? n.doubleValue() : 1.0;
                double vector = 1.0 / (1.0 + Math.max(0, distance));
                int keyword = keywordScore(q, sc.text());
                if (keyword > 0 || q.isBlank()) {
                    keywordRanked.add(new ScoredChunk(sc.docId(), sc.chunkId(), sc.text(), sc.title(), sc.ordinal(), keyword));
                }
                if (vector > 0.01 || q.isBlank()) {
                    vectorRanked.add(new ScoredChunk(sc.docId(), sc.chunkId(), sc.text(), sc.title(), sc.ordinal(), vector));
                }
            }
        } else {
            // local：内存全量检索（开发/H2）；生产 pgvector 请走 SQL 分支
            List<Map<String, Object>> rows = repo.listChunksWithDoc(kbId);
            for (Map<String, Object> row : rows) {
                String text = String.valueOf(row.get("text"));
                double[] emb = fromJson(String.valueOf(row.get("embedding")));
                int keyword = keywordScore(q, text);
                double vector = cosine(qVec, emb);
                ScoredChunk sc = toScored(row, 0);
                if (keyword > 0 || q.isBlank()) {
                    keywordRanked.add(new ScoredChunk(sc.docId(), sc.chunkId(), sc.text(), sc.title(), sc.ordinal(), keyword));
                }
                if (vector > 0.01 || q.isBlank()) {
                    vectorRanked.add(new ScoredChunk(sc.docId(), sc.chunkId(), sc.text(), sc.title(), sc.ordinal(), vector));
                }
            }
        }

        keywordRanked.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        vectorRanked.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());

        final int rrfK = 60;
        Map<String, Double> fused = new HashMap<>();
        Map<String, ScoredChunk> byKey = new HashMap<>();
        for (int i = 0; i < keywordRanked.size(); i++) {
            ScoredChunk sc = keywordRanked.get(i);
            fused.merge(sc.chunkId(), 1.0 / (rrfK + i + 1), Double::sum);
            byKey.putIfAbsent(sc.chunkId(), sc);
        }
        for (int i = 0; i < vectorRanked.size(); i++) {
            ScoredChunk sc = vectorRanked.get(i);
            fused.merge(sc.chunkId(), 1.0 / (rrfK + i + 1), Double::sum);
            byKey.putIfAbsent(sc.chunkId(), sc);
        }

        return fused.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .map(e -> {
                    ScoredChunk sc = byKey.get(e.getKey());
                    String citationId = Ids.newId("cit");
                    KbCitationDO cit = new KbCitationDO();
                    cit.setId(citationId);
                    cit.setDocId(sc.docId());
                    cit.setChunkId(sc.chunkId());
                    cit.setText(sc.text());
                    cit.setTitle(sc.title());
                    cit.setOrdinal(sc.ordinal());
                    repo.insertCitation(cit);
                    return new RetrievalHit(sc.docId(), sc.chunkId(), sc.text(), e.getValue(), sc.title(), citationId);
                })
                .toList();
    }

    public Citation resolveCitation(String citationId) {
        KbCitationDO r = repo.findCitation(citationId);
        if (r == null) throw new BizException(ErrorCode.NOT_FOUND, "citation 不存在");
        return new Citation(r.getId(), r.getDocId(), r.getChunkId(), r.getText(), r.getTitle(), r.getOrdinal());
    }

    public ChunkView readChunk(String chunkId) {
        KbChunkDO c = repo.findChunk(chunkId);
        if (c == null) throw new BizException(ErrorCode.NOT_FOUND, "chunk 不存在");
        KbDocumentDO d = repo.findDocument(c.getDocId());
        return new ChunkView(c.getId(), c.getDocId(), c.getKbId(), c.getText(), c.getOrdinal(),
                d == null ? "" : d.getTitle());
    }

    public DocumentText readDocument(String docId, int maxChunks) {
        KbDocumentDO d = repo.findDocument(docId);
        if (d == null) throw new BizException(ErrorCode.NOT_FOUND, "document 不存在");
        List<KbChunkDO> chunks = repo.listChunksByDoc(docId);
        int limit = maxChunks <= 0 ? chunks.size() : Math.min(maxChunks, chunks.size());
        StringBuilder sb = new StringBuilder();
        List<String> chunkIds = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            KbChunkDO c = chunks.get(i);
            chunkIds.add(c.getId());
            if (sb.length() > 0) sb.append('\n');
            sb.append(c.getText() == null ? "" : c.getText());
        }
        return new DocumentText(d.getId(), d.getKbId(), d.getTitle(), sb.toString(), chunkIds);
    }

    private static int keywordScore(String q, String text) {
        if (text == null) return 0;
        String lower = text.toLowerCase();
        int keyword = 0;
        for (String token : q.split("\\s+")) {
            if (!token.isBlank() && lower.contains(token)) keyword++;
        }
        return keyword;
    }

    private static ScoredChunk toScored(Map<String, Object> row, double score) {
        Object ordinal = row.get("ordinal");
        int ord = ordinal instanceof Number n ? n.intValue() : 0;
        return new ScoredChunk(
                String.valueOf(row.get("doc_id")),
                String.valueOf(row.get("chunk_id")),
                String.valueOf(row.get("text")),
                String.valueOf(row.getOrDefault("title", "")),
                ord,
                score);
    }

    private String toJson(double[] v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private double[] fromJson(String json) {
        try {
            List<Double> list = mapper.readValue(json, new TypeReference<>() {});
            double[] v = new double[list.size()];
            for (int i = 0; i < list.size(); i++) v[i] = list.get(i);
            return v;
        } catch (Exception e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "embedding JSON 解析失败");
        }
    }

    private void insertChunk(String chunkId, String docId, String kbId, String text, int ordinal, double[] vec) {
        String json = toJson(vec);
        if (pgvector) {
            repo.insertChunkWithVector(chunkId, docId, kbId, text, ordinal, json, toVectorLiteral(vec));
        } else {
            KbChunkDO row = new KbChunkDO();
            row.setId(chunkId);
            row.setDocId(docId);
            row.setKbId(kbId);
            row.setText(text);
            row.setOrdinal(ordinal);
            row.setEmbedding(json);
            repo.insertChunk(row);
        }
    }

    private static String toVectorLiteral(double[] v) {
        return "[" + Arrays.stream(v).mapToObj(d -> Double.toString(d)).collect(Collectors.joining(",")) + "]";
    }

    private double[] embed(String text) {
        return embeddingClient.embed(text);
    }

    private static double cosine(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        double s = 0;
        for (int i = 0; i < n; i++) s += a[i] * b[i];
        return s;
    }

    private record ScoredChunk(String docId, String chunkId, String text, String title, int ordinal, double score) {}

    public record KnowledgeBase(String id, String workspaceId, String name) {}
    public record Document(String id, String kbId, String title, String sourceType, String status, String format) {}
    public record RetrievalHit(String docId, String chunkId, String text, double score, String title, String citationId) {}
    public record Citation(String id, String docId, String chunkId, String text, String title, int ordinal) {}
    public record ChunkView(String chunkId, String docId, String kbId, String text, int ordinal, String title) {}
    public record DocumentText(String docId, String kbId, String title, String text, List<String> chunkIds) {}
}

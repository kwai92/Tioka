package com.tioka.knowledge.application;

import com.tioka.framework.id.Ids;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.knowledge.api.KnowledgeSearchApi;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import com.tioka.knowledge.infrastructure.parse.DocumentParser;
import com.tioka.knowledge.infrastructure.persistence.KbBaseDO;
import com.tioka.knowledge.infrastructure.persistence.KbChunkDO;
import com.tioka.knowledge.infrastructure.persistence.KbCitationDO;
import com.tioka.knowledge.infrastructure.persistence.KbDocumentDO;
import com.tioka.model.api.BindingQueryApi;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.model.api.gateway.ModelPurpose;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class KnowledgeService implements KnowledgeSearchApi {
    private final KnowledgeRepository repo;
    private final ModelGateway modelGateway;
    private final BindingQueryApi bindings;
    private final PrincipalHolder principals;
    private final DocumentParser documentParser;
    private final int embeddingDim;
    private final TransactionTemplate tx;

    public KnowledgeService(KnowledgeRepository repo,
                            ModelGateway modelGateway,
                            BindingQueryApi bindings,
                            PrincipalHolder principals,
                            DocumentParser documentParser,
                            @Value("${tioka.knowledge.embedding-dim:1024}") int embeddingDim,
                            PlatformTransactionManager transactionManager) {
        this.repo = repo;
        this.modelGateway = modelGateway;
        this.bindings = bindings;
        this.principals = principals;
        this.documentParser = documentParser;
        this.embeddingDim = Math.max(1, embeddingDim);
        this.tx = new TransactionTemplate(transactionManager);
    }

    public KnowledgeBase createBase(String workspaceId, String name) {
        return createBase(workspaceId, name, null);
    }

    public KnowledgeBase createBase(String workspaceId, String name, String embeddingBindingId) {
        String bindingId = normalizeBinding(embeddingBindingId);
        String id = Ids.newId("kb");
        KbBaseDO row = new KbBaseDO();
        row.setId(id);
        row.setWorkspaceId(workspaceId);
        row.setName(name);
        row.setEmbeddingBindingId(bindingId);
        repo.insertBase(row);
        return new KnowledgeBase(id, workspaceId, name, bindingId);
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

    /** V119 / ReindexJob：按已有 chunk 文本经网关重嵌入。 */
    public void reindexDocument(String docId) {
        KbDocumentDO doc = repo.findDocument(docId);
        if (doc == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "document 不存在");
        }
        List<KbChunkDO> chunks = repo.listChunksByDoc(docId);
        String bindingId = resolveBindingId(doc.getKbId());
        for (KbChunkDO chunk : chunks) {
            double[] vec = embed(bindingId, chunk.getText());
            repo.updateChunkVector(chunk.getId(), toVectorLiteral(vec));
        }
        tx.executeWithoutResult(status -> {
            KbDocumentDO latest = repo.findDocument(docId);
            if (latest == null) {
                return;
            }
            latest.setStatus("READY");
            latest.setErrorMessage(null);
            repo.updateDocument(latest);
        });
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
                repo.insertChunkWithVector(c.chunkId(), c.docId(), c.kbId(), c.text(), c.ordinal(),
                        toVectorLiteral(c.vec()));
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
        String bindingId = resolveBindingId(kbId);
        List<PreparedChunk> out = new ArrayList<>();
        int size = 200;
        if (normalized == null || normalized.isEmpty()) {
            out.add(new PreparedChunk("chk_" + docId + "_0", docId, kbId, "", 0, embed(bindingId, "")));
            return out;
        }
        for (int i = 0, ordinal = 0; i < normalized.length(); i += size, ordinal++) {
            String text = normalized.substring(i, Math.min(normalized.length(), i + size));
            out.add(new PreparedChunk("chk_" + docId + "_" + ordinal, docId, kbId, text, ordinal,
                    embed(bindingId, text)));
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
        String bindingId = resolveBindingId(kbId);
        double[] qVec = embed(bindingId, q);
        int limit = Math.max(1, topK);

        List<ScoredChunk> keywordRanked = new ArrayList<>();
        List<ScoredChunk> vectorRanked = new ArrayList<>();

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

    private String normalizeBinding(String embeddingBindingId) {
        if (embeddingBindingId == null || embeddingBindingId.isBlank()) {
            return null;
        }
        BindingQueryApi.BindingInfo info = bindings.find(embeddingBindingId)
                .orElseThrow(() -> new BizException(ErrorCode.BAD_REQUEST, "嵌入 binding 不存在"));
        if (!"embedding".equalsIgnoreCase(info.kind())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "嵌入 binding 的 kind 必须是 embedding");
        }
        if (!info.enabled()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "嵌入 binding 已停用");
        }
        if (info.embeddingDim() == null || info.embeddingDim() != embeddingDim) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "嵌入 binding 维度须等于 tioka.knowledge.embedding-dim=" + embeddingDim);
        }
        return info.id();
    }

    private String resolveBindingId(String kbId) {
        KbBaseDO base = repo.findBase(kbId);
        if (base == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
        return base.getEmbeddingBindingId();
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

    private static String toVectorLiteral(double[] v) {
        return "[" + Arrays.stream(v).mapToObj(d -> Double.toString(d)).collect(Collectors.joining(",")) + "]";
    }

    private double[] embed(String bindingId, String text) {
        Principal principal = principals.find().orElse(SystemPrincipals.system());
        ModelCallContext ctx = new ModelCallContext(
                principal, null, null, null, null, ModelPurpose.EMBEDDING);
        ModelGateway.EmbeddingResult result = modelGateway.embed(
                ctx, new ModelGateway.EmbeddingRequest(bindingId, List.of(text == null ? "" : text)));
        if (result.vectors() == null || result.vectors().isEmpty()) {
            throw new BizException(ErrorCode.DEPENDENCY, "嵌入结果为空");
        }
        float[] src = result.vectors().get(0);
        if (src.length != embeddingDim) {
            throw new BizException(ErrorCode.DEPENDENCY,
                    "嵌入向量维度=" + src.length + " 与配置 embedding-dim=" + embeddingDim + " 不一致");
        }
        double[] out = new double[src.length];
        for (int i = 0; i < src.length; i++) {
            out[i] = src[i];
        }
        return out;
    }

    private record ScoredChunk(String docId, String chunkId, String text, String title, int ordinal, double score) {}

    public record KnowledgeBase(String id, String workspaceId, String name, String embeddingBindingId) {
        public KnowledgeBase(String id, String workspaceId, String name) {
            this(id, workspaceId, name, null);
        }
    }
    public record Document(String id, String kbId, String title, String sourceType, String status, String format) {}
    public record RetrievalHit(String docId, String chunkId, String text, double score, String title, String citationId) {}
    public record Citation(String id, String docId, String chunkId, String text, String title, int ordinal) {}
    public record ChunkView(String chunkId, String docId, String kbId, String text, int ordinal, String title) {}
    public record DocumentText(String docId, String kbId, String title, String text, List<String> chunkIds) {}
}

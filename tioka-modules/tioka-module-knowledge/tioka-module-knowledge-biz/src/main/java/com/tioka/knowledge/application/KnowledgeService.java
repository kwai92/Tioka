package com.tioka.knowledge.application;

import com.tioka.framework.id.Ids;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.AccessLevel;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.ResourceGuard;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.framework.security.VisibilityFilter;
import com.tioka.knowledge.api.KnowledgeSearchApi;
import com.tioka.knowledge.infrastructure.ChunkInsertCommand;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import com.tioka.knowledge.infrastructure.parse.DocumentParser;
import com.tioka.knowledge.infrastructure.persistence.KbBaseDO;
import com.tioka.knowledge.infrastructure.persistence.KbChunkDO;
import com.tioka.knowledge.infrastructure.persistence.KbCitationDO;
import com.tioka.knowledge.infrastructure.persistence.KbDocumentDO;
import com.tioka.model.api.ModelBindingQueryApi;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.model.api.gateway.ModelPurpose;
import com.tioka.system.api.authz.ResourceAclApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class KnowledgeService implements KnowledgeSearchApi {
    private static final int CHUNK_CHAR_SIZE = 200;
    private static final int ERROR_MESSAGE_MAX_LEN = 2000;

    private final KnowledgeRepository repo;
    private final ModelGateway modelGateway;
    private final ModelBindingQueryApi bindings;
    private final PrincipalHolder principals;
    private final ResourceGuard guard;
    private final ResourceAclApi resourceAcl;
    private final DocumentParser documentParser;
    private final int embeddingDim;
    private final TransactionTemplate tx;

    public KnowledgeService(KnowledgeRepository repo,
                            ModelGateway modelGateway,
                            ModelBindingQueryApi bindings,
                            PrincipalHolder principals,
                            ResourceGuard guard,
                            ResourceAclApi resourceAcl,
                            DocumentParser documentParser,
                            @Value("${tioka.knowledge.embedding-dim:1024}") int embeddingDim,
                            PlatformTransactionManager transactionManager) {
        this.repo = repo;
        this.modelGateway = modelGateway;
        this.bindings = bindings;
        this.principals = principals;
        this.guard = guard;
        this.resourceAcl = resourceAcl;
        this.documentParser = documentParser;
        this.embeddingDim = Math.max(1, embeddingDim);
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** FE-04：可用嵌入 binding（kind=embedding、enabled、维度对齐配置）。 */
    public List<EmbeddingModelBindingView> listEmbeddingBindings() {
        return bindings.listByKind("embedding").stream()
                .filter(ModelBindingQueryApi.ModelBindingInfo::enabled)
                .filter(b -> b.embeddingDim() != null && b.embeddingDim() == embeddingDim)
                .map(b -> {
                    String label = b.modelName() == null || b.modelName().isBlank()
                            ? b.id() + " (dim=" + b.embeddingDim() + ")"
                            : b.modelName() + " (" + b.id() + ", dim=" + b.embeddingDim() + ")";
                    return new EmbeddingModelBindingView(b.id(), b.embeddingDim(), b.enabled(), label);
                })
                .toList();
    }

    /** FE-04：当前库文档重嵌入进度（REINDEX_PENDING）。 */
    public ReindexProgress reindexProgress(String kbId) {
        if (!repo.hasBase(kbId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
        Map<String, Long> byStatus = repo.countDocumentsByStatus(kbId);
        long pending = byStatus.getOrDefault("REINDEX_PENDING", 0L);
        long ready = byStatus.getOrDefault("READY", 0L);
        long failed = byStatus.getOrDefault("FAILED", 0L);
        long embedding = byStatus.getOrDefault("EMBEDDING", 0L);
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        int percent = total == 0 ? 100 : (int) Math.round(100.0 * (total - pending) / total);
        return new ReindexProgress(kbId, pending, ready, failed, embedding, total, percent);
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
        Principal p = principals.find().orElse(null);
        if (p != null && p.userId() != null && !p.userId().isBlank()) {
            resourceAcl.ensureOwner("kb", id, p.userId());
        }
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
        String workspaceId = requireWorkspaceId(kbId);
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
                repo.insertChunkWithVector(new ChunkInsertCommand(
                        c.chunkId(), c.docId(), c.kbId(), workspaceId, c.text(), c.ordinal(),
                        toVectorLiteral(c.vec())));
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
        if (normalized == null || normalized.isEmpty()) {
            out.add(new PreparedChunk("chk_" + docId + "_0", docId, kbId, "", 0, embed(bindingId, "")));
            return out;
        }
        for (int i = 0, ordinal = 0; i < normalized.length(); i += CHUNK_CHAR_SIZE, ordinal++) {
            String text = normalized.substring(i, Math.min(normalized.length(), i + CHUNK_CHAR_SIZE));
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
        doc.setErrorMessage(errorMessage == null ? "" : errorMessage.substring(0,
                Math.min(errorMessage.length(), ERROR_MESSAGE_MAX_LEN)));
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
        return retrieve(kbId == null ? null : List.of(kbId), query, topK);
    }

    /**
     * KNW-02：权限过滤 → 向量召回（下推 ACL）。{@code kbIds == null} 表示当前空间内全部可见知识库。
     */
    public List<RetrievalHit> retrieve(List<String> kbIds, String query, int topK) {
        return retrieve(kbIds, query, topK, null);
    }

    /** DAT-02：带 maxDataLabel 的检索——有效标签（kb/doc 最大值）超限的文档被过滤掉（ADR-040）。 */
    public List<RetrievalHit> retrieve(List<String> kbIds, String query, int topK,
                                       com.tioka.framework.label.DataLabel maxDataLabel) {
        VisibilityFilter vf = guard.filter("kb", AccessLevel.USE);
        List<String> allowed = null;
        if (kbIds != null) {
            allowed = kbIds.stream()
                    .filter(id -> id != null && !id.isBlank())
                    .filter(id -> guard.allows("kb", id, AccessLevel.USE))
                    .distinct()
                    .toList();
            if (allowed.isEmpty()) {
                return List.of();
            }
        }
        Principal p = principals.current();
        String workspaceId = p.workspaceId();
        if (workspaceId == null || workspaceId.isBlank()) {
            return List.of();
        }

        String bindingId = resolveBindingForRetrieve(allowed);
        String q = query == null ? "" : query.toLowerCase();
        double[] qVec = embed(bindingId, q);
        int limit = Math.max(1, topK);

        Map<String, Object> params = new HashMap<>();
        if (!vf.all()) {
            params.putAll(vf.params());
        }
        params.put("vectorLiteral", toVectorLiteral(qVec));
        params.put("workspaceId", workspaceId);
        params.put("kbIds", allowed);
        params.put("k", Math.max(limit * 5, 20));
        params.put("vfAll", vf.all());
        params.put("vfSql", vf.sqlForTableAlias("b"));

        List<Map<String, Object>> rows = repo.searchVisible(params);
        List<ScoredChunk> keywordRanked = new ArrayList<>();
        List<ScoredChunk> vectorRanked = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            ScoredChunk sc = toScored(row, 0);
            double distance = row.get("distance") instanceof Number n ? n.doubleValue() : 1.0;
            double vector = 1.0 / (1.0 + Math.max(0, distance));
            int keyword = keywordScore(q, sc.text());
            if (keyword > 0 || q.isBlank()) {
                keywordRanked.add(new ScoredChunk(sc.docId(), sc.chunkId(), sc.kbId(), sc.text(), sc.title(),
                        sc.ordinal(), keyword));
            }
            if (vector > 0.01 || q.isBlank()) {
                vectorRanked.add(new ScoredChunk(sc.docId(), sc.chunkId(), sc.kbId(), sc.text(), sc.title(),
                        sc.ordinal(), vector));
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

        final com.tioka.framework.label.DataLabel labelCeiling = maxDataLabel;
        return fused.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                // DAT-02：标签过滤在 limit 之前（可返回少于 topK，登记口径）
                .filter(e -> {
                    if (labelCeiling == null) {
                        return true;
                    }
                    ScoredChunk sc0 = byKey.get(e.getKey());
                    return KbLabels.readable(effectiveLabel(sc0.kbId(), sc0.docId()), labelCeiling);
                })
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
                    return new RetrievalHit(sc.docId(), sc.chunkId(), sc.kbId(), sc.text(), e.getValue(),
                            sc.title(), citationId);
                })
                .toList();
    }

    /** DAT-02：hit 的有效数据标签 = max(kb_base.data_label, kb_document.data_label)。 */
    public com.tioka.framework.label.DataLabel effectiveLabel(String kbId, String docId) {
        String kbLabel = null;
        String docLabel = null;
        if (kbId != null) {
            KbBaseDO base = repo.findBase(kbId);
            kbLabel = base == null ? null : base.getDataLabel();
        }
        if (docId != null) {
            KbDocumentDO doc = repo.findDocument(docId);
            docLabel = doc == null ? null : doc.getDataLabel();
        }
        return KbLabels.effective(kbLabel, docLabel);
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
        ModelBindingQueryApi.ModelBindingInfo info = bindings.find(embeddingBindingId)
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

    private String requireWorkspaceId(String kbId) {
        KbBaseDO base = repo.findBase(kbId);
        if (base == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
        if (base.getWorkspaceId() == null || base.getWorkspaceId().isBlank()) {
            throw new BizException(ErrorCode.INTERNAL, "知识库缺少 workspaceId");
        }
        return base.getWorkspaceId();
    }

    private String resolveBindingId(String kbId) {
        KbBaseDO base = repo.findBase(kbId);
        if (base == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
        return base.getEmbeddingBindingId();
    }

    private String resolveBindingForRetrieve(List<String> allowedKbIds) {
        if (allowedKbIds != null && !allowedKbIds.isEmpty()) {
            return resolveBindingId(allowedKbIds.get(0));
        }
        Set<String> visible = guard.visibleIds("kb", AccessLevel.USE);
        if (visible.isEmpty()) {
            return null;
        }
        return resolveBindingId(visible.iterator().next());
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
        Object kb = row.get("kb_id");
        return new ScoredChunk(
                String.valueOf(row.get("doc_id")),
                String.valueOf(row.get("chunk_id")),
                kb == null ? "" : String.valueOf(kb),
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

    private record ScoredChunk(String docId, String chunkId, String kbId, String text, String title,
                               int ordinal, double score) {}

    public record KnowledgeBase(String id, String workspaceId, String name, String embeddingBindingId) {
        public KnowledgeBase(String id, String workspaceId, String name) {
            this(id, workspaceId, name, null);
        }
    }
    public record EmbeddingModelBindingView(String id, Integer embeddingDim, boolean enabled, String label) {}
    public record ReindexProgress(String kbId, long pending, long ready, long failed, long embedding,
                                  long total, int percent) {}
    public record Document(String id, String kbId, String title, String sourceType, String status, String format) {}
    public record RetrievalHit(String docId, String chunkId, String kbId, String text, double score, String title,
                               String citationId) {
        /** 兼容旧调用：无 kbId。 */
        public RetrievalHit(String docId, String chunkId, String text, double score, String title, String citationId) {
            this(docId, chunkId, "", text, score, title, citationId);
        }
    }
    public record Citation(String id, String docId, String chunkId, String text, String title, int ordinal) {}
    public record ChunkView(String chunkId, String docId, String kbId, String text, int ordinal, String title) {}
    public record DocumentText(String docId, String kbId, String title, String text, List<String> chunkIds) {}
}

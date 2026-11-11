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
import com.tioka.knowledge.application.chunk.StructuredChunker;
import com.tioka.knowledge.application.chunk.StructuredChunker.ChunkPolicy;
import com.tioka.knowledge.application.parse.ParseSupport;
import com.tioka.knowledge.application.parse.ParserPort;
import com.tioka.knowledge.infrastructure.ChunkInsertCommand;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import com.tioka.knowledge.infrastructure.persistence.KbBaseDO;
import com.tioka.knowledge.infrastructure.persistence.KbChunkDO;
import com.tioka.knowledge.infrastructure.persistence.KbCitationDO;
import com.tioka.knowledge.infrastructure.persistence.KbDocumentDO;
import com.tioka.model.api.ModelBindingQueryApi;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.model.api.gateway.ModelPurpose;
import com.tioka.ontology.api.docparse.ParseResult;
import com.tioka.system.api.authz.ResourceAclApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * KnowledgeService（KNW-02/05/11/03）：同步入库路径与检索入口。
 *
 * <p>KNW-11：同步路径解析同样经 {@link ParserPort}（DOC 域共用解析服务，ObjectProvider 弱依赖；
 * 条件装配缺席时降级 TextStructuredParser 本地结构化解析——「旧行为不变」纪律对 KNW-11 不适用，
 * 行为变更为设计本意：解析产物溯源落 kb_document，切块从固定 200 字升级为结构感知父子块）。
 * KNW-03：{@link StructuredChunker} 按知识库策略切块；检索返回子块引用 + 父块正文上下文
 * （同一父块只随第一个命中返回一次）；{@code chunkPolicyJson} 策略修改后经既有重建路径重嵌入。
 */
@Service
public class KnowledgeService implements KnowledgeSearchApi {
    private static final int ERROR_MESSAGE_MAX_LEN = 2000;

    private final KnowledgeRepository repo;
    private final ModelGateway modelGateway;
    private final ModelBindingQueryApi bindings;
    private final PrincipalHolder principals;
    private final ResourceGuard guard;
    private final ResourceAclApi resourceAcl;
    private final ObjectProvider<ParserPort> parserPort;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final int embeddingDim;
    private final TransactionTemplate tx;
    /** KNW-05：异步入库任务服务（开关 tioka.knowledge.ingest.async.enabled 缺省关闭时缺席）。 */
    private final ObjectProvider<KnowledgeIngestJobService> ingestJobs;
    public KnowledgeService(KnowledgeRepository repo,
                            ModelGateway modelGateway,
                            ModelBindingQueryApi bindings,
                            PrincipalHolder principals,
                            ResourceGuard guard,
                            ResourceAclApi resourceAcl,
                            ObjectProvider<ParserPort> parserPort,
                            com.fasterxml.jackson.databind.ObjectMapper mapper,
                            @Value("${tioka.knowledge.embedding-dim:1024}") int embeddingDim,
                            PlatformTransactionManager transactionManager,
                            org.springframework.beans.factory.ObjectProvider<KnowledgeIngestJobService> ingestJobs) {
        this.repo = repo;
        this.modelGateway = modelGateway;
        this.bindings = bindings;
        this.principals = principals;
        this.guard = guard;
        this.resourceAcl = resourceAcl;
        this.parserPort = parserPort;
        this.mapper = mapper;
        this.embeddingDim = Math.max(1, embeddingDim);
        this.tx = new TransactionTemplate(transactionManager);
        this.ingestJobs = ingestJobs;
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

    /** KNW-03：切块策略修改（落 kb_base 列）并触发既有重建路径（逐文档重嵌入）。 */
    public int updateChunkPolicy(String kbId, String chunkPolicyJson, Boolean contextEnhancement) {
        if (!repo.hasBase(kbId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
        ChunkPolicy.fromJson(chunkPolicyJson, mapper); // 非法 JSON 静默回退自动，不阻断
        repo.updateBaseChunkPolicy(kbId, chunkPolicyJson, contextEnhancement);
        int triggered = 0;
        for (KbDocumentDO doc : repo.listDocumentsByKb(kbId)) {
            if (!"READY".equals(doc.getStatus())) {
                continue;
            }
            doc.setStatus("REINDEX_PENDING");
            repo.updateDocument(doc);
            reindexDocument(doc.getId());
            triggered++;
        }
        return triggered;
    }

    public Document ingest(String kbId, String title, String content, String sourceType) {
        return ingest(kbId, title, content, sourceType, "txt");
    }

    public Document ingest(String kbId, String title, String content, String sourceType, String format) {
        if (!repo.hasBase(kbId)) throw new BizException(ErrorCode.NOT_FOUND, "知识库不存在");
        // KNW-05：异步开关打开 → 只写文档登记 + 入库任务 + outbox（同一事务），立即返回
        KnowledgeIngestJobService jobs = ingestJobs == null ? null : ingestJobs.getIfAvailable();
        if (jobs != null) {
            return jobs.submitText(kbId, title, content, sourceType, format);
        }
        String fmt = format == null ? "txt" : format;
        // KNW-11：同步路径同样经 ParserPort（结构化解析产物）
        ParseResult result = ParseSupport.parse(parserPort, new ParserPort.ParseRequest(
                requireWorkspaceId(kbId), title, fmt,
                (content == null ? "" : content).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return indexDocument(kbId, title, result, sourceType, fmt);
    }

    /** multipart 上传：经 ParserPort 真解析后结构化入库（KNW-11）。 */
    public Document ingestUpload(String kbId, String title, byte[] bytes, String filename) {
        if (!repo.hasBase(kbId)) throw new BizException(ErrorCode.NOT_FOUND, "知识库不存在");
        // KNW-05：异步开关打开 → 登记任务立即返回（解析在 PARSE 阶段完成）
        KnowledgeIngestJobService jobs = ingestJobs == null ? null : ingestJobs.getIfAvailable();
        if (jobs != null) {
            return jobs.submitUpload(kbId, title, bytes, filename);
        }
        String format = ParserPort.resolveFormat(filename);
        String docTitle = (title == null || title.isBlank())
                ? (filename == null || filename.isBlank() ? "upload" : filename)
                : title;
        ParseResult result = ParseSupport.parse(parserPort, new ParserPort.ParseRequest(
                requireWorkspaceId(kbId), filename, format, bytes));
        return indexDocument(kbId, docTitle, result, "upload", format);
    }

    /** F39：入库任务接受 ChangeEvent 作为来源（KNW-06 只需产生事件）。 */
    public Document ingestChange(String kbId, com.tioka.knowledge.api.IngestChangeSource change, String content) {
        if (!repo.hasBase(kbId)) throw new BizException(ErrorCode.NOT_FOUND, "知识库不存在");
        KnowledgeIngestJobService jobs = ingestJobs == null ? null : ingestJobs.getIfAvailable();
        if (jobs != null) {
            return jobs.submitChange(kbId, change, content);
        }
        // 开关关闭（异步未启用）：按同步行为入库（ChangeEvent 无文件语义，按 txt 处理）
        String title = change == null || change.title() == null || change.title().isBlank()
                ? (change == null ? "change" : change.externalId()) : change.title();
        return ingest(kbId, title, content, "datasource", "txt");
    }

    /** V119 / ReindexJob：按已有 chunk 文本经网关重嵌入（KNW-03：父块无向量，跳过）。 */
    public void reindexDocument(String docId) {
        KbDocumentDO doc = repo.findDocument(docId);
        if (doc == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "document 不存在");
        }
        List<KbChunkDO> chunks = repo.listChunksByDoc(docId).stream()
                .filter(c -> !ChunkInsertCommand.TYPE_PARENT.equals(c.getChunkType()))
                .toList();
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

    private Document indexDocument(String kbId, String title, ParseResult result,
                                   String sourceType, String format) {
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
            ParseSupport.applyParseMeta(doc, result, mapper);
            repo.insertDocument(doc);
        });

        List<PreparedRow> prepared;
        try {
            prepared = prepareRows(id, kbId, title, result);
        } catch (RuntimeException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            tx.executeWithoutResult(status -> markFailed(id, msg));
            throw e instanceof BizException be ? be
                    : new BizException(ErrorCode.INTERNAL, "嵌入失败: " + msg);
        }

        return tx.execute(status -> {
            for (PreparedRow c : prepared) {
                repo.insertChunkWithVector(new ChunkInsertCommand(
                        c.chunkId(), c.docId(), c.kbId(), workspaceId, c.text(), c.ordinal(),
                        c.vec() == null ? null : toVectorLiteral(c.vec()), 0,
                        c.chunkType(), c.parentChunkId(), c.pageStart(), c.pageEnd(),
                        c.blockIds(), c.contextPrefix()));
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

    /** KNW-03：结构感知父子块准备（策略读 kb_base；子块嵌入输入带上下文前缀，文本列只存正文）。 */
    private List<PreparedRow> prepareRows(String docId, String kbId, String docTitle, ParseResult result) {
        String bindingId = resolveBindingId(kbId);
        KbBaseDO base = repo.findBase(kbId);
        ChunkPolicy policy = ChunkPolicy.fromJson(base == null ? null : base.getChunkPolicyJson(), mapper);
        boolean contextEnhancement = base == null || base.getContextEnhancement() == null
                || base.getContextEnhancement();
        List<StructuredChunker.ChunkPiece> pieces =
                new StructuredChunker(policy, contextEnhancement).chunk(result, docTitle);
        List<PreparedRow> out = new ArrayList<>(pieces.size());
        String[] ids = new String[pieces.size()];
        for (int i = 0; i < pieces.size(); i++) {
            ids[i] = "chk_" + docId + "_" + i;
        }
        for (int i = 0; i < pieces.size(); i++) {
            StructuredChunker.ChunkPiece p = pieces.get(i);
            boolean parent = StructuredChunker.TYPE_PARENT.equals(p.chunkType());
            String parentChunkId = !parent && p.parentOrdinal() >= 0 ? ids[p.parentOrdinal()] : null;
            String embedInput = contextEnhancement && p.contextPrefix() != null && !p.contextPrefix().isBlank()
                    ? p.contextPrefix() + "\n" + p.text() : p.text();
            double[] vec = parent ? null : embed(bindingId, embedInput);
            out.add(new PreparedRow(ids[i], docId, kbId, p.text(), i, vec,
                    p.chunkType(), parentChunkId, p.pageStart(), p.pageEnd(),
                    p.blockIds(), p.contextPrefix()));
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

    private record PreparedRow(String chunkId, String docId, String kbId, String text, int ordinal,
                               double[] vec, String chunkType, String parentChunkId,
                               Integer pageStart, Integer pageEnd, List<String> blockIds,
                               String contextPrefix) {}

    @Override
    public List<KnowledgeSearchApi.Hit> searchKnowledge(String kbId, String query, int topK) {
        return search(kbId, query, topK).stream()
                .map(h -> new KnowledgeSearchApi.Hit(h.docId(), h.chunkId(), h.text(), h.score(), h.title(), h.citationId()))
                .toList();
    }    public List<RetrievalHit> search(String kbId, String query, int topK) {
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
            ScoredChunk sc = toScored(row, 0, mapper);
            double distance = row.get("distance") instanceof Number n ? n.doubleValue() : 1.0;
            double vector = 1.0 / (1.0 + Math.max(0, distance));
            // KNW-03：全文索引口径 = 标题路径前缀 + 正文（context_enhancement）
            int keyword = keywordScore(q, sc.indexableText());
            if (keyword > 0 || q.isBlank()) {
                keywordRanked.add(sc);
            }
            if (vector > 0.01 || q.isBlank()) {
                vectorRanked.add(sc);
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
        List<RetrievalHit> hits = fused.entrySet().stream()
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
                            sc.title(), citationId, sc.chunkType(), sc.parentChunkId(), null,
                            sc.pageStart(), sc.pageEnd(), sc.blockIds());
                })
                .toList();
        // KNW-03：父块正文上下文——同一父块只随第一个（最高分）命中返回一次
        return attachParentContext(hits);
    }

    /**
     * 父块正文组装：命中按分序遍历，每个 parentChunkId 只在首个命中携带 parentText；
     * 无父块引用（存量 CHILD/降级数据）的命中 parentText 为 null。
     */
    private List<RetrievalHit> attachParentContext(List<RetrievalHit> hits) {
        Set<String> parentIds = hits.stream()
                .map(RetrievalHit::parentChunkId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (parentIds.isEmpty()) {
            return hits;
        }
        Map<String, KbChunkDO> parents = new HashMap<>();
        for (KbChunkDO c : repo.listChunksByIds(parentIds)) {
            parents.put(c.getId(), c);
        }
        Set<String> attached = new HashSet<>();
        List<RetrievalHit> out = new ArrayList<>(hits.size());
        for (RetrievalHit h : hits) {
            String pid = h.parentChunkId();
            if (pid != null && !pid.isBlank() && attached.add(pid)) {
                KbChunkDO parent = parents.get(pid);
                if (parent != null) {
                    out.add(h.withParentText(parent.getText()));
                    continue;
                }
            }
            out.add(h);
        }
        return out;
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

    private static ScoredChunk toScored(Map<String, Object> row, double score,
                                        com.fasterxml.jackson.databind.ObjectMapper mapper) {
        Object ordinal = row.get("ordinal");
        int ord = ordinal instanceof Number n ? n.intValue() : 0;
        Object kb = row.get("kb_id");
        Integer pageStart = row.get("page_start") instanceof Number n ? n.intValue() : null;
        Integer pageEnd = row.get("page_end") instanceof Number n ? n.intValue() : null;
        List<String> blockIds = readBlockIds(row.get("block_ids_json"), mapper);
        String prefix = row.get("context_prefix") == null ? null : String.valueOf(row.get("context_prefix"));
        String type = row.get("chunk_type") == null ? ChunkInsertCommand.TYPE_CHILD
                : String.valueOf(row.get("chunk_type"));
        String parent = row.get("parent_chunk_id") == null ? null : String.valueOf(row.get("parent_chunk_id"));
        return new ScoredChunk(
                String.valueOf(row.get("doc_id")),
                String.valueOf(row.get("chunk_id")),
                kb == null ? "" : String.valueOf(kb),
                String.valueOf(row.get("text")),
                String.valueOf(row.getOrDefault("title", "")),
                ord,
                score,
                type, parent, pageStart, pageEnd, blockIds, prefix);
    }

    @SuppressWarnings("unchecked")
    private static List<String> readBlockIds(Object json, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        if (json == null) {
            return List.of();
        }
        String s = String.valueOf(json);
        if (s.isBlank() || "null".equals(s)) {
            return List.of();
        }
        try {
            List<String> ids = mapper.readValue(s, List.class);
            return ids == null ? List.of() : ids;
        } catch (Exception e) {
            return List.of();
        }
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
                               int ordinal, double score, String chunkType, String parentChunkId,
                               Integer pageStart, Integer pageEnd, List<String> blockIds,
                               String contextPrefix) {
        /** 全文索引口径：标题路径前缀 + 正文（context_enhancement）。 */
        String indexableText() {
            return contextPrefix == null || contextPrefix.isBlank() ? text
                    : contextPrefix + "\n" + text;
        }
    }

    public record KnowledgeBase(String id, String workspaceId, String name, String embeddingBindingId) {
        public KnowledgeBase(String id, String workspaceId, String name) {
            this(id, workspaceId, name, null);
        }
    }
    public record EmbeddingModelBindingView(String id, Integer embeddingDim, boolean enabled, String label) {}
    public record ReindexProgress(String kbId, long pending, long ready, long failed, long embedding,
                                  long total, int percent) {}
    public record Document(String id, String kbId, String title, String sourceType, String status, String format) {}
    /**
     * 检索命中（KNW-03）：返回子块用于引用定位；父块正文（parentText）用于模型上下文，
     * 同一父块只随第一个命中返回一次。旧 7 参构造兼容存量调用（结构字段为 null）。
     */
    public record RetrievalHit(String docId, String chunkId, String kbId, String text, double score, String title,
                               String citationId, String chunkType, String parentChunkId, String parentText,
                               Integer pageStart, Integer pageEnd, List<String> blockIds) {
        /** 兼容旧调用：无 kbId。 */
        public RetrievalHit(String docId, String chunkId, String text, double score, String title, String citationId) {
            this(docId, chunkId, "", text, score, title, citationId, null, null, null, null, null, null);
        }

        /** KNW-03：附带父块正文（不可变拷贝）。 */
        public RetrievalHit withParentText(String parentText) {
            return new RetrievalHit(docId, chunkId, kbId, text, score, title, citationId,
                    chunkType, parentChunkId, parentText, pageStart, pageEnd, blockIds);
        }
    }
    public record Citation(String id, String docId, String chunkId, String text, String title, int ordinal) {}
    public record ChunkView(String chunkId, String docId, String kbId, String text, int ordinal, String title) {}
    public record DocumentText(String docId, String kbId, String title, String text, List<String> chunkIds) {}
}

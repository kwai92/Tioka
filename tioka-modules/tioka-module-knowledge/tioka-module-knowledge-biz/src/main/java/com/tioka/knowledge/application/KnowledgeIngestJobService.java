package com.tioka.knowledge.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.id.Ids;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.knowledge.api.IngestChangeSource;
import com.tioka.knowledge.api.mq.KnowledgeMqTopics;
import com.tioka.knowledge.application.chunk.StructuredChunker;
import com.tioka.knowledge.application.chunk.StructuredChunker.ChunkPolicy;
import com.tioka.knowledge.application.parse.ParseSupport;
import com.tioka.knowledge.application.parse.ParserPort;
import com.tioka.knowledge.infrastructure.ChunkInsertCommand;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import com.tioka.knowledge.infrastructure.persistence.KbBaseDO;
import com.tioka.knowledge.infrastructure.persistence.KbDocumentDO;
import com.tioka.knowledge.infrastructure.persistence.KbIngestJobDO;
import com.tioka.model.api.ModelBindingQueryApi;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.model.api.gateway.ModelPurpose;
import com.tioka.ontology.api.docparse.ParseResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * KNW-05：入库任务服务（M3-06 §3.1 / ADR-018 五阶段流水线）。
 *
 * <p>提交路径（上传 / 数据源变更 / ChangeEvent，约束 F39）：同一事务内写文档登记 + 入库任务 +
 * outbox 消息，立即返回。消费者按阶段（PARSE → CHUNK → EMBED → SWAP）推进，每阶段写任务状态；
 * 失败按阶段重试最多 3 次，耗尽标 FAILED（文档状态同步失败，知识库页经状态计数可见）。
 * SWAP：新版本分块全部写入后，单事务切换 {@code kb_document.active_version} 并删除旧版本分块；
 * 检索 SQL 只看当前版本（{@code KbChunkMapper} 过滤 {@code doc_version = active_version}）。
 *
 * <p>KNW-11：PARSE 阶段经 {@link ParserPort}（DOC 域共用解析服务，ObjectProvider 弱依赖）
 * 产结构化解析产物 ParseResult（溯源/质量指标/警告落 kb_document）；解析 FAILED 抛异常交按阶段重试，
 * PARTIAL 照常入库并把失败页与降级原因（warnings）写到文档上。
 * KNW-03：CHUNK 阶段 {@link StructuredChunker} 按知识库策略（kb_base.chunk_policy_json）
 * 切父子块；EMBED 阶段子块嵌入（context_enhancement 开启时输入带标题路径前缀）、
 * 父块无向量落库供上下文组装。
 *
 * <p>仅由 {@code KnowledgeIngestAsyncAutoConfiguration} 在
 * {@code tioka.knowledge.ingest.async.enabled=true} 时装配；缺省关闭时
 * {@code KnowledgeService} 走既有同步路径。
 */
public class KnowledgeIngestJobService {

    static final int MAX_ATTEMPTS = 3;
    private static final int ERROR_MESSAGE_MAX_LEN = 2000;

    static final String STAGE_PARSE = "PARSE";
    static final String STAGE_CHUNK = "CHUNK";
    static final String STAGE_EMBED = "EMBED";
    static final String STAGE_SWAP = "SWAP";

    static final String STATUS_QUEUED = "QUEUED";
    static final String STATUS_RUNNING = "RUNNING";
    static final String STATUS_DONE = "DONE";
    static final String STATUS_FAILED = "FAILED";

    private final KnowledgeRepository repo;
    private final ModelGateway modelGateway;
    private final ModelBindingQueryApi bindings;
    private final PrincipalHolder principals;
    private final ObjectProvider<ParserPort> parserPort;
    private final EventPublisher publisher;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final int embeddingDim;

    public KnowledgeIngestJobService(KnowledgeRepository repo,
                                     ModelGateway modelGateway,
                                     ModelBindingQueryApi bindings,
                                     PrincipalHolder principals,
                                     ObjectProvider<ParserPort> parserPort,
                                     EventPublisher publisher,
                                     ObjectMapper mapper,
                                     @Value("${tioka.knowledge.embedding-dim:1024}") int embeddingDim,
                                     PlatformTransactionManager transactionManager) {
        this.repo = repo;
        this.modelGateway = modelGateway;
        this.bindings = bindings;
        this.principals = principals;
        this.parserPort = parserPort;
        this.publisher = publisher;
        this.mapper = mapper;
        this.embeddingDim = Math.max(1, embeddingDim);
        this.tx = new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------
    // 提交：文档登记 + 入库任务 + outbox 同一事务，立即返回
    // ------------------------------------------------------------------

    public KnowledgeService.Document submitText(String kbId, String title, String content,
                                                String sourceType, String format) {
        String fmt = format == null ? "txt" : format;
        Map<String, Object> payload = basePayload("TEXT", title, fmt);
        payload.put("content", content == null ? "" : content);
        return submit(kbId, title, sourceType == null ? "upload" : sourceType, fmt, payload,
                null, null, null, "TEXT");
    }

    public KnowledgeService.Document submitUpload(String kbId, String title, byte[] bytes, String filename) {
        String format = ParserPort.resolveFormat(filename);
        String docTitle = (title == null || title.isBlank())
                ? (filename == null || filename.isBlank() ? "upload" : filename)
                : title;
        Map<String, Object> payload = basePayload("UPLOAD", docTitle, format);
        payload.put("base64", bytes == null ? "" : java.util.Base64.getEncoder().encodeToString(bytes));
        payload.put("filename", filename == null ? "" : filename);
        return submit(kbId, docTitle, "upload", format, payload, null, null, bytes, "UPLOAD");
    }

    /** F39：ChangeEvent 来源（aclPrincipals 可选）；同 external_id+external_version 或内容未变则跳过，否则复用 doc 行递增版本。 */
    public KnowledgeService.Document submitChange(String kbId, IngestChangeSource change, String content) {
        if (!repo.hasBase(kbId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
        if (change == null || change.externalId() == null || change.externalId().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "ChangeEvent 缺少 externalId");
        }
        String title = change.title() == null || change.title().isBlank() ? change.externalId() : change.title();
        Map<String, Object> payload = basePayload("CHANGE_EVENT", title, "txt");
        payload.put("content", content == null ? "" : content);
        return submit(kbId, title, "datasource", "txt", payload, "ds", change, null, "CHANGE_EVENT");
    }

    private KnowledgeService.Document submit(String kbId, String title, String sourceType, String format,
                                             Map<String, Object> payload, String sourceRef,
                                             IngestChangeSource change, byte[] uploadBytes, String jobSourceType) {
        if (!repo.hasBase(kbId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
        String contentHash = uploadBytes != null ? hashOf(uploadBytes)
                : hashOf(String.valueOf(payload.get("content")));
        // content_hash 去重：同库同哈希且非 FAILED 的文档 → 内容未变跳过（ADR-018 登记步）
        KbDocumentDO same = repo.findDocumentsByHash(kbId, contentHash).stream()
                .filter(d -> !"FAILED".equals(d.getStatus()))
                .findFirst()
                .orElse(null);
        if (same != null) {
            return new KnowledgeService.Document(same.getId(), kbId, same.getTitle(), same.getSourceType(),
                    same.getStatus(), same.getFormat());
        }

        // F39：ChangeEvent 复用同 external_id 的 doc 行，版本 = active_version + 1
        final KbDocumentDO target;
        final int docVersion;
        if (change != null) {
            final String externalVersion = change.externalVersion() == null ? "" : change.externalVersion();
            KbDocumentDO found = repo.findDocumentsByExternalId(kbId, change.externalId()).stream()
                    .filter(d -> !"FAILED".equals(d.getStatus()))
                    .reduce((a, b) -> activeVersionOf(a) >= activeVersionOf(b) ? a : b)
                    .orElse(null);
            if (found != null && externalVersion.equals(
                    found.getExternalVersion() == null ? "" : found.getExternalVersion())) {
                return new KnowledgeService.Document(found.getId(), kbId, found.getTitle(),
                        found.getSourceType(), found.getStatus(), found.getFormat());
            }
            target = found;
            docVersion = found == null ? 1 : activeVersionOf(found) + 1;
        } else {
            target = null;
            docVersion = 1;
        }
        final String docId = target == null ? Ids.newId("doc") : target.getId();

        String jobId = Ids.newId("job");
        String payloadJson = writeJson(payload);
        Principal principal = principals.find().orElse(SystemPrincipals.system());
        String runAsJson = writeJson(Map.of(
                "userId", principal.userId() == null ? "" : principal.userId(),
                "workspaceId", principal.workspaceId() == null ? "" : principal.workspaceId()));

        return tx.execute(status -> {
            if (target == null) {
                KbDocumentDO doc = new KbDocumentDO();
                doc.setId(docId);
                doc.setKbId(kbId);
                doc.setTitle(title);
                doc.setSourceType(sourceType);
                doc.setStatus(STATUS_QUEUED);
                doc.setFormat(format);
                doc.setContentHash(contentHash);
                doc.setActiveVersion(0);
                if (change != null) {
                    doc.setDatasourceId(sourceRef);
                    doc.setExternalId(change.externalId());
                    doc.setExternalVersion(change.externalVersion());
                    if (change.aclPrincipals() != null && !change.aclPrincipals().isEmpty()) {
                        doc.setAclPrincipals(writeJson(change.aclPrincipals()));
                    }
                }
                repo.insertDocument(doc);
            } else {
                KbDocumentDO doc = repo.findDocument(docId);
                doc.setTitle(title);
                doc.setStatus(STATUS_QUEUED);
                doc.setErrorMessage(null);
                doc.setContentHash(contentHash);
                doc.setExternalVersion(change.externalVersion());
                if (change.aclPrincipals() != null && !change.aclPrincipals().isEmpty()) {
                    doc.setAclPrincipals(writeJson(change.aclPrincipals()));
                }
                repo.updateDocument(doc);
            }

            KbIngestJobDO job = new KbIngestJobDO();
            job.setId(jobId);
            job.setDocId(docId);
            job.setDocVersion(docVersion);
            job.setStage(STAGE_PARSE);
            job.setStatus(STATUS_QUEUED);
            job.setAttempts(0);
            job.setRunAsJson(runAsJson);
            job.setSourceType(jobSourceType);
            job.setSourceRef(sourceRef);
            job.setAclPrincipalsJson(change == null || change.aclPrincipals() == null || change.aclPrincipals().isEmpty()
                    ? null : writeJson(change.aclPrincipals()));
            job.setPayloadJson(payloadJson);
            repo.insertJob(job);

            publisher.publish(new DomainEvent(
                    KnowledgeMqTopics.INGEST_REQUESTED,
                    KnowledgeMqTopics.TOPIC,
                    jobId,
                    Map.of("jobId", jobId, "docId", docId, "kbId", kbId),
                    0,
                    null,
                    "kb_ingest_job:" + jobId));
            return new KnowledgeService.Document(docId, kbId, title, sourceType, STATUS_QUEUED, format);
        });
    }

    // ------------------------------------------------------------------
    // 消费：按阶段推进；失败按阶段重试最多 3 次
    // ------------------------------------------------------------------

    /**
     * 推进一个阶段。返回 true 表示任务仍在推进中（QUEUED/RUNNING）；
     * DONE / FAILED / 不存在返回 false。由消费者按事件触发调用；重试经 Broker 重投或再次触发。
     */
    public boolean process(String jobId) {
        KbIngestJobDO job = repo.findJob(jobId);
        if (job == null || STATUS_DONE.equals(job.getStatus()) || STATUS_FAILED.equals(job.getStatus())) {
            return false;
        }
        KbDocumentDO doc = repo.findDocument(job.getDocId());
        if (doc == null) {
            markJobFailed(job, "document 不存在");
            return false;
        }
        try {
            repo.updateJob(touch(job, STATUS_RUNNING));
            switch (job.getStage() == null ? STAGE_PARSE : job.getStage()) {
                case STAGE_PARSE -> doParse(job);
                case STAGE_CHUNK -> doChunk(job);
                case STAGE_EMBED -> doEmbed(job, doc);
                case STAGE_SWAP -> doSwap(job, doc);
                default -> throw new BizException(ErrorCode.INTERNAL, "未知阶段: " + job.getStage());
            }
            return !STATUS_DONE.equals(job.getStatus());
        } catch (RuntimeException e) {
            int attempts = (job.getAttempts() == null ? 0 : job.getAttempts()) + 1;
            job.setAttempts(attempts);
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            job.setError(msg.length() > ERROR_MESSAGE_MAX_LEN ? msg.substring(0, ERROR_MESSAGE_MAX_LEN) : msg);
            if (attempts >= MAX_ATTEMPTS) {
                markJobFailed(job, job.getError());
            } else {
                job.setStatus(STATUS_QUEUED);
                repo.updateJob(job);
            }
            return !STATUS_FAILED.equals(job.getStatus());
        }
    }

    /**
     * PARSE：经 ParserPort（KNW-11，DOC 域共用解析服务）产结构化解析产物；
     * FAILED 抛异常交按阶段重试，PARTIAL 照常入库（警告落文档）。
     */
    private void doParse(KbIngestJobDO job) {
        Map<String, Object> payload = readPayload(job);
        String kind = String.valueOf(payload.get("kind"));
        String format = String.valueOf(payload.getOrDefault("format", "txt"));
        byte[] bytes;
        String filename = null;
        if ("UPLOAD".equals(kind)) {
            bytes = java.util.Base64.getDecoder().decode(String.valueOf(payload.getOrDefault("base64", "")));
            filename = String.valueOf(payload.getOrDefault("filename", ""));
        } else {
            bytes = String.valueOf(payload.getOrDefault("content", "")).getBytes(StandardCharsets.UTF_8);
        }
        KbDocumentDO doc = repo.findDocument(job.getDocId());
        String workspaceId = doc == null ? "" : workspaceIdOf(doc);
        ParseResult result = ParseSupport.parse(parserPort,
                new ParserPort.ParseRequest(workspaceId, filename, format, bytes));
        ParseSupport.assertNotFailed(result);
        payload.put("parseResult", writeJson(result));
        job.setPayloadJson(writeJson(payload));
        if (doc != null) {
            ParseSupport.applyParseMeta(doc, result, mapper);
            repo.updateDocument(doc);
        }
        advanceStage(job, STAGE_CHUNK);
    }

    /** CHUNK：KNW-03 结构感知切块（策略读 kb_base.chunk_policy_json，缺省自动）。 */
    private void doChunk(KbIngestJobDO job) {
        Map<String, Object> payload = readPayload(job);
        KbDocumentDO doc = repo.findDocument(job.getDocId());
        if (doc == null) {
            throw new BizException(ErrorCode.INTERNAL, "document 不存在");
        }
        ParseResult result = readParseResult(payload);
        KbBaseDO base = repo.findBase(doc.getKbId());
        ChunkPolicy policy = ChunkPolicy.fromJson(base == null ? null : base.getChunkPolicyJson(), mapper);
        boolean contextEnhancement = base == null || base.getContextEnhancement() == null
                || base.getContextEnhancement();
        List<StructuredChunker.ChunkPiece> pieces =
                new StructuredChunker(policy, contextEnhancement).chunk(result, doc.getTitle());

        List<Map<String, Object>> chunks = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            StructuredChunker.ChunkPiece p = pieces.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", p.chunkType());
            m.put("parentOrdinal", p.parentOrdinal());
            m.put("text", p.text());
            m.put("contextPrefix", p.contextPrefix());
            m.put("pageStart", p.pageStart());
            m.put("pageEnd", p.pageEnd());
            m.put("blockIds", p.blockIds());
            m.put("ordinal", i);
            chunks.add(m);
        }
        payload.put("chunks", chunks);
        job.setPayloadJson(writeJson(payload));
        advanceStage(job, STAGE_EMBED);
    }

    /**
     * EMBED：子块批量嵌入（context_enhancement 开启时输入带标题路径前缀）并带 doc_version 写入
     * （先清同版本分块，重试幂等）；父块无向量落库（chunk_type=PARENT），仅供上下文组装。
     */
    private void doEmbed(KbIngestJobDO job, KbDocumentDO doc) {
        Map<String, Object> payload = readPayload(job);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chunks =
                (List<Map<String, Object>>) payload.getOrDefault("chunks", List.of());
        String bindingId = resolveBindingId(doc.getKbId());
        String workspaceId = workspaceIdOf(doc);
        KbBaseDO base = repo.findBase(doc.getKbId());
        boolean contextEnhancement = base == null || base.getContextEnhancement() == null
                || base.getContextEnhancement();
        repo.deleteChunksOfVersion(doc.getId(), job.getDocVersion());
        Map<Integer, String> parentIds = new LinkedHashMap<>();
        for (Map<String, Object> c : chunks) {
            int ordinal = c.get("ordinal") instanceof Number n ? n.intValue() : 0;
            String type = String.valueOf(c.getOrDefault("type", ChunkInsertCommand.TYPE_CHILD));
            String chunkId = "chk_" + doc.getId() + "_v" + job.getDocVersion() + "_" + ordinal;
            String text = String.valueOf(c.getOrDefault("text", ""));
            if (ChunkInsertCommand.TYPE_PARENT.equals(type)) {
                parentIds.put(ordinal, chunkId);
                repo.insertChunkWithVector(new ChunkInsertCommand(
                        chunkId, doc.getId(), doc.getKbId(), workspaceId,
                        text, ordinal, null, job.getDocVersion(),
                        ChunkInsertCommand.TYPE_PARENT, null,
                        intOf(c.get("pageStart")), intOf(c.get("pageEnd")),
                        listOfStrings(c.get("blockIds")), stringOrNull(c.get("contextPrefix"))));
            }
        }
        for (Map<String, Object> c : chunks) {
            int ordinal = c.get("ordinal") instanceof Number n ? n.intValue() : 0;
            String type = String.valueOf(c.getOrDefault("type", ChunkInsertCommand.TYPE_CHILD));
            if (ChunkInsertCommand.TYPE_PARENT.equals(type)) {
                continue;
            }
            String text = String.valueOf(c.getOrDefault("text", ""));
            String prefix = stringOrNull(c.get("contextPrefix"));
            String embedInput = contextEnhancement && prefix != null && !prefix.isBlank()
                    ? prefix + "\n" + text : text;
            double[] vec = embed(bindingId, embedInput);
            Integer parentOrdinal = c.get("parentOrdinal") instanceof Number n ? n.intValue() : -1;
            String parentChunkId = parentOrdinal >= 0 ? parentIds.get(parentOrdinal) : null;
            repo.insertChunkWithVector(new ChunkInsertCommand(
                    "chk_" + doc.getId() + "_v" + job.getDocVersion() + "_" + ordinal,
                    doc.getId(), doc.getKbId(), workspaceId,
                    text, ordinal, toVectorLiteral(vec), job.getDocVersion(),
                    ChunkInsertCommand.TYPE_CHILD, parentChunkId,
                    intOf(c.get("pageStart")), intOf(c.get("pageEnd")),
                    listOfStrings(c.get("blockIds")), prefix));
        }
        advanceStage(job, STAGE_SWAP);
    }

    /** SWAP：单事务切换 active_version 并删除旧版本分块（检索永远看不到半条数据）。 */
    private void doSwap(KbIngestJobDO job, KbDocumentDO doc) {
        tx.executeWithoutResult(status -> {
            KbDocumentDO latest = repo.findDocument(doc.getId());
            if (latest == null) {
                return;
            }
            latest.setActiveVersion(job.getDocVersion());
            latest.setStatus("READY");
            latest.setErrorMessage(null);
            repo.updateDocument(latest);
            repo.deleteChunksBeforeVersion(doc.getId(), job.getDocVersion());
        });
        job.setStatus(STATUS_DONE);
        job.setError(null);
        repo.updateJob(job);
    }

    private static int activeVersionOf(KbDocumentDO doc) {
        return doc.getActiveVersion() == null ? 0 : doc.getActiveVersion();
    }

    private void advanceStage(KbIngestJobDO job, String nextStage) {
        job.setStage(nextStage);
        job.setStatus(STATUS_QUEUED);
        job.setError(null);
        repo.updateJob(job);
    }

    private void markJobFailed(KbIngestJobDO job, String errorMessage) {
        job.setStatus(STATUS_FAILED);
        job.setError(errorMessage);
        repo.updateJob(job);
        KbDocumentDO doc = repo.findDocument(job.getDocId());
        if (doc != null) {
            doc.setStatus("FAILED");
            doc.setErrorMessage(errorMessage == null ? "" : errorMessage);
            repo.updateDocument(doc);
        }
    }

    private static KbIngestJobDO touch(KbIngestJobDO job, String status) {
        job.setStatus(status);
        return job;
    }

    // ------------------------------------------------------------------
    // 支撑
    // ------------------------------------------------------------------

    private String workspaceIdOf(KbDocumentDO doc) {
        KbBaseDO base = repo.findBase(doc.getKbId());
        return base == null ? "" : base.getWorkspaceId();
    }

    private String resolveBindingId(String kbId) {
        KbBaseDO base = repo.findBase(kbId);
        if (base == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "知识库不存在");
        }
        return base.getEmbeddingBindingId();
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

    private static String toVectorLiteral(double[] v) {
        return "[" + Arrays.stream(v).mapToObj(Double::toString).collect(Collectors.joining(",")) + "]";
    }

    static String hashOf(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes == null ? new byte[0] : bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    static String hashOf(String content) {
        return hashOf((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> basePayload(String kind, String title, String format) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", kind);
        payload.put("title", title == null ? "" : title);
        payload.put("format", format);
        return payload;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPayload(KbIngestJobDO job) {
        try {
            return mapper.readValue(job.getPayloadJson() == null ? "{}" : job.getPayloadJson(), Map.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "任务载荷损坏: " + e.getMessage());
        }
    }

    private ParseResult readParseResult(Map<String, Object> payload) {
        try {
            String json = String.valueOf(payload.getOrDefault("parseResult", "{}"));
            ParseResult result = mapper.readValue(json, ParseResult.class);
            if (result == null || result.units() == null) {
                throw new BizException(ErrorCode.INTERNAL, "载荷缺解析产物");
            }
            return result;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "解析产物还原失败: " + e.getMessage());
        }
    }

    private static Integer intOf(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static String stringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return s.isEmpty() || "null".equals(s) ? null : s;
    }

    @SuppressWarnings("unchecked")
    private static List<String> listOfStrings(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        return (List<String>) list;
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL, "载荷序列化失败: " + e.getMessage());
        }
    }
}

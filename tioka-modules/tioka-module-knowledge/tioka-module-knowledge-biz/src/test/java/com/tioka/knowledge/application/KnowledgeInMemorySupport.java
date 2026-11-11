package com.tioka.knowledge.application;

import com.tioka.framework.messaging.EventPublisher;
import com.tioka.framework.security.AccessLevel;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.framework.security.ResourceGuard;
import com.tioka.framework.security.VisibilityFilter;
import com.tioka.knowledge.infrastructure.ChunkInsertCommand;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import com.tioka.knowledge.infrastructure.embedding.HashEmbeddingClient;
import com.tioka.knowledge.infrastructure.parse.DocumentParser;
import com.tioka.model.api.ModelBindingQueryApi;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.knowledge.infrastructure.persistence.KbBaseDO;
import com.tioka.knowledge.infrastructure.persistence.KbChunkDO;
import com.tioka.knowledge.infrastructure.persistence.KbCitationDO;
import com.tioka.knowledge.infrastructure.persistence.KbDocumentDO;
import com.tioka.knowledge.infrastructure.persistence.KbDatasourceDO;
import com.tioka.system.api.authz.ResourceAclApi;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/** 单测用内存 Repository 替身（不碰 DB）。 */
final class KnowledgeInMemorySupport {
    private KnowledgeInMemorySupport() {}

    static KnowledgeRepository repo() {
        Map<String, KbBaseDO> bases = new ConcurrentHashMap<>();
        Map<String, KbDocumentDO> docs = new ConcurrentHashMap<>();
        Map<String, KbChunkDO> chunks = new ConcurrentHashMap<>();
        Map<String, KbCitationDO> citations = new ConcurrentHashMap<>();
        Map<String, KbDatasourceDO> datasources = new ConcurrentHashMap<>();
        Map<String, com.tioka.knowledge.infrastructure.persistence.KbIngestJobDO> jobs = new ConcurrentHashMap<>();

        return new KnowledgeRepository(null, null, null, null, null, null, null, null) {
            @Override public void insertBase(KbBaseDO row) { bases.put(row.getId(), row); }
            @Override public boolean hasBase(String id) { return bases.containsKey(id); }
            @Override public KbBaseDO findBase(String id) { return bases.get(id); }
            @Override public boolean lockBaseExists(String id) { return bases.containsKey(id); }
            @Override public void insertDocument(KbDocumentDO row) { docs.put(row.getId(), row); }
            @Override public void updateDocument(KbDocumentDO row) { docs.put(row.getId(), row); }
            @Override public KbDocumentDO findDocument(String id) { return docs.get(id); }
            @Override public List<KbDocumentDO> listDocumentsByStatus(String status, int limit) {
                return docs.values().stream()
                        .filter(d -> status.equals(d.getStatus()))
                        .limit(limit)
                        .toList();
            }
            @Override public Map<String, Long> countDocumentsByStatus(String kbId) {
                Map<String, Long> out = new HashMap<>();
                for (KbDocumentDO d : docs.values()) {
                    if (!kbId.equals(d.getKbId())) continue;
                    String status = d.getStatus() == null ? "" : d.getStatus();
                    out.merge(status, 1L, Long::sum);
                }
                return out;
            }
            @Override public void insertChunkWithVector(ChunkInsertCommand cmd) {
                KbChunkDO row = new KbChunkDO();
                row.setId(cmd.id());
                row.setDocId(cmd.docId());
                row.setKbId(cmd.kbId());
                row.setWorkspaceId(cmd.workspaceId());
                row.setText(cmd.text());
                row.setOrdinal(cmd.ordinal());
                row.setDocVersion(cmd.docVersion());
                chunks.put(cmd.id(), row);
            }
            @Override public void updateChunkVector(String id, String vectorLiteral) { /* no-op in memory */ }
            @Override public KbChunkDO findChunk(String id) { return chunks.get(id); }
            @Override public List<KbChunkDO> listChunksByDoc(String docId) {
                return chunks.values().stream()
                        .filter(c -> docId.equals(c.getDocId()))
                        .sorted(Comparator.comparingInt(c -> c.getOrdinal() == null ? 0 : c.getOrdinal()))
                        .collect(Collectors.toList());
            }
            @Override public void insertCitation(KbCitationDO row) { citations.put(row.getId(), row); }
            @Override public KbCitationDO findCitation(String id) { return citations.get(id); }
            @Override public List<Map<String, Object>> searchNearest(String kbId, String vectorLiteral, int k) {
                return searchVisible(Map.of("kbIds", List.of(kbId), "k", k)).stream().limit(k).toList();
            }
            @Override public List<Map<String, Object>> searchVisible(Map<String, Object> params) {
                @SuppressWarnings("unchecked")
                List<String> kbIds = (List<String>) params.get("kbIds");
                int k = params.get("k") instanceof Number n ? n.intValue() : 20;
                List<Map<String, Object>> out = new ArrayList<>();
                for (KbChunkDO c : chunks.values()) {
                    if (kbIds != null && !kbIds.isEmpty() && !kbIds.contains(c.getKbId())) {
                        continue;
                    }
                    KbDocumentDO d = docs.get(c.getDocId());
                    Map<String, Object> m = new HashMap<>();
                    m.put("chunk_id", c.getId());
                    m.put("doc_id", c.getDocId());
                    m.put("kb_id", c.getKbId());
                    m.put("text", c.getText());
                    m.put("ordinal", c.getOrdinal());
                    m.put("title", d == null ? "" : d.getTitle());
                    m.put("distance", 0.1);
                    out.add(m);
                }
                return out.stream().limit(k).toList();
            }
            @Override public void insertDatasource(KbDatasourceDO row) { datasources.put(row.getId(), row); }
            @Override public List<KbDatasourceDO> listDatasources(String kbId) {
                return datasources.values().stream().filter(d -> kbId.equals(d.getKbId())).toList();
            }
            @Override public KbDatasourceDO findDatasource(String id) { return datasources.get(id); }

            // ---- KNW-05：入库任务与版本化分块（内存替身） ----
            @Override public void insertJob(com.tioka.knowledge.infrastructure.persistence.KbIngestJobDO row) {
                jobs.put(row.getId(), row);
            }
            @Override public com.tioka.knowledge.infrastructure.persistence.KbIngestJobDO findJob(String id) {
                return jobs.get(id);
            }
            @Override public void updateJob(com.tioka.knowledge.infrastructure.persistence.KbIngestJobDO row) {
                jobs.put(row.getId(), row);
            }
            @Override public List<KbDocumentDO> findDocumentsByHash(String kbId, String contentHash) {
                return docs.values().stream()
                        .filter(d -> kbId.equals(d.getKbId()) && contentHash.equals(d.getContentHash()))
                        .toList();
            }
            @Override public List<KbDocumentDO> findDocumentsByExternalId(String kbId, String externalId) {
                return docs.values().stream()
                        .filter(d -> kbId.equals(d.getKbId()) && externalId.equals(d.getExternalId()))
                        .toList();
            }
            @Override public int deleteChunksBeforeVersion(String docId, int activeVersion) {
                int before = chunks.size();
                chunks.values().removeIf(c -> docId.equals(c.getDocId())
                        && c.getDocVersion() != null && c.getDocVersion() < activeVersion);
                return before - chunks.size();
            }
            @Override public int deleteChunksOfVersion(String docId, int docVersion) {
                int before = chunks.size();
                chunks.values().removeIf(c -> docId.equals(c.getDocId())
                        && c.getDocVersion() != null && c.getDocVersion() == docVersion);
                return before - chunks.size();
            }
        };
    }

    static KnowledgeService service(KnowledgeRepository repo) {
        HashEmbeddingClient hash = new HashEmbeddingClient(32);
        ModelGateway gateway = new ModelGateway() {
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
                List<String> inputs = request.inputs() == null ? List.of() : request.inputs();
                List<float[]> vectors = new ArrayList<>();
                for (String text : inputs) {
                    double[] d = hash.embed(text);
                    float[] f = new float[d.length];
                    for (int i = 0; i < d.length; i++) {
                        f[i] = (float) d[i];
                    }
                    vectors.add(f);
                }
                return new EmbeddingResult(vectors, hash.dimension(), inputs.size(), "bnd_hash");
            }

            @Override
            public RerankResult rerank(ModelCallContext ctx, RerankRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        ModelBindingQueryApi bindings = bindingId -> Optional.empty();
        Principal testUser = new Principal("1", "ws", PrincipalType.USER, Set.of("builder"), Set.of("*"), "test");
        PrincipalHolder holder = new PrincipalHolder() {
            @Override public Principal current() { return testUser; }
            @Override public java.util.Optional<Principal> find() { return java.util.Optional.of(testUser); }
            @Override public <T> T runAs(Principal principal, java.util.function.Supplier<T> body) { return body.get(); }
        };
        ResourceGuard guard = new ResourceGuard() {
            @Override public void check(String resourceType, String resourceId, AccessLevel level) {}
            @Override public boolean allows(String resourceType, String resourceId, AccessLevel level) { return true; }
            @Override public Set<String> visibleIds(String resourceType, AccessLevel level) { return Set.of(); }
            @Override public VisibilityFilter filter(String resourceType, AccessLevel level) {
                return VisibilityFilter.allowAll();
            }
        };
        ResourceAclApi acls = new ResourceAclApi() {
            @Override public void ensureOwner(String resourceType, String resourceId, String userId) {}
            @Override public List<AclEntry> list(String resourceType, String resourceId) { return List.of(); }
            @Override public void replace(String resourceType, String resourceId, List<AclEntry> entries) {}
        };
        return new KnowledgeService(repo, gateway, bindings, holder, guard, acls, new DocumentParser(), 32,
                new PassthroughTxManager(), null);
    }

    /** KNW-05：异步开关打开场景（ObjectProvider 返回给定任务服务）。 */
    static KnowledgeService asyncService(KnowledgeRepository repo, KnowledgeIngestJobService jobService) {
        return new KnowledgeService(repo, gateway(repo), bindings(), holder(), guard(), acls(),
                new DocumentParser(), 32, new PassthroughTxManager(),
                new ObjectProvider<>() {
                    @Override public KnowledgeIngestJobService getIfAvailable() { return jobService; }
                    @Override public KnowledgeIngestJobService getIfUnique() { return jobService; }
                    @Override public KnowledgeIngestJobService getObject() { return jobService; }
                    @Override public KnowledgeIngestJobService getObject(Object... args) { return jobService; }
                });
    }

    /** KNW-05：入库任务服务（哈希嵌入 + 捕获事件）。 */
    static KnowledgeIngestJobService jobService(KnowledgeRepository repo, EventPublisher publisher,
                                                ModelGateway gatewayOverride) {
        return new KnowledgeIngestJobService(repo,
                gatewayOverride != null ? gatewayOverride : gateway(repo),
                bindings(), holder(), new DocumentParser(), publisher,
                new com.fasterxml.jackson.databind.ObjectMapper(), 32, new PassthroughTxManager());
    }

    private static com.tioka.model.api.gateway.ModelGateway gateway(KnowledgeRepository repo) {
        HashEmbeddingClient hash = new HashEmbeddingClient(32);
        return new com.tioka.model.api.gateway.ModelGateway() {
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
                List<String> inputs = request.inputs() == null ? List.of() : request.inputs();
                List<float[]> vectors = new ArrayList<>();
                for (String text : inputs) {
                    double[] d = hash.embed(text);
                    float[] f = new float[d.length];
                    for (int i = 0; i < d.length; i++) {
                        f[i] = (float) d[i];
                    }
                    vectors.add(f);
                }
                return new EmbeddingResult(vectors, hash.dimension(), inputs.size(), "bnd_hash");
            }

            @Override
            public RerankResult rerank(ModelCallContext ctx, RerankRequest request) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static ModelBindingQueryApi bindings() {
        return bindingId -> Optional.empty();
    }

    private static PrincipalHolder holder() {
        Principal testUser = new Principal("1", "ws", PrincipalType.USER, Set.of("builder"), Set.of("*"), "test");
        return new PrincipalHolder() {
            @Override public Principal current() { return testUser; }
            @Override public java.util.Optional<Principal> find() { return java.util.Optional.of(testUser); }
            @Override public <T> T runAs(Principal principal, java.util.function.Supplier<T> body) { return body.get(); }
        };
    }

    private static ResourceGuard guard() {
        return new ResourceGuard() {
            @Override public void check(String resourceType, String resourceId, AccessLevel level) {}
            @Override public boolean allows(String resourceType, String resourceId, AccessLevel level) { return true; }
            @Override public Set<String> visibleIds(String resourceType, AccessLevel level) { return Set.of(); }
            @Override public VisibilityFilter filter(String resourceType, AccessLevel level) {
                return VisibilityFilter.allowAll();
            }
        };
    }

    private static ResourceAclApi acls() {
        return new ResourceAclApi() {
            @Override public void ensureOwner(String resourceType, String resourceId, String userId) {}
            @Override public List<AclEntry> list(String resourceType, String resourceId) { return List.of(); }
            @Override public void replace(String resourceType, String resourceId, List<AclEntry> entries) {}
        };
    }

    /** 单测无真实事务：回调直接执行。 */
    static final class PassthroughTxManager implements org.springframework.transaction.PlatformTransactionManager {
        @Override
        public org.springframework.transaction.TransactionStatus getTransaction(
                org.springframework.transaction.TransactionDefinition definition) {
            return new org.springframework.transaction.support.SimpleTransactionStatus();
        }

        @Override
        public void commit(org.springframework.transaction.TransactionStatus status) {}

        @Override
        public void rollback(org.springframework.transaction.TransactionStatus status) {}
    }
}

package com.tioka.knowledge.application;

import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.SystemPrincipals;
import com.tioka.knowledge.infrastructure.KnowledgeRepository;
import com.tioka.knowledge.infrastructure.embedding.HashEmbeddingClient;
import com.tioka.knowledge.infrastructure.parse.DocumentParser;
import com.tioka.model.api.gateway.ModelCallContext;
import com.tioka.model.api.gateway.ModelGateway;
import com.tioka.knowledge.infrastructure.persistence.KbBaseDO;
import com.tioka.knowledge.infrastructure.persistence.KbChunkDO;
import com.tioka.knowledge.infrastructure.persistence.KbCitationDO;
import com.tioka.knowledge.infrastructure.persistence.KbDocumentDO;
import com.tioka.knowledge.infrastructure.persistence.KbDatasourceDO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

        return new KnowledgeRepository(null, null, null, null, null, null, null) {
            @Override public void insertBase(KbBaseDO row) { bases.put(row.getId(), row); }
            @Override public boolean hasBase(String id) { return bases.containsKey(id); }
            @Override public boolean lockBaseExists(String id) { return bases.containsKey(id); }
            @Override public void insertDocument(KbDocumentDO row) { docs.put(row.getId(), row); }
            @Override public void updateDocument(KbDocumentDO row) { docs.put(row.getId(), row); }
            @Override public KbDocumentDO findDocument(String id) { return docs.get(id); }
            @Override public void insertChunk(KbChunkDO row) { chunks.put(row.getId(), row); }
            @Override public void insertChunkWithVector(String id, String docId, String kbId, String text, int ordinal, String embedding, String vectorLiteral) {
                KbChunkDO row = new KbChunkDO();
                row.setId(id);
                row.setDocId(docId);
                row.setKbId(kbId);
                row.setText(text);
                row.setOrdinal(ordinal);
                row.setEmbedding(embedding);
                chunks.put(id, row);
            }
            @Override public KbChunkDO findChunk(String id) { return chunks.get(id); }
            @Override public List<KbChunkDO> listChunksByDoc(String docId) {
                return chunks.values().stream()
                        .filter(c -> docId.equals(c.getDocId()))
                        .sorted(Comparator.comparingInt(c -> c.getOrdinal() == null ? 0 : c.getOrdinal()))
                        .collect(Collectors.toList());
            }
            @Override public void insertCitation(KbCitationDO row) { citations.put(row.getId(), row); }
            @Override public KbCitationDO findCitation(String id) { return citations.get(id); }
            @Override public List<Map<String, Object>> listChunksWithDoc(String kbId) {
                List<Map<String, Object>> out = new ArrayList<>();
                for (KbChunkDO c : chunks.values()) {
                    if (!kbId.equals(c.getKbId())) continue;
                    KbDocumentDO d = docs.get(c.getDocId());
                    Map<String, Object> m = new HashMap<>();
                    m.put("chunk_id", c.getId());
                    m.put("doc_id", c.getDocId());
                    m.put("text", c.getText());
                    m.put("ordinal", c.getOrdinal());
                    m.put("embedding", c.getEmbedding());
                    m.put("title", d == null ? "" : d.getTitle());
                    out.add(m);
                }
                return out;
            }
            @Override public List<Map<String, Object>> searchNearest(String kbId, String vectorLiteral, int k) {
                return listChunksWithDoc(kbId).stream().limit(k).toList();
            }
            @Override public void insertDatasource(KbDatasourceDO row) { datasources.put(row.getId(), row); }
            @Override public List<KbDatasourceDO> listDatasources(String kbId) {
                return datasources.values().stream().filter(d -> kbId.equals(d.getKbId())).toList();
            }
            @Override public KbDatasourceDO findDatasource(String id) { return datasources.get(id); }
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
        PrincipalHolder holder = new PrincipalHolder() {
            @Override public Principal current() { return SystemPrincipals.system(); }
            @Override public java.util.Optional<Principal> find() { return java.util.Optional.of(SystemPrincipals.system()); }
            @Override public <T> T runAs(Principal principal, java.util.function.Supplier<T> body) { return body.get(); }
        };
        return new KnowledgeService(repo, gateway, holder, new DocumentParser(), "local",
                new com.fasterxml.jackson.databind.ObjectMapper(), new PassthroughTxManager());
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

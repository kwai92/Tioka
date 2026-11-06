package com.tioka.knowledge.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.knowledge.infrastructure.mapper.*;
import com.tioka.knowledge.infrastructure.persistence.*;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class KnowledgeRepository {
    private final KbBaseMapper bases;
    private final KbDocumentMapper documents;
    private final KbChunkMapper chunks;
    private final KbCitationMapper citations;
    private final KbWikiMapper wikis;
    private final KbDatasourceMapper datasources;

    public KnowledgeRepository(KbBaseMapper bases, KbDocumentMapper documents, KbChunkMapper chunks,
                               KbCitationMapper citations, KbWikiMapper wikis, KbDatasourceMapper datasources) {
        this.bases = bases;
        this.documents = documents;
        this.chunks = chunks;
        this.citations = citations;
        this.wikis = wikis;
        this.datasources = datasources;
    }

    public void insertBase(KbBaseDO row) { bases.insert(row); }
    public boolean hasBase(String id) { return bases.selectById(id) != null; }
    public void insertDocument(KbDocumentDO row) { documents.insert(row); }
    public void updateDocument(KbDocumentDO row) { documents.updateById(row); }
    public KbDocumentDO findDocument(String id) { return documents.selectById(id); }
    public void insertChunk(KbChunkDO row) { chunks.insert(row); }
    public void insertChunkWithVector(String id, String docId, String kbId, String text, int ordinal, String embedding, String vectorLiteral) {
        chunks.insertWithVector(id, docId, kbId, text, ordinal, embedding, vectorLiteral);
    }
    public KbChunkDO findChunk(String id) { return chunks.selectById(id); }
    public List<KbChunkDO> listChunksByDoc(String docId) {
        return chunks.selectList(Wrappers.<KbChunkDO>lambdaQuery()
                .eq(KbChunkDO::getDocId, docId)
                .orderByAsc(KbChunkDO::getOrdinal));
    }
    public void insertCitation(KbCitationDO row) { citations.insert(row); }
    public KbCitationDO findCitation(String id) { return citations.selectById(id); }

    /** local 后端：内存检索用；pgvector 请用 {@link #searchNearest}。 */
    public List<Map<String, Object>> listChunksWithDoc(String kbId) {
        List<KbChunkDO> chunkRows = chunks.selectList(Wrappers.<KbChunkDO>lambdaQuery().eq(KbChunkDO::getKbId, kbId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (KbChunkDO c : chunkRows) {
            KbDocumentDO d = documents.selectById(c.getDocId());
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

    public List<Map<String, Object>> searchNearest(String kbId, String vectorLiteral, int k) {
        return chunks.searchNearest(kbId, vectorLiteral, k);
    }

    public void insertWiki(KbWikiDO row) { wikis.insert(row); }
    public void insertDatasource(KbDatasourceDO row) { datasources.insert(row); }
    public List<KbDatasourceDO> listDatasources(String kbId) {
        return datasources.selectList(Wrappers.<KbDatasourceDO>lambdaQuery().eq(KbDatasourceDO::getKbId, kbId));
    }
    public KbDatasourceDO findDatasource(String id) { return datasources.selectById(id); }
}

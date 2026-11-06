package com.tioka.knowledge.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.knowledge.infrastructure.mapper.*;
import com.tioka.knowledge.infrastructure.persistence.*;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class KnowledgeRepository {
    private final KbBaseMapper bases;
    private final KbBaseLockMapper baseLocks;
    private final KbDocumentMapper documents;
    private final KbChunkMapper chunks;
    private final KbCitationMapper citations;
    private final KbWikiMapper wikis;
    private final KbDatasourceMapper datasources;

    public KnowledgeRepository(KbBaseMapper bases, KbBaseLockMapper baseLocks,
                               KbDocumentMapper documents, KbChunkMapper chunks,
                               KbCitationMapper citations, KbWikiMapper wikis, KbDatasourceMapper datasources) {
        this.bases = bases;
        this.baseLocks = baseLocks;
        this.documents = documents;
        this.chunks = chunks;
        this.citations = citations;
        this.wikis = wikis;
        this.datasources = datasources;
    }

    public void insertBase(KbBaseDO row) { bases.insert(row); }
    public boolean hasBase(String id) { return bases.selectById(id) != null; }
    public KbBaseDO findBase(String id) { return bases.selectById(id); }

    /** AST-02：FOR SHARE 确认知识库存在且未删除。 */
    public boolean lockBaseExists(String id) { return baseLocks.selectIdForShare(id) != null; }
    public void insertDocument(KbDocumentDO row) { documents.insert(row); }
    public void updateDocument(KbDocumentDO row) { documents.updateById(row); }
    public KbDocumentDO findDocument(String id) { return documents.selectById(id); }

    public List<KbDocumentDO> listDocumentsByStatus(String status, int limit) {
        return documents.selectList(Wrappers.<KbDocumentDO>lambdaQuery()
                .eq(KbDocumentDO::getStatus, status)
                .orderByAsc(KbDocumentDO::getUpdateAt)
                .last("LIMIT " + Math.max(1, limit)));
    }

    public void insertChunkWithVector(String id, String docId, String kbId, String text, int ordinal, String vectorLiteral) {
        chunks.insertWithVector(id, docId, kbId, text, ordinal, vectorLiteral);
    }

    public void updateChunkVector(String id, String vectorLiteral) {
        chunks.updateVector(id, vectorLiteral);
    }

    public KbChunkDO findChunk(String id) { return chunks.selectById(id); }
    public List<KbChunkDO> listChunksByDoc(String docId) {
        return chunks.selectList(Wrappers.<KbChunkDO>lambdaQuery()
                .eq(KbChunkDO::getDocId, docId)
                .orderByAsc(KbChunkDO::getOrdinal));
    }
    public void insertCitation(KbCitationDO row) { citations.insert(row); }
    public KbCitationDO findCitation(String id) { return citations.selectById(id); }

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

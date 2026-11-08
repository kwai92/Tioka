package com.tioka.knowledge.infrastructure;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tioka.knowledge.infrastructure.mapper.*;
import com.tioka.knowledge.infrastructure.persistence.*;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class KnowledgeRepository {
    private final KbBaseMapper bases;
    private final KbBaseLockMapper baseLocks;
    private final KbDocumentMapper documents;
    private final KbChunkMapper chunks;
    private final KbCitationMapper citations;
    private final WikiPageMapper wikis;
    private final KbDatasourceMapper datasources;

    public KnowledgeRepository(KbBaseMapper bases, KbBaseLockMapper baseLocks,
                               KbDocumentMapper documents, KbChunkMapper chunks,
                               KbCitationMapper citations, WikiPageMapper wikis, KbDatasourceMapper datasources) {
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

    /** FE-04：按知识库聚合文档状态计数（status → count）。 */
    public Map<String, Long> countDocumentsByStatus(String kbId) {
        List<KbDocumentDO> rows = documents.selectList(Wrappers.<KbDocumentDO>lambdaQuery()
                .eq(KbDocumentDO::getKbId, kbId)
                .select(KbDocumentDO::getStatus));
        Map<String, Long> out = new HashMap<>();
        for (KbDocumentDO row : rows) {
            String status = row.getStatus() == null ? "" : row.getStatus();
            out.merge(status, 1L, Long::sum);
        }
        return out;
    }

    public void insertChunkWithVector(ChunkInsertCommand cmd) {
        chunks.insertWithVector(cmd.id(), cmd.docId(), cmd.kbId(), cmd.workspaceId(),
                cmd.text(), cmd.ordinal(), cmd.vectorLiteral());
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

    public List<Map<String, Object>> searchVisible(Map<String, Object> params) {
        return chunks.searchVisible(params);
    }

    public void insertWiki(WikiPageDO row) { wikis.insert(row); }
    public void insertDatasource(KbDatasourceDO row) { datasources.insert(row); }
    public List<KbDatasourceDO> listDatasources(String kbId) {
        return datasources.selectList(Wrappers.<KbDatasourceDO>lambdaQuery().eq(KbDatasourceDO::getKbId, kbId));
    }
    public KbDatasourceDO findDatasource(String id) { return datasources.selectById(id); }
}

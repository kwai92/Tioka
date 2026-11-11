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
    private final KbIngestJobMapper ingestJobs;
    /** ENG-05：统一注入 Spring ObjectMapper。 */
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public KnowledgeRepository(KbBaseMapper bases, KbBaseLockMapper baseLocks,
                               KbDocumentMapper documents, KbChunkMapper chunks,
                               KbCitationMapper citations, WikiPageMapper wikis, KbDatasourceMapper datasources,
                               KbIngestJobMapper ingestJobs,
                               com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.bases = bases;
        this.baseLocks = baseLocks;
        this.documents = documents;
        this.chunks = chunks;
        this.citations = citations;
        this.wikis = wikis;
        this.datasources = datasources;
        this.ingestJobs = ingestJobs;
        this.mapper = mapper;
    }

    public void insertBase(KbBaseDO row) { bases.insert(row); }
    public boolean hasBase(String id) { return bases.selectById(id) != null; }
    public KbBaseDO findBase(String id) { return bases.selectById(id); }

    /** KNW-03：切块策略落 kb_base 列；修改后调用方触发既有重建路径。 */
    public void updateBaseChunkPolicy(String kbId, String chunkPolicyJson, Boolean contextEnhancement) {
        KbBaseDO row = bases.selectById(kbId);
        if (row == null) {
            throw new com.tioka.framework.error.BizException(
                    com.tioka.framework.error.ErrorCode.NOT_FOUND, "知识库不存在");
        }
        row.setChunkPolicyJson(chunkPolicyJson);
        if (contextEnhancement != null) {
            row.setContextEnhancement(contextEnhancement);
        }
        bases.updateById(row);
    }

    /** KNW-03：重建任务按知识库遍历文档。 */
    public List<KbDocumentDO> listDocumentsByKb(String kbId) {
        return documents.selectList(Wrappers.<KbDocumentDO>lambdaQuery()
                .eq(KbDocumentDO::getKbId, kbId));
    }

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
                cmd.text(), cmd.ordinal(), cmd.vectorLiteral(), cmd.docVersion(),
                cmd.chunkType(), cmd.parentChunkId(), cmd.pageStart(), cmd.pageEnd(),
                cmd.blockIds() == null ? null : writeJsonQuietly(cmd.blockIds()),
                cmd.contextPrefix());
    }

    /** KNW-03：检索后按父块 ID 批量取父块正文（上下文组装，同一父块只取一次）。 */
    public List<KbChunkDO> listChunksByIds(java.util.Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return chunks.selectBatchIds(ids);
    }

    private String writeJsonQuietly(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败: " + e.getMessage(), e);
        }
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

    // ---- KNW-05：入库任务与版本化分块 ----

    public void insertJob(KbIngestJobDO row) {
        if (ingestJobs == null) {
            throw new IllegalStateException("kb_ingest_job 未装配");
        }
        ingestJobs.insert(row);
    }

    public KbIngestJobDO findJob(String id) {
        return ingestJobs == null ? null : ingestJobs.selectById(id);
    }

    public void updateJob(KbIngestJobDO row) {
        if (ingestJobs == null) {
            throw new IllegalStateException("kb_ingest_job 未装配");
        }
        ingestJobs.updateById(row);
    }

    /** content_hash 去重：同库同哈希且未删除的文档。 */
    public List<KbDocumentDO> findDocumentsByHash(String kbId, String contentHash) {
        return documents.selectList(Wrappers.<KbDocumentDO>lambdaQuery()
                .eq(KbDocumentDO::getKbId, kbId)
                .eq(KbDocumentDO::getContentHash, contentHash));
    }

    /** F39：ChangeEvent 来源按 external_id 找既有文档（版本递增复用同一 doc 行）。 */
    public List<KbDocumentDO> findDocumentsByExternalId(String kbId, String externalId) {
        return documents.selectList(Wrappers.<KbDocumentDO>lambdaQuery()
                .eq(KbDocumentDO::getKbId, kbId)
                .eq(KbDocumentDO::getExternalId, externalId));
    }

    /** SWAP 原子切换的后半步：删除旧版本分块（doc_version &lt; activeVersion）。 */
    public int deleteChunksBeforeVersion(String docId, int activeVersion) {
        return chunks.delete(Wrappers.<KbChunkDO>lambdaQuery()
                .eq(KbChunkDO::getDocId, docId)
                .lt(KbChunkDO::getDocVersion, activeVersion));
    }

    /** EMBED 阶段幂等重跑：清掉同版本已有分块再写（EMBED 失败重试不产生重复 PK）。 */
    public int deleteChunksOfVersion(String docId, int docVersion) {
        return chunks.delete(Wrappers.<KbChunkDO>lambdaQuery()
                .eq(KbChunkDO::getDocId, docId)
                .eq(KbChunkDO::getDocVersion, docVersion));
    }
}

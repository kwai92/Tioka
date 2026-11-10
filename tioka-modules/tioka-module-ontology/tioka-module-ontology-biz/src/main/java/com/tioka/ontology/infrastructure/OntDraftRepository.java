package com.tioka.ontology.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tioka.ontology.infrastructure.mapper.OntDraftMapper;
import com.tioka.ontology.infrastructure.persistence.OntDraftDO;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * {@code ont_draft} 仓储（M3-03 §7 DraftService 持久化面）。
 */
@Repository
public class OntDraftRepository {

    private final OntDraftMapper mapper;

    public OntDraftRepository(OntDraftMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<OntDraftDO> find(String instanceKey) {
        return Optional.ofNullable(mapper.selectOne(
                new QueryWrapper<OntDraftDO>().eq("instance_key", instanceKey)));
    }

    /** 按门店键查草稿（getByStoreKey 语义）。 */
    public java.util.List<OntDraftDO> listByStoreKey(String storeKey) {
        return mapper.selectList(new QueryWrapper<OntDraftDO>().eq("store_key", storeKey));
    }

    public void insert(OntDraftDO row) {
        if (row.getRevision() == null) {
            row.setRevision(1);
        }
        if (row.getUpdatedAt() == null) {
            row.setUpdatedAt(Instant.now());
        }
        mapper.insert(row);
    }

    /**
     * 乐观锁保存：不存在则插入（revision=1）；存在则按 expectedRevision 条件更新并 +1。
     *
     * @return 持久化后的 revision；null 表示乐观锁冲突
     */
    public Integer save(OntDraftDO row, int expectedRevision) {
        if (find(row.getInstanceKey()).isEmpty()) {
            row.setRevision(1);
            row.setUpdatedAt(Instant.now());
            mapper.insert(row);
            return row.getRevision();
        }
        int updated = mapper.updateWithRevision(row, expectedRevision);
        return updated > 0 ? expectedRevision + 1 : null;
    }

    public void updateBizDraftRef(String instanceKey, String bizDraftRef) {
        OntDraftDO existing = find(instanceKey).orElseThrow();
        existing.setBizDraftRef(bizDraftRef);
        int updated = mapper.updateWithRevision(existing, existing.getRevision());
        if (updated == 0) {
            throw new IllegalStateException("ont_draft 乐观锁冲突: " + instanceKey);
        }
    }
}

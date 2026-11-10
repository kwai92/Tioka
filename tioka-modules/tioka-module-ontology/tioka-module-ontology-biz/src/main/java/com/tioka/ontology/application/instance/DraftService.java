package com.tioka.ontology.application.instance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.ontology.infrastructure.OntDraftRepository;
import com.tioka.ontology.infrastructure.persistence.OntDraftDO;
import org.springframework.beans.factory.ObjectProvider;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 草稿服务（ONT-02，M3-03 §7）：{@code save(draft)}（revision 乐观锁）、{@code propose(instanceKey)}。
 *
 * <p>propose 调领域包声明的提交工具推业务系统（proposeModelDraft）；提交工具是
 * CON-07a 业务连接器侧能力，经 {@link DraftProposer} 端口注入（本批未接线 → 未配置时抛异常）。
 */
public class DraftService {

    private final OntDraftRepository drafts;
    private final ObjectProvider<DraftProposer> proposer;
    private final ObjectMapper mapper;

    public DraftService(OntDraftRepository drafts, ObjectProvider<DraftProposer> proposer, ObjectMapper mapper) {
        this.drafts = drafts;
        this.proposer = proposer;
        this.mapper = mapper;
    }

    /**
     * 保存 / 更新草稿（乐观锁：revision 与当前行不一致 → 冲突报错）。
     *
     * @return 持久化后的 revision
     */
    public int save(ModelDraft draft) {
        Objects.requireNonNull(draft.instanceKey(), "instanceKey");
        Optional<OntDraftDO> existing = drafts.find(draft.instanceKey());
        OntDraftDO row = new OntDraftDO();
        row.setInstanceKey(draft.instanceKey());
        row.setDefKey(draft.defKey());
        row.setDefVersion(draft.defVersion());
        row.setStoreKey(draft.storeKey());
        row.setDocIds(draft.docIds());
        row.setModelJson(ModelDraftJson.stringify(draft.modelJson(), mapper));
        row.setValidationJson(ModelDraftJson.stringify(draft.validationJson(), mapper));
        row.setBuildRunId(draft.buildRunId());
        row.setBizDraftRef(draft.bizDraftRef());
        int expectedRevision = existing.map(OntDraftDO::getRevision).orElse(draft.revision());
        Integer persisted = drafts.save(row, expectedRevision);
        if (persisted == null) {
            throw new DraftConcurrencyException(
                    "草稿乐观锁冲突 instanceKey=" + draft.instanceKey()
                            + " expectedRevision=" + expectedRevision);
        }
        return persisted;
    }

    public Optional<ModelDraft> get(String instanceKey) {
        return drafts.find(instanceKey).map(row -> OntDraftRepositoryAccess.toModel(row, mapper));
    }

    /** 按门店键取草稿（getByStoreKey 语义）。 */
    public List<ModelDraft> getByStoreKey(String storeKey) {
        return drafts.listByStoreKey(storeKey).stream()
                .map(row -> OntDraftRepositoryAccess.toModel(row, mapper))
                .toList();
    }

    /**
     * 推送草稿到业务系统（领域包声明的提交工具）；业务系统返回 draftRef 写回 {@code biz_draft_ref}。
     */
    public String propose(String instanceKey) {
        ModelDraft draft = get(instanceKey)
                .orElseThrow(() -> new IllegalArgumentException("草稿不存在: " + instanceKey));
        DraftProposer p = proposer.getIfAvailable();
        if (p == null) {
            throw new IllegalStateException("DraftProposer 未装配（CON-07a 业务连接器未接线）");
        }
        String ref = p.propose(draft);
        drafts.updateBizDraftRef(instanceKey, ref);
        return ref;
    }

    /** 草稿并发冲突。 */
    public static final class DraftConcurrencyException extends RuntimeException {
        public DraftConcurrencyException(String message) {
            super(message);
        }
    }
}

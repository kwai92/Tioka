package com.tioka.ontology.infrastructure;

import com.tioka.ontology.infrastructure.mapper.OntStateMapper;
import com.tioka.ontology.infrastructure.persistence.OntStateDO;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * {@code ont_state} 仓储（ONT-07 折叠状态，V173）。upsert 覆盖写（幂等）。
 */
@Repository
public class OntStateRepository {

    private final OntStateMapper mapper;

    public OntStateRepository(OntStateMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<OntStateDO> find(String storeKey) {
        return Optional.ofNullable(mapper.findByStoreKey(storeKey));
    }

    /** upsert：ON CONFLICT (store_key) DO UPDATE。 */
    public void upsert(OntStateDO row) {
        mapper.upsert(row);
    }

    /** 投影钩子全部成功后置 written_back = true。 */
    public void markWrittenBack(String storeKey) {
        mapper.markWrittenBack(storeKey);
    }
}

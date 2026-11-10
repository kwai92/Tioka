package com.tioka.ontology.infrastructure;

import com.tioka.ontology.infrastructure.mapper.OntValueIndexMapper;
import com.tioka.ontology.infrastructure.persistence.OntValueIndexDO;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * {@code ont_value_index} 仓储（派生索引，确认单事务内重建）。
 */
@Repository
public class OntValueIndexRepository {

    private final OntValueIndexMapper mapper;

    public OntValueIndexRepository(OntValueIndexMapper mapper) {
        this.mapper = mapper;
    }

    public List<OntValueIndexDO> list(String instanceKey, int version) {
        return mapper.listByVersion(instanceKey, version);
    }

    /** 先删后插（重建口径，M3-03 §7）。 */
    public void rebuild(String instanceKey, int version, List<OntValueIndexDO> rows) {
        mapper.deleteByVersion(instanceKey, version);
        if (!rows.isEmpty()) {
            mapper.insertBatch(rows);
        }
    }
}

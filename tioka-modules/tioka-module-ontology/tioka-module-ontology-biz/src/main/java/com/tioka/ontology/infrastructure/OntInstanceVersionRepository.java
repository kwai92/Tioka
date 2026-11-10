package com.tioka.ontology.infrastructure;

import com.tioka.ontology.infrastructure.mapper.OntInstanceVersionMapper;
import com.tioka.ontology.infrastructure.persistence.OntInstanceVersionDO;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * {@code ont_instance_version} 仓储（确认投影，ADR-029 第 50 条）。
 */
@Repository
public class OntInstanceVersionRepository {

    private final OntInstanceVersionMapper mapper;

    public OntInstanceVersionRepository(OntInstanceVersionMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<OntInstanceVersionDO> find(String instanceKey, int version) {
        return Optional.ofNullable(mapper.findByKey(instanceKey, version));
    }

    public Optional<OntInstanceVersionDO> findLatestConfirmed(String instanceKey) {
        return Optional.ofNullable(mapper.findLatestConfirmed(instanceKey));
    }

    public List<OntInstanceVersionDO> listConfirmedByStore(String storeKey) {
        return mapper.listConfirmedByStore(storeKey);
    }

    public void insert(OntInstanceVersionDO row) {
        mapper.insertVersion(row);
    }

    /** 确认单事务内：其余 CONFIRMED 版本标 SUPERSEDED。 */
    public void supersedeOthers(String instanceKey, int keepVersion) {
        mapper.supersedeOthers(instanceKey, keepVersion);
    }
}

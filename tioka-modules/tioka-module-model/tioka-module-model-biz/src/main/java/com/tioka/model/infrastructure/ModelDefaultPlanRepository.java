package com.tioka.model.infrastructure;

import com.tioka.model.infrastructure.mapper.ModelDefaultPlanMapper;
import com.tioka.model.infrastructure.persistence.ModelDefaultPlanDO;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** model_default_plan 仓储（MH-01：application 不直依赖 Mapper）。 */
@Repository
public class ModelDefaultPlanRepository {
    private final ModelDefaultPlanMapper mapper;

    public ModelDefaultPlanRepository(ModelDefaultPlanMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<ModelDefaultPlanDO> findByPurpose(String purpose) {
        return Optional.ofNullable(mapper.selectById(purpose));
    }
}

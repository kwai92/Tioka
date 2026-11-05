package com.tioka.model.infrastructure;

import com.tioka.model.infrastructure.persistence.*;

import java.util.List;
import java.util.Optional;

public interface ModelEntitiesRepository {
    void putProvider(ModelProviderDO row);
    Optional<ModelProviderDO> getProvider(String id);
    List<ModelProviderDO> providers();
    void putLogical(ModelLogicalDO row);
    boolean hasLogical(String id);
    void putBinding(ModelBindingDO row);
    Optional<ModelBindingDO> getBinding(String id);
    boolean hasBinding(String id);
    void putPlan(ModelRoutingPlanDO row);
    Optional<ModelRoutingPlanDO> getPlan(String id);
    List<ModelRoutingPlanDO> plans();
    Optional<String> getFreeze(String sessionId, String turnId);
    void putFreeze(String sessionId, String turnId, String bindingId);
}

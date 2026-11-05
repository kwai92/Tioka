package com.tioka.model.infrastructure;

import com.tioka.model.infrastructure.mapper.*;
import com.tioka.model.infrastructure.persistence.*;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ModelEntitiesRepositoryImpl implements ModelEntitiesRepository {
    private final ModelProviderMapper providers;
    private final ModelLogicalMapper logicals;
    private final ModelBindingMapper bindings;
    private final ModelRoutingPlanMapper plans;
    private final ModelSessionFreezeMapper freezes;

    public ModelEntitiesRepositoryImpl(ModelProviderMapper providers, ModelLogicalMapper logicals,
                                       ModelBindingMapper bindings, ModelRoutingPlanMapper plans,
                                       ModelSessionFreezeMapper freezes) {
        this.providers = providers;
        this.logicals = logicals;
        this.bindings = bindings;
        this.plans = plans;
        this.freezes = freezes;
    }

    @Override
    public void putProvider(ModelProviderDO row) {
        if (providers.selectById(row.getId()) == null) providers.insert(row);
        else providers.updateById(row);
    }

    @Override
    public Optional<ModelProviderDO> getProvider(String id) {
        return Optional.ofNullable(providers.selectById(id));
    }

    @Override
    public List<ModelProviderDO> providers() {
        return providers.selectList(null);
    }

    @Override
    public void putLogical(ModelLogicalDO row) {
        if (logicals.selectById(row.getId()) == null) logicals.insert(row);
        else logicals.updateById(row);
    }

    @Override
    public boolean hasLogical(String id) {
        return logicals.selectById(id) != null;
    }

    @Override
    public void putBinding(ModelBindingDO row) {
        if (bindings.selectById(row.getId()) == null) bindings.insert(row);
        else bindings.updateById(row);
    }

    @Override
    public Optional<ModelBindingDO> getBinding(String id) {
        return Optional.ofNullable(bindings.selectById(id));
    }

    @Override
    public boolean hasBinding(String id) {
        return bindings.selectById(id) != null;
    }

    @Override
    public void putPlan(ModelRoutingPlanDO row) {
        if (plans.selectById(row.getId()) == null) plans.insert(row);
        else plans.updateById(row);
    }

    @Override
    public Optional<ModelRoutingPlanDO> getPlan(String id) {
        return Optional.ofNullable(plans.selectById(id));
    }

    @Override
    public List<ModelRoutingPlanDO> plans() {
        return plans.selectList(null);
    }

    @Override
    public Optional<String> getFreeze(String sessionId) {
        ModelSessionFreezeDO row = freezes.selectById(sessionId);
        return row == null ? Optional.empty() : Optional.ofNullable(row.getBindingId());
    }

    @Override
    public void putFreeze(String sessionId, String bindingId) {
        ModelSessionFreezeDO row = new ModelSessionFreezeDO();
        row.setSessionId(sessionId);
        row.setBindingId(bindingId);
        if (freezes.selectById(sessionId) == null) freezes.insert(row);
        else freezes.updateById(row);
    }
}

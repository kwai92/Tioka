package com.tioka.model.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
    private final ModelRoutingPlanLockMapper planLocks;
    private final ModelTurnFreezeMapper freezes;

    public ModelEntitiesRepositoryImpl(ModelProviderMapper providers, ModelLogicalMapper logicals,
                                       ModelBindingMapper bindings, ModelRoutingPlanMapper plans,
                                       ModelRoutingPlanLockMapper planLocks,
                                       ModelTurnFreezeMapper freezes) {
        this.providers = providers;
        this.logicals = logicals;
        this.bindings = bindings;
        this.plans = plans;
        this.planLocks = planLocks;
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
    public Optional<ModelBindingDO> getModelBinding(String id) {
        return Optional.ofNullable(bindings.selectById(id));
    }

    @Override
    public boolean hasBinding(String id) {
        return bindings.selectById(id) != null;
    }

    @Override
    public List<ModelBindingDO> bindings() {
        return bindings.selectList(null);
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
    public Optional<ModelRoutingPlanDO> lockPlanForShare(String id) {
        return Optional.ofNullable(planLocks.selectForShare(id));
    }

    @Override
    public List<ModelRoutingPlanDO> plans() {
        return plans.selectList(null);
    }

    @Override
    public Optional<String> getFreeze(String threadId, String turnId) {
        // 只选 binding_id：TIMESTAMPTZ → LocalDateTime 在续跑二次选模时会炸（P1 时区债）
        ModelTurnFreezeDO row = freezes.selectOne(new LambdaQueryWrapper<ModelTurnFreezeDO>()
                .select(ModelTurnFreezeDO::getBindingId)
                .eq(ModelTurnFreezeDO::getThreadId, threadId)
                .eq(ModelTurnFreezeDO::getTurnId, turnId)
                .last("LIMIT 1"));
        return row == null ? Optional.empty() : Optional.ofNullable(row.getBindingId());
    }

    @Override
    public String insertFreezeIfAbsent(String threadId, String turnId, String bindingId, String reason) {
        freezes.insertIfAbsent(threadId, turnId, bindingId, reason);
        return getFreeze(threadId, turnId)
                .orElseThrow(() -> new IllegalStateException(
                        "冻结写入后读不到: " + threadId + "/" + turnId));
    }

    @Override
    public void putFreeze(String threadId, String turnId, String bindingId) {
        ModelTurnFreezeDO existing = freezes.selectOne(new LambdaQueryWrapper<ModelTurnFreezeDO>()
                .select(ModelTurnFreezeDO::getBindingId, ModelTurnFreezeDO::getThreadId, ModelTurnFreezeDO::getTurnId)
                .eq(ModelTurnFreezeDO::getThreadId, threadId)
                .eq(ModelTurnFreezeDO::getTurnId, turnId)
                .last("LIMIT 1"));
        if (existing == null) {
            freezes.insertIfAbsent(threadId, turnId, bindingId, null);
        } else {
            existing.setBindingId(bindingId);
            existing.setUpdateBy("system");
            freezes.update(existing, new LambdaQueryWrapper<ModelTurnFreezeDO>()
                    .eq(ModelTurnFreezeDO::getThreadId, threadId)
                    .eq(ModelTurnFreezeDO::getTurnId, turnId));
        }
    }

    @Override
    public void recordFailover(String threadId, String turnId, String bindingId, String reason) {
        int n = freezes.recordFailover(threadId, turnId, bindingId, reason);
        if (n == 0) {
            freezes.insertIfAbsent(threadId, turnId, bindingId, reason);
            freezes.recordFailover(threadId, turnId, bindingId, reason);
        }
    }

    @Override
    public List<ModelTurnFreezeDO> listFreezes(String threadId) {
        return freezes.listByThread(threadId);
    }
}

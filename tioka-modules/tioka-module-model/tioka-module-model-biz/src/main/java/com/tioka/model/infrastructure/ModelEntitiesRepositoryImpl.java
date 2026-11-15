package com.tioka.model.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tioka.model.infrastructure.mapper.ModelAliasMapper;
import com.tioka.model.infrastructure.mapper.ModelBindingMapper;
import com.tioka.model.infrastructure.mapper.ModelProviderMapper;
import com.tioka.model.infrastructure.mapper.ModelRoutingPlanLockMapper;
import com.tioka.model.infrastructure.mapper.ModelRoutingPlanMapper;
import com.tioka.model.infrastructure.mapper.ModelTurnFreezeMapper;
import com.tioka.model.infrastructure.mapper.PromptTemplateMapper;
import com.tioka.model.infrastructure.persistence.ModelAliasDO;
import com.tioka.model.infrastructure.persistence.ModelBindingDO;
import com.tioka.model.infrastructure.persistence.ModelProviderDO;
import com.tioka.model.infrastructure.persistence.ModelRoutingPlanDO;
import com.tioka.model.infrastructure.persistence.ModelTurnFreezeDO;
import com.tioka.model.infrastructure.persistence.PromptTemplateDO;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ModelEntitiesRepositoryImpl implements ModelEntitiesRepository {
    private final ModelProviderMapper providers;
    private final ModelAliasMapper aliases;
    private final ModelBindingMapper bindings;
    private final ModelRoutingPlanMapper plans;
    private final ModelRoutingPlanLockMapper planLocks;
    private final ModelTurnFreezeMapper freezes;
    private final PromptTemplateMapper promptTemplates;

    public ModelEntitiesRepositoryImpl(ModelProviderMapper providers, ModelAliasMapper aliases,
                                       ModelBindingMapper bindings, ModelRoutingPlanMapper plans,
                                       ModelRoutingPlanLockMapper planLocks,
                                       ModelTurnFreezeMapper freezes,
                                       PromptTemplateMapper promptTemplates) {
        this.providers = providers;
        this.aliases = aliases;
        this.bindings = bindings;
        this.plans = plans;
        this.planLocks = planLocks;
        this.freezes = freezes;
        this.promptTemplates = promptTemplates;
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
    public void updateProviderHealth(String providerId, String health) {
        if (providerId == null || providerId.isBlank()) {
            return;
        }
        ModelProviderDO patch = new ModelProviderDO();
        patch.setId(providerId);
        patch.setHealth(health);
        patch.setUpdateBy("system");
        providers.updateById(patch);
    }

    @Override
    public void putAlias(ModelAliasDO row) {
        if (aliases.selectById(row.getId()) == null) aliases.insert(row);
        else aliases.updateById(row);
    }

    @Override
    public boolean hasAlias(String id) {
        return aliases.selectById(id) != null;
    }

    @Override
    public Optional<ModelAliasDO> getAlias(String id) {
        return Optional.ofNullable(aliases.selectById(id));
    }

    @Override
    public List<ModelAliasDO> aliases() {
        return aliases.selectList(null);
    }

    @Override
    public Optional<ModelAliasDO> findAliasByName(String name) {
        return Optional.ofNullable(aliases.findByName(name));
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

    // ------------------------------------------------- AST-07 Prompt 模板

    @Override
    public void putPromptTemplate(PromptTemplateDO row) {
        if (promptTemplates.selectById(row.getId()) == null) promptTemplates.insert(row);
        else promptTemplates.updateById(row);
    }

    @Override
    public Optional<PromptTemplateDO> getPromptTemplate(String id) {
        return Optional.ofNullable(promptTemplates.selectById(id));
    }

    @Override
    public List<PromptTemplateDO> promptTemplates() {
        return promptTemplates.selectList(null);
    }

    @Override
    public Optional<PromptTemplateDO> findPromptTemplateByName(String name) {
        return Optional.ofNullable(promptTemplates.findByName(name));
    }
}

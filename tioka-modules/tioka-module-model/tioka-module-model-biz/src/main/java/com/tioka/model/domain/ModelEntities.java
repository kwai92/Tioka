package com.tioka.model.domain;

import com.tioka.model.infrastructure.ModelEntitiesRepository;
import com.tioka.model.infrastructure.persistence.ModelAliasDO;
import com.tioka.model.infrastructure.persistence.ModelBindingDO;
import com.tioka.model.infrastructure.persistence.ModelProviderDO;
import com.tioka.model.infrastructure.persistence.ModelRoutingPlanDO;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ModelEntities {
    private final ModelEntitiesRepository repo;

    public ModelEntities(ModelEntitiesRepository repo) {
        this.repo = repo;
    }

    public void putProvider(Provider p) {
        ModelProviderDO row = new ModelProviderDO();
        row.setId(p.id());
        row.setName(p.name());
        row.setBaseUrl(p.baseUrl());
        row.setApiKeyRef(p.apiKeyRef());
        repo.putProvider(row);
    }

    public Optional<Provider> getProvider(String id) {
        return repo.getProvider(id).map(r -> new Provider(r.getId(), r.getName(), r.getBaseUrl(), r.getApiKeyRef()));
    }

    public List<Provider> providers() {
        return repo.providers().stream()
                .map(r -> new Provider(r.getId(), r.getName(), r.getBaseUrl(), r.getApiKeyRef()))
                .toList();
    }

    /** MH-08：别名写入（原 logical model；V151 起表为 model_alias）。 */
    public void putAlias(ModelAlias m) {
        ModelAliasDO row = new ModelAliasDO();
        row.setId(m.id());
        row.setName(m.name());
        row.setCapabilityTags(m.capabilityTags());
        row.setPurpose(m.purpose());
        row.setTargetType(m.targetType());
        row.setTargetId(m.targetId());
        row.setCurrentVersionNo(m.currentVersionNo());
        repo.putAlias(row);
    }

    public boolean hasAlias(String id) {
        return repo.hasAlias(id);
    }

    public Optional<ModelAlias> getAlias(String id) {
        return repo.getAlias(id).map(this::toAlias);
    }

    public List<ModelAlias> aliases() {
        return repo.aliases().stream().map(this::toAlias).toList();
    }

    public Optional<ModelAlias> findAliasByName(String name) {
        return repo.findAliasByName(name).map(this::toAlias);
    }

    private ModelAlias toAlias(ModelAliasDO r) {
        return new ModelAlias(r.getId(), r.getName(), r.getCapabilityTags(),
                r.getPurpose(), r.getTargetType(), r.getTargetId(), r.getCurrentVersionNo());
    }

    public void putBinding(ModelBinding b) {
        ModelBindingDO row = new ModelBindingDO();
        row.setId(b.id());
        row.setLogicalModelId(b.logicalModelId());
        row.setProviderId(b.providerId());
        row.setModelName(b.modelName());
        row.setKind(b.kind() == null ? "chat" : b.kind());
        row.setContextWindow(b.contextWindow());
        row.setEmbeddingDim(b.embeddingDim());
        row.setInputPriceMicros(b.inputPriceMicros());
        row.setOutputPriceMicros(b.outputPriceMicros());
        row.setRpmLimit(b.rpmLimit());
        row.setTpmLimit(b.tpmLimit());
        row.setBudgetTokenMonth(b.budgetTokenMonth());
        row.setBudgetCostMonth(b.budgetCostMonth());
        row.setEnabled(b.enabled());
        repo.putBinding(row);
    }

    public Optional<ModelBinding> getModelBinding(String id) {
        return repo.getModelBinding(id).map(this::toBinding);
    }

    private ModelBinding toBinding(ModelBindingDO r) {
        return new ModelBinding(
                r.getId(), r.getLogicalModelId(), r.getProviderId(), r.getModelName(),
                r.getKind() == null ? "chat" : r.getKind(),
                r.getContextWindow(), r.getEmbeddingDim(),
                r.getInputPriceMicros() == null ? 0L : r.getInputPriceMicros(),
                r.getOutputPriceMicros() == null ? 0L : r.getOutputPriceMicros(),
                r.getRpmLimit(), r.getTpmLimit(),
                r.getEnabled() == null || r.getEnabled(),
                r.getBudgetTokenMonth(), r.getBudgetCostMonth(),
                r.getCurrentVersionNo());
    }

    public boolean hasBinding(String id) {
        return repo.hasBinding(id);
    }

    public List<ModelBinding> bindings() {
        return repo.bindings().stream().map(this::toBinding).toList();
    }

    public Optional<ModelBinding> firstEnabledBinding() {
        return repo.bindings().stream()
                .map(this::toBinding)
                .filter(ModelBinding::enabled)
                .findFirst();
    }

    public void putPlan(RoutingPlan p) {
        ModelRoutingPlanDO row = new ModelRoutingPlanDO();
        row.setId(p.id());
        row.setName(p.name());
        row.setType(p.type());
        row.setPrimaryBindingId(p.primaryBindingId());
        row.setFallbackBindingId(p.fallbackBindingId());
        row.setHardBindingId(p.hardBindingId());
        row.setFallbackChain(p.fallbackChain());
        row.setPublished(p.published());
        row.setSnapshotHash(p.snapshotHash());
        repo.putPlan(row);
    }

    public Optional<RoutingPlan> getPlan(String id) {
        return repo.getPlan(id).map(this::toPlan);
    }

    public List<RoutingPlan> plans() {
        return repo.plans().stream().map(this::toPlan).toList();
    }

    public Optional<String> getFreeze(String threadId, String turnId) {
        return repo.getFreeze(threadId, turnId);
    }

    /** @return 实际冻结的 bindingId（并发以先写者为准） */
    public String insertFreezeIfAbsent(String threadId, String turnId, String bindingId, String reason) {
        return repo.insertFreezeIfAbsent(threadId, turnId, bindingId, reason);
    }

    public void putFreeze(String threadId, String turnId, String bindingId) {
        repo.putFreeze(threadId, turnId, bindingId);
    }

    /** MH-02：冻结切到 nextBinding，failover_count + 1。 */
    public void recordFailover(String threadId, String turnId, String nextBinding, String reason) {
        repo.recordFailover(threadId, turnId, nextBinding, reason);
    }

    public List<Freeze> listFreezes(String threadId) {
        return repo.listFreezes(threadId).stream()
                .map(r -> new Freeze(
                        r.getThreadId(), r.getTurnId(), r.getBindingId(),
                        r.getReason(),
                        r.getFailoverCount() == null ? 0 : r.getFailoverCount()))
                .toList();
    }

    private RoutingPlan toPlan(ModelRoutingPlanDO r) {
        return new RoutingPlan(
                r.getId(), r.getName(), r.getType(), r.getPrimaryBindingId(),
                r.getFallbackBindingId(), r.getHardBindingId(),
                Boolean.TRUE.equals(r.getPublished()), r.getSnapshotHash(),
                r.getFallbackChain(), r.getCurrentVersionNo());
    }

    public record Provider(String id, String name, String baseUrl, String apiKeyRef) {}

    /**
     * 模型别名（MH-08 / ADR-041 §1，由 LogicalModel 改名扩展）。
     * 指向一个路由计划或一个 binding；{@code currentVersionNo} 为当前生效版本（GOV-12）。
     */
    public record ModelAlias(String id, String name, String capabilityTags,
                             String purpose, String targetType, String targetId,
                             Integer currentVersionNo) {
        public ModelAlias(String id, String name, String capabilityTags) {
            this(id, name, capabilityTags, null, null, null, null);
        }
    }

    /**
     * 模型绑定快照。预算字段（P2-L5 / V218）：NULL 表示不限；
     * {@code budgetTokenMonth} 为本月 token 上限，{@code budgetCostMonth} 为本月成本上限（微美分）。
     */
    public record ModelBinding(String id, String logicalModelId, String providerId, String modelName,
                          String kind, Integer contextWindow, Integer embeddingDim,
                          long inputPriceMicros, long outputPriceMicros,
                          Integer rpmLimit, Integer tpmLimit, boolean enabled,
                          Long budgetTokenMonth, Long budgetCostMonth,
                          Integer currentVersionNo) {
        public ModelBinding(String id, String logicalModelId, String providerId, String modelName) {
            this(id, logicalModelId, providerId, modelName, "chat", null, null, 0L, 0L, null, null, true,
                    null, null, null);
        }
        /** 兼容 V218 之前调用方的短构造：预算不限。 */
        public ModelBinding(String id, String logicalModelId, String providerId, String modelName,
                            String kind, Integer contextWindow, Integer embeddingDim,
                            long inputPriceMicros, long outputPriceMicros,
                            Integer rpmLimit, Integer tpmLimit, boolean enabled) {
            this(id, logicalModelId, providerId, modelName, kind, contextWindow, embeddingDim,
                    inputPriceMicros, outputPriceMicros, rpmLimit, tpmLimit, enabled, null, null, null);
        }
        /** 兼容 V218 调用方：带预算、不带版本。 */
        public ModelBinding(String id, String logicalModelId, String providerId, String modelName,
                            String kind, Integer contextWindow, Integer embeddingDim,
                            long inputPriceMicros, long outputPriceMicros,
                            Integer rpmLimit, Integer tpmLimit, boolean enabled,
                            Long budgetTokenMonth, Long budgetCostMonth) {
            this(id, logicalModelId, providerId, modelName, kind, contextWindow, embeddingDim,
                    inputPriceMicros, outputPriceMicros, rpmLimit, tpmLimit, enabled,
                    budgetTokenMonth, budgetCostMonth, null);
        }
    }

    public record RoutingPlan(
            String id, String name, String type, String primaryBindingId,
            String fallbackBindingId, String hardBindingId, boolean published, String snapshotHash,
            String fallbackChain, Integer currentVersionNo) {
        public RoutingPlan(String id, String name, String type, String primaryBindingId,
                           String fallbackBindingId, String hardBindingId, boolean published, String snapshotHash) {
            this(id, name, type, primaryBindingId, fallbackBindingId, hardBindingId, published, snapshotHash,
                    fallbackBindingId, null);
        }
        public RoutingPlan(String id, String name, String type, String primaryBindingId,
                           String fallbackBindingId, String hardBindingId, boolean published, String snapshotHash,
                           String fallbackChain) {
            this(id, name, type, primaryBindingId, fallbackBindingId, hardBindingId, published, snapshotHash,
                    fallbackChain, null);
        }
    }

    public record Freeze(String threadId, String turnId, String bindingId, String reason, int failoverCount) {}
}

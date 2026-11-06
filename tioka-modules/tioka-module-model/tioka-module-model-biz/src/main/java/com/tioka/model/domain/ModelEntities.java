package com.tioka.model.domain;

import com.tioka.model.infrastructure.ModelEntitiesRepository;
import com.tioka.model.infrastructure.persistence.ModelBindingDO;
import com.tioka.model.infrastructure.persistence.ModelLogicalDO;
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

    public void putLogical(LogicalModel m) {
        ModelLogicalDO row = new ModelLogicalDO();
        row.setId(m.id());
        row.setName(m.name());
        row.setCapabilityTags(m.capabilityTags());
        repo.putLogical(row);
    }

    public boolean hasLogical(String id) {
        return repo.hasLogical(id);
    }

    public void putBinding(Binding b) {
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
        row.setEnabled(b.enabled());
        repo.putBinding(row);
    }

    public Optional<Binding> getBinding(String id) {
        return repo.getBinding(id).map(this::toBinding);
    }

    private Binding toBinding(ModelBindingDO r) {
        return new Binding(
                r.getId(), r.getLogicalModelId(), r.getProviderId(), r.getModelName(),
                r.getKind() == null ? "chat" : r.getKind(),
                r.getContextWindow(), r.getEmbeddingDim(),
                r.getInputPriceMicros() == null ? 0L : r.getInputPriceMicros(),
                r.getOutputPriceMicros() == null ? 0L : r.getOutputPriceMicros(),
                r.getRpmLimit(), r.getTpmLimit(),
                r.getEnabled() == null || r.getEnabled());
    }

    public boolean hasBinding(String id) {
        return repo.hasBinding(id);
    }

    public Optional<Binding> firstEnabledBinding() {
        return repo.bindings().stream()
                .map(this::toBinding)
                .filter(Binding::enabled)
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

    public Optional<String> getFreeze(String sessionId, String turnId) {
        return repo.getFreeze(sessionId, turnId);
    }

    /** @return 实际冻结的 bindingId（并发以先写者为准） */
    public String insertFreezeIfAbsent(String sessionId, String turnId, String bindingId, String reason) {
        return repo.insertFreezeIfAbsent(sessionId, turnId, bindingId, reason);
    }

    public void putFreeze(String sessionId, String turnId, String bindingId) {
        repo.putFreeze(sessionId, turnId, bindingId);
    }

    public List<Freeze> listFreezes(String sessionId) {
        return repo.listFreezes(sessionId).stream()
                .map(r -> new Freeze(
                        r.getSessionId(), r.getTurnId(), r.getBindingId(),
                        r.getReason(),
                        r.getFailoverCount() == null ? 0 : r.getFailoverCount()))
                .toList();
    }

    private RoutingPlan toPlan(ModelRoutingPlanDO r) {
        return new RoutingPlan(
                r.getId(), r.getName(), r.getType(), r.getPrimaryBindingId(),
                r.getFallbackBindingId(), r.getHardBindingId(),
                Boolean.TRUE.equals(r.getPublished()), r.getSnapshotHash(),
                r.getFallbackChain());
    }

    public record Provider(String id, String name, String baseUrl, String apiKeyRef) {}
    public record LogicalModel(String id, String name, String capabilityTags) {}

    public record Binding(String id, String logicalModelId, String providerId, String modelName,
                          String kind, Integer contextWindow, Integer embeddingDim,
                          long inputPriceMicros, long outputPriceMicros,
                          Integer rpmLimit, Integer tpmLimit, boolean enabled) {
        public Binding(String id, String logicalModelId, String providerId, String modelName) {
            this(id, logicalModelId, providerId, modelName, "chat", null, null, 0L, 0L, null, null, true);
        }
    }

    public record RoutingPlan(
            String id, String name, String type, String primaryBindingId,
            String fallbackBindingId, String hardBindingId, boolean published, String snapshotHash,
            String fallbackChain) {
        public RoutingPlan(String id, String name, String type, String primaryBindingId,
                           String fallbackBindingId, String hardBindingId, boolean published, String snapshotHash) {
            this(id, name, type, primaryBindingId, fallbackBindingId, hardBindingId, published, snapshotHash,
                    fallbackBindingId);
        }
    }

    public record Freeze(String sessionId, String turnId, String bindingId, String reason, int failoverCount) {}
}

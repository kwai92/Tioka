package com.tioka.model.application;

import com.tioka.model.api.ModelCatalog;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.domain.ModelEntities.ModelAlias;
import com.tioka.model.domain.ModelEntities.ModelBinding;
import com.tioka.model.domain.ModelEntities.Provider;
import com.tioka.model.domain.ModelEntities.RoutingPlan;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ModelCatalog} 默认实现（F11）：读投影表 + 本地缓存。
 * <p>缓存 30 秒 TTL（ADR-041 §2：防广播丢失的兜底比对周期）；版本发布广播驱动的
 * 主动失效由 GOV-12 跨实例刷新承接（cfg-version-published），此处不重复实现。
 */
@Component
public class DefaultModelCatalog implements ModelCatalog {

    private static final long TTL_MS = 30_000L;

    private final ModelEntities entities;
    private final ConcurrentHashMap<String, Cached<?>> cache = new ConcurrentHashMap<>();

    public DefaultModelCatalog(ModelEntities entities) {
        this.entities = entities;
    }

    @Override
    public Optional<ProviderSnapshot> currentProvider(String id) {
        return read("p:" + id, () -> entities.getProvider(id).map(DefaultModelCatalog::toSnapshot));
    }

    @Override
    public Optional<BindingSnapshot> currentBinding(String id) {
        return read("b:" + id, () -> entities.getModelBinding(id).map(DefaultModelCatalog::toSnapshot));
    }

    @Override
    public Optional<AliasSnapshot> currentAlias(String id) {
        return read("a:" + id, () -> entities.getAlias(id).map(DefaultModelCatalog::toSnapshot));
    }

    @Override
    public Optional<AliasSnapshot> aliasByName(String name) {
        return read("an:" + name, () -> entities.findAliasByName(name).map(DefaultModelCatalog::toSnapshot));
    }

    @Override
    public Optional<PlanSnapshot> currentPlan(String id) {
        return read("r:" + id, () -> entities.getPlan(id).map(DefaultModelCatalog::toSnapshot));
    }

    @SuppressWarnings("unchecked")
    private <T> Optional<T> read(String key, java.util.function.Supplier<Optional<T>> loader) {
        long now = System.currentTimeMillis();
        Cached<?> hit = cache.get(key);
        if (hit != null && hit.expiresAt() > now) {
            return (Optional<T>) hit.value();
        }
        Optional<T> fresh = loader.get();
        cache.put(key, new Cached<>(fresh, now + TTL_MS));
        return fresh;
    }

    private static ModelCatalog.ProviderSnapshot toSnapshot(Provider p) {
        return new ProviderSnapshot(p.id(), p.name(), p.baseUrl(), null);
    }

    private static ModelCatalog.BindingSnapshot toSnapshot(ModelBinding b) {
        return new BindingSnapshot(b.id(), b.providerId(), b.modelName(), b.kind(),
                b.embeddingDim(), b.enabled(), b.currentVersionNo());
    }

    private static ModelCatalog.AliasSnapshot toSnapshot(ModelAlias a) {
        return new AliasSnapshot(a.id(), a.name(), a.purpose(), a.targetType(), a.targetId(),
                a.currentVersionNo());
    }

    private static ModelCatalog.PlanSnapshot toSnapshot(RoutingPlan p) {
        return new PlanSnapshot(p.id(), p.name(), p.type(), p.primaryBindingId(),
                p.fallbackChain(), p.published(), p.currentVersionNo());
    }

    private record Cached<T>(Optional<T> value, long expiresAt) {}
}

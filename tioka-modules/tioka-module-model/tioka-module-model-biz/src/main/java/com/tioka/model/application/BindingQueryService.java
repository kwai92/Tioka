package com.tioka.model.application;

import com.tioka.model.api.BindingQueryApi;
import com.tioka.model.domain.ModelEntities;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class BindingQueryService implements BindingQueryApi {
    private final ModelEntities entities;

    public BindingQueryService(ModelEntities entities) {
        this.entities = entities;
    }

    @Override
    public Optional<BindingInfo> find(String bindingId) {
        if (bindingId == null || bindingId.isBlank()) {
            return Optional.empty();
        }
        return entities.getBinding(bindingId).map(this::toInfo);
    }

    @Override
    public List<BindingInfo> listByKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return List.of();
        }
        String want = kind.trim();
        return entities.bindings().stream()
                .filter(b -> b.kind() != null && want.equalsIgnoreCase(b.kind()))
                .map(this::toInfo)
                .toList();
    }

    private BindingInfo toInfo(ModelEntities.Binding b) {
        return new BindingInfo(b.id(), b.kind(), b.embeddingDim(), b.enabled(), b.modelName());
    }
}

package com.tioka.model.application;

import com.tioka.model.api.ModelBindingQueryApi;
import com.tioka.model.domain.ModelEntities;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ModelBindingQueryService implements ModelBindingQueryApi {
    private final ModelEntities entities;

    public ModelBindingQueryService(ModelEntities entities) {
        this.entities = entities;
    }

    @Override
    public Optional<ModelBindingInfo> find(String bindingId) {
        if (bindingId == null || bindingId.isBlank()) {
            return Optional.empty();
        }
        return entities.getModelBinding(bindingId).map(this::toInfo);
    }

    @Override
    public List<ModelBindingInfo> listByKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return List.of();
        }
        String want = kind.trim();
        return entities.bindings().stream()
                .filter(b -> b.kind() != null && want.equalsIgnoreCase(b.kind()))
                .map(this::toInfo)
                .toList();
    }

    private ModelBindingInfo toInfo(ModelEntities.ModelBinding b) {
        return new ModelBindingInfo(b.id(), b.kind(), b.embeddingDim(), b.enabled(), b.modelName());
    }
}

package com.tioka.model.application;

import com.tioka.model.api.BindingQueryApi;
import com.tioka.model.domain.ModelEntities;
import org.springframework.stereotype.Service;

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
        return entities.getBinding(bindingId)
                .map(b -> new BindingInfo(b.id(), b.kind(), b.embeddingDim(), b.enabled()));
    }
}

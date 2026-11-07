package com.tioka.model.application;

import com.tioka.model.api.RoutingPlanQueryApi;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.infrastructure.ModelEntitiesRepository;
import com.tioka.model.infrastructure.persistence.ModelRoutingPlanDO;
import org.springframework.stereotype.Service;

@Service
public class RoutingPlanQueryService implements RoutingPlanQueryApi {
    private final ModelEntities entities;
    private final ModelEntitiesRepository repo;

    public RoutingPlanQueryService(ModelEntities entities, ModelEntitiesRepository repo) {
        this.entities = entities;
        this.repo = repo;
    }

    @Override
    public boolean isPublished(String routingPlanId) {
        return entities.getPlan(routingPlanId).map(ModelEntities.RoutingPlan::published).orElse(false);
    }

    @Override
    public ChatAvailability lockForChat(String routingPlanId) {
        if (routingPlanId == null || routingPlanId.isBlank()) {
            return ChatAvailability.MISSING;
        }
        ModelRoutingPlanDO row = repo.lockPlanForShare(routingPlanId).orElse(null);
        if (row == null) {
            return ChatAvailability.MISSING;
        }
        return Boolean.TRUE.equals(row.getPublished())
                ? ChatAvailability.AVAILABLE
                : ChatAvailability.UNPUBLISHED;
    }
}

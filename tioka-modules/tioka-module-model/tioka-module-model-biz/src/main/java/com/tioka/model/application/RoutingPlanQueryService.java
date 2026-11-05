package com.tioka.model.application;

import com.tioka.model.api.RoutingPlanQueryApi;
import com.tioka.model.domain.ModelEntities;
import org.springframework.stereotype.Service;

@Service
public class RoutingPlanQueryService implements RoutingPlanQueryApi {
    private final ModelEntities entities;

    public RoutingPlanQueryService(ModelEntities entities) {
        this.entities = entities;
    }

    @Override
    public boolean isPublished(String routingPlanId) {
        return entities.getPlan(routingPlanId).map(ModelEntities.RoutingPlan::published).orElse(false);
    }
}

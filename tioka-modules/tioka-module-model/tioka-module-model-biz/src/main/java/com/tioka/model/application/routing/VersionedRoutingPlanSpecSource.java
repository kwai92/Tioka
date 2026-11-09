package com.tioka.model.application.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.model.api.versioning.ModelHubSpecs;
import com.tioka.model.application.versioning.ModelConfigVersionService;
import com.tioka.model.domain.ModelEntities;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 默认 spec 源：cfg_version 当前生效版本 → 投影行平铺兜底。
 * 版本内容经 ModelConfigVersionService 只读口（ArchUnit：不直触 cfg_* 持久化）。
 */
@Component
public class VersionedRoutingPlanSpecSource implements RoutingPlanSpecSource {

    private final ModelConfigVersionService versioning;
    private final ModelEntities entities;
    private final ObjectMapper mapper;

    public VersionedRoutingPlanSpecSource(ModelConfigVersionService versioning,
                                          ModelEntities entities, ObjectMapper mapper) {
        this.versioning = versioning;
        this.entities = entities;
        this.mapper = mapper;
    }

    @Override
    public Optional<RoutingPlanSnapshot> load(String planId) {
        if (planId == null || planId.isBlank()) {
            return Optional.empty();
        }
        // GOV-12 当前生效版本（发布型对象；rollback 后即回退版本内容）
        if (versioning.available()) {
            try {
                return versioning.current(ModelHubSpecs.OT_ROUTING_PLAN, planId)
                        .map(v -> RoutingPlanSpecParser.parse(v.spec()));
            } catch (RuntimeException ignored) {
                // 版本读取失败回退投影行
            }
        }
        return entities.getPlan(planId)
                .map(plan -> RoutingPlanSpecParser.parse(RoutingPlanSpecParser.flatSpec(mapper, plan)));
    }
}

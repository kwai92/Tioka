package com.tioka.model.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.tioka.model.api.profile.ModelProfile;
import com.tioka.model.api.profile.StructuredCapabilityQueryApi;
import com.tioka.model.api.profile.StructuredOutputCapability;
import com.tioka.model.api.profile.StructuredOutputPlanApi;
import com.tioka.model.application.gateway.StructuredRequestBuilder;
import com.tioka.model.application.routing.RoutingPlanSnapshot;
import com.tioka.model.application.routing.RoutingPlanSpecSource;
import com.tioka.model.domain.ModelEntities;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * SCH-06（ADR-066 §2）：Agent 发布期按路由计划的候选模型逐个试编译权威 Schema。
 *
 * <p>候选模型 ref 口径与 {@code ModelBindingResolver} 一致（{@code logicalModelId@modelName}）；
 * 能力记录经 {@link StructuredCapabilityQueryApi} 查询（V177 已接线，默认实现
 * {@code StructuredCapabilityQueryService}），未登记时按 legacy 兜底。
 */
@Service
public class StructuredOutputPlanService implements StructuredOutputPlanApi {

    private final RoutingPlanSpecSource specSource;
    private final ModelEntities entities;
    private final StructuredRequestBuilder builder;
    private final ObjectProvider<StructuredCapabilityQueryApi> capabilities;

    public StructuredOutputPlanService(RoutingPlanSpecSource specSource,
                                       ModelEntities entities,
                                       StructuredRequestBuilder builder,
                                       ObjectProvider<StructuredCapabilityQueryApi> capabilities) {
        this.specSource = specSource;
        this.entities = entities;
        this.builder = builder;
        this.capabilities = capabilities;
    }

    @Override
    public List<CandidateCompile> trialCompile(String routingPlanId, JsonNode authoritativeSchema,
                                               boolean multimodalInput) {
        if (authoritativeSchema == null || authoritativeSchema.isNull()) {
            return List.of();
        }
        List<String> modelRefs = candidateModelRefs(routingPlanId);
        if (modelRefs.isEmpty()) {
            return List.of();
        }
        List<CandidateCompile> results = new ArrayList<>(modelRefs.size());
        for (String modelRef : modelRefs) {
            StructuredRequestBuilder.CompileOutcome outcome =
                    builder.tryCompile(new ModelProfile(modelRef, capabilityOf(modelRef)),
                            authoritativeSchema, multimodalInput);
            results.add(new CandidateCompile(modelRef, outcome.compiled(),
                    outcome.dialectHash(), outcome.reasonCode()));
        }
        return results;
    }

    private List<String> candidateModelRefs(String routingPlanId) {
        if (routingPlanId == null || routingPlanId.isBlank()) {
            return List.of();
        }
        Set<String> bindingIds = new LinkedHashSet<>();
        Optional<RoutingPlanSnapshot> snapshot = specSource.load(routingPlanId);
        snapshot.ifPresent(s -> s.groups().values().forEach(bindingIds::addAll));
        if (bindingIds.isEmpty()) {
            entities.getPlan(routingPlanId).ifPresent(plan -> {
                addIfSet(bindingIds, plan.primaryBindingId());
                addIfSet(bindingIds, plan.fallbackBindingId());
                addIfSet(bindingIds, plan.hardBindingId());
            });
        }
        Set<String> refs = new LinkedHashSet<>();
        for (String bindingId : bindingIds) {
            entities.getModelBinding(bindingId).ifPresent(binding -> {
                if (binding.enabled()) {
                    refs.add(binding.logicalModelId() + "@" + binding.modelName());
                }
            });
        }
        return List.copyOf(refs);
    }

    private StructuredOutputCapability capabilityOf(String modelRef) {
        StructuredCapabilityQueryApi api = capabilities == null ? null : capabilities.getIfAvailable();
        if (api != null) {
            Optional<StructuredCapabilityQueryApi.CapabilityRecord> record = api.current(modelRef);
            if (record.isPresent()) {
                return record.get().capability();
            }
        }
        return StructuredOutputCapability.legacy("schema");
    }

    private static void addIfSet(Set<String> set, String value) {
        if (value != null && !value.isBlank()) {
            set.add(value);
        }
    }
}

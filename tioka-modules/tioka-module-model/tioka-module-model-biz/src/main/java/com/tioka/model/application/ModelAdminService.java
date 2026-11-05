package com.tioka.model.application;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.model.api.dto.ProviderDtos.*;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.domain.ModelEntities.*;
import com.tioka.system.api.spi.SecretStore;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class ModelAdminService {
    private final SecretStore secretStore;
    private final ModelEntities entities;

    public ModelAdminService(SecretStore secretStore, ModelEntities entities) {
        this.secretStore = secretStore;
        this.entities = entities;
    }

    public ProviderView createProvider(CreateProviderRequest req) {
        String id = "prv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String ref = secretStore.put("model-provider", id, req.apiKeyPlain() == null ? "" : req.apiKeyPlain());
        Provider p = new Provider(id, req.name(), req.baseUrl(), ref);
        entities.putProvider(p);
        return new ProviderView(id, p.name(), p.baseUrl(), p.apiKeyRef());
    }

    public String createLogicalModel(CreateLogicalModelRequest req) {
        String id = "lm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        entities.putLogical(new LogicalModel(id, req.name(), req.capabilityTags()));
        return id;
    }

    public String createBinding(CreateBindingRequest req) {
        if (!entities.hasLogical(req.logicalModelId()) || entities.getProvider(req.providerId()).isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "logicalModel 或 provider 不存在");
        }
        String id = "bnd_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        entities.putBinding(new Binding(id, req.logicalModelId(), req.providerId(), req.modelName()));
        return id;
    }

    public RoutingPlanView createPlan(CreateRoutingPlanRequest req) {
        if (!entities.hasBinding(req.primaryBindingId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "binding 不存在");
        }
        if (req.fallbackBindingId() != null && !entities.hasBinding(req.fallbackBindingId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "fallback binding 不存在");
        }
        if (req.hardBindingId() != null && !entities.hasBinding(req.hardBindingId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "hard binding 不存在");
        }
        String id = "rp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String type = req.type() == null ? "FIXED" : req.type();
        RoutingPlan plan = new RoutingPlan(
                id, req.name(), type, req.primaryBindingId(),
                req.fallbackBindingId(), req.hardBindingId(), false, null);
        entities.putPlan(plan);
        return toView(plan);
    }

    public RoutingPlanView publish(String planId) {
        RoutingPlan plan = entities.getPlan(planId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "routing plan 不存在"));
        String hash = sha256(plan.id() + ":" + plan.primaryBindingId() + ":" + plan.type()
                + ":" + plan.fallbackBindingId() + ":" + plan.hardBindingId());
        RoutingPlan published = new RoutingPlan(
                plan.id(), plan.name(), plan.type(), plan.primaryBindingId(),
                plan.fallbackBindingId(), plan.hardBindingId(), true, hash);
        entities.putPlan(published);
        return toView(published);
    }

    public List<RoutingPlanView> listPlans() {
        return entities.plans().stream().map(this::toView).toList();
    }

    public List<ProviderView> listProviders() {
        return entities.providers().stream()
                .map(p -> new ProviderView(p.id(), p.name(), p.baseUrl(), p.apiKeyRef()))
                .toList();
    }

    private RoutingPlanView toView(RoutingPlan p) {
        return new RoutingPlanView(
                p.id(), p.name(), p.type(), p.primaryBindingId(),
                p.fallbackBindingId(), p.hardBindingId(), p.published(), p.snapshotHash());
    }

    private static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

package com.tioka.model.application;

import com.tioka.framework.id.Ids;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.model.api.dto.ProviderDtos.*;
import com.tioka.model.api.gateway.ModelPurpose;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.domain.ModelEntities.*;
import com.tioka.model.infrastructure.ModelDefaultPlanRepository;
import com.tioka.model.infrastructure.persistence.ModelDefaultPlanDO;
import com.tioka.system.api.spi.SecretStore;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class ModelAdminService {
    private final SecretStore secretStore;
    private final ModelEntities entities;
    private final ModelDefaultPlanRepository defaultPlans;
    private final PrincipalHolder principals;

    public ModelAdminService(SecretStore secretStore, ModelEntities entities,
                             ModelDefaultPlanRepository defaultPlans,
                             PrincipalHolder principals) {
        this.secretStore = secretStore;
        this.entities = entities;
        this.defaultPlans = defaultPlans;
        this.principals = principals;
    }

    public ProviderView createProvider(CreateProviderRequest req) {
        String id = Ids.newId("prv");
        String ref = secretStore.put("model-provider", id, req.apiKeyPlain() == null ? "" : req.apiKeyPlain());
        Provider p = new Provider(id, req.name(), req.baseUrl(), ref);
        entities.putProvider(p);
        return new ProviderView(id, p.name(), p.baseUrl(), p.apiKeyRef());
    }

    public String createLogicalModel(CreateLogicalModelRequest req) {
        String id = Ids.newId("lm");
        entities.putLogical(new LogicalModel(id, req.name(), req.capabilityTags()));
        return id;
    }

    public String createModelBinding(CreateModelBindingRequest req) {
        if (!entities.hasLogical(req.logicalModelId()) || entities.getProvider(req.providerId()).isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "logicalModel 或 provider 不存在");
        }
        String kind = normalizeKind(req.kind());
        Integer embeddingDim = req.embeddingDim();
        requireEmbeddingDim(kind, embeddingDim);
        String id = Ids.newId("bnd");
        entities.putBinding(new ModelBinding(
                id, req.logicalModelId(), req.providerId(), req.modelName(),
                kind, req.contextWindow(), embeddingDim,
                req.inputPriceMicros() == null ? 0L : req.inputPriceMicros(),
                req.outputPriceMicros() == null ? 0L : req.outputPriceMicros(),
                req.rpmLimit(), req.tpmLimit(),
                req.enabled() == null || req.enabled()));
        return id;
    }

    public List<ModelBindingView> listModelBindings() {
        return entities.bindings().stream().map(this::toModelBindingView).toList();
    }

    public ModelBindingView updateModelBinding(String id, UpdateModelBindingRequest req) {
        ModelBinding existing = entities.getModelBinding(id)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "binding 不存在"));
        String kind = req.kind() == null || req.kind().isBlank() ? existing.kind() : normalizeKind(req.kind());
        Integer embeddingDim = req.embeddingDim() != null ? req.embeddingDim() : existing.embeddingDim();
        requireEmbeddingDim(kind, embeddingDim);
        ModelBinding updated = new ModelBinding(
                existing.id(), existing.logicalModelId(), existing.providerId(), existing.modelName(),
                kind,
                req.contextWindow() != null ? req.contextWindow() : existing.contextWindow(),
                embeddingDim,
                req.inputPriceMicros() != null ? req.inputPriceMicros() : existing.inputPriceMicros(),
                req.outputPriceMicros() != null ? req.outputPriceMicros() : existing.outputPriceMicros(),
                req.rpmLimit() != null ? req.rpmLimit() : existing.rpmLimit(),
                req.tpmLimit() != null ? req.tpmLimit() : existing.tpmLimit(),
                req.enabled() != null ? req.enabled() : existing.enabled());
        entities.putBinding(updated);
        return toModelBindingView(updated);
    }

    public RoutingPlanView createPlan(CreateRoutingPlanRequest req) {
        String type = req.type() == null ? "FIXED" : req.type();
        if ("DIFFICULTY".equalsIgnoreCase(type)) {
            throw com.tioka.framework.error.NotImplemented.of("routing-plan-DIFFICULTY", "ADR-024");
        }
        if (!entities.hasBinding(req.primaryBindingId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "binding 不存在");
        }
        String chain = resolveFallbackChain(req.fallbackChain(), req.fallbackBindingId());
        validateChainBindings(chain);
        if (req.hardBindingId() != null && !entities.hasBinding(req.hardBindingId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "hard binding 不存在");
        }
        String fallbackBindingId = firstChainId(chain);
        if (fallbackBindingId == null) {
            fallbackBindingId = req.fallbackBindingId();
        }
        if (fallbackBindingId != null && !entities.hasBinding(fallbackBindingId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "fallback binding 不存在");
        }
        String id = Ids.newId("rp");
        RoutingPlan plan = new RoutingPlan(
                id, req.name(), type, req.primaryBindingId(),
                fallbackBindingId, req.hardBindingId(), false, null, chain);
        entities.putPlan(plan);
        return toView(plan);
    }

    public RoutingPlanView publish(String planId) {
        RoutingPlan plan = entities.getPlan(planId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "routing plan 不存在"));
        if ("DIFFICULTY".equalsIgnoreCase(plan.type())) {
            throw com.tioka.framework.error.NotImplemented.of("routing-plan-DIFFICULTY", "ADR-024");
        }
        String hash = sha256(plan.id() + ":" + plan.primaryBindingId() + ":" + plan.type()
                + ":" + plan.fallbackBindingId() + ":" + plan.hardBindingId()
                + ":" + plan.fallbackChain());
        RoutingPlan published = new RoutingPlan(
                plan.id(), plan.name(), plan.type(), plan.primaryBindingId(),
                plan.fallbackBindingId(), plan.hardBindingId(), true, hash, plan.fallbackChain());
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

    public List<DefaultPlanView> listDefaultPlans() {
        return defaultPlans.listAll().stream()
                .map(r -> new DefaultPlanView(r.getPurpose(), r.getRoutingPlanId(), r.getBindingId()))
                .toList();
    }

    public DefaultPlanView upsertDefaultPlan(String purpose, UpsertDefaultPlanRequest req) {
        String normalized = normalizePurpose(purpose);
        if (req.routingPlanId() != null && !req.routingPlanId().isBlank()
                && entities.getPlan(req.routingPlanId()).isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "routing plan 不存在");
        }
        if (req.bindingId() != null && !req.bindingId().isBlank()
                && !entities.hasBinding(req.bindingId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "binding 不存在");
        }
        ModelDefaultPlanDO row = new ModelDefaultPlanDO();
        row.setPurpose(normalized);
        row.setRoutingPlanId(blankToNull(req.routingPlanId()));
        row.setBindingId(blankToNull(req.bindingId()));
        row.setUpdateAt(OffsetDateTime.now());
        row.setUpdateBy(principals.find().map(p -> p.userId()).orElse("system"));
        defaultPlans.upsert(row);
        return new DefaultPlanView(row.getPurpose(), row.getRoutingPlanId(), row.getBindingId());
    }

    private ModelBindingView toModelBindingView(ModelBinding b) {
        return new ModelBindingView(
                b.id(), b.logicalModelId(), b.providerId(), b.modelName(),
                b.kind(), b.contextWindow(), b.embeddingDim(),
                b.inputPriceMicros(), b.outputPriceMicros(),
                b.rpmLimit(), b.tpmLimit(), b.enabled());
    }

    private RoutingPlanView toView(RoutingPlan p) {
        return new RoutingPlanView(
                p.id(), p.name(), p.type(), p.primaryBindingId(),
                p.fallbackBindingId(), p.hardBindingId(), p.published(), p.snapshotHash(),
                p.fallbackChain());
    }

    private static String normalizeKind(String kind) {
        String k = kind == null || kind.isBlank() ? "chat" : kind.trim().toLowerCase(Locale.ROOT);
        if (!k.equals("chat") && !k.equals("embedding") && !k.equals("rerank")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "binding.kind 必须是 chat/embedding/rerank");
        }
        return k;
    }

    private static void requireEmbeddingDim(String kind, Integer embeddingDim) {
        if ("embedding".equals(kind) && embeddingDim == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "kind=embedding 时 embeddingDim 必填");
        }
    }

    private static String normalizePurpose(String purpose) {
        if (purpose == null || purpose.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "purpose 不能为空");
        }
        String upper = purpose.trim().toUpperCase(Locale.ROOT);
        try {
            return ModelPurpose.valueOf(upper).name();
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, "非法 purpose: " + purpose);
        }
    }

    private void validateChainBindings(String chain) {
        if (chain == null || chain.isBlank()) {
            return;
        }
        for (String id : chain.split(",")) {
            String trimmed = id.trim();
            if (!trimmed.isEmpty() && !entities.hasBinding(trimmed)) {
                throw new BizException(ErrorCode.NOT_FOUND, "fallback chain binding 不存在: " + trimmed);
            }
        }
    }

    /** 优先 fallbackChain；否则用单个 fallbackBindingId。 */
    private static String resolveFallbackChain(String fallbackChain, String fallbackBindingId) {
        if (fallbackChain != null && !fallbackChain.isBlank()) {
            return Arrays.stream(fallbackChain.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.joining(","));
        }
        return blankToNull(fallbackBindingId);
    }

    private static String firstChainId(String chain) {
        if (chain == null || chain.isBlank()) {
            return null;
        }
        return Arrays.stream(chain.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElse(null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

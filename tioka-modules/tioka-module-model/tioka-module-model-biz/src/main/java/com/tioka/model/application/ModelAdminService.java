package com.tioka.model.application;

import com.tioka.framework.id.Ids;

import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.model.api.dto.ProviderDtos.*;
import com.tioka.model.api.gateway.ModelPurpose;
import com.tioka.model.application.versioning.ModelConfigVersionService;
import com.tioka.model.api.versioning.ModelHubSpecs;
import com.tioka.model.domain.ModelEntities;
import com.tioka.model.domain.ModelEntities.*;
import com.tioka.system.api.spi.SecretStore;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class ModelAdminService {
    private final SecretStore secretStore;
    private final ModelEntities entities;
    private final PrincipalHolder principals;
    /** MH-08：版本化发布；版本框架未装配时为 no-op。 */
    private final ModelConfigVersionService versioning;

    public ModelAdminService(SecretStore secretStore, ModelEntities entities,
                             PrincipalHolder principals, ModelConfigVersionService versioning) {
        this.secretStore = secretStore;
        this.entities = entities;
        this.principals = principals;
        this.versioning = versioning;
    }

    public ProviderView createProvider(CreateProviderRequest req) {
        String id = Ids.newId("prv");
        String ref = secretStore.put("model-provider", id, req.apiKeyPlain() == null ? "" : req.apiKeyPlain());
        Provider p = new Provider(id, req.name(), req.baseUrl(), ref);
        entities.putProvider(p);
        versioning.publishCurrent(ModelHubSpecs.OT_PROVIDER, id, "创建供应商登记第 1 版");
        return new ProviderView(id, p.name(), p.baseUrl(), p.apiKeyRef());
    }

    /** 兼容 MH-08 之前的 logical model 创建（现为无指向别名）；新代码走 {@link #createAlias}。 */
    @Deprecated
    public String createLogicalModel(CreateLogicalModelRequest req) {
        return createAlias(new CreateAliasRequest(req.name(), req.capabilityTags(), null, null, null));
    }

    /** MH-08：创建别名（ADR-041 §1：稳定名称、用途、指向）。 */
    public String createAlias(CreateAliasRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "别名 name 必填");
        }
        if (entities.findAliasByName(req.name()).isPresent()) {
            throw new BizException(ErrorCode.CONFLICT, "别名已存在: " + req.name());
        }
        String purpose = normalizePurpose(req.purpose());
        String targetType = blankToNull(req.targetType());
        String targetId = blankToNull(req.targetId());
        validateTarget(purpose, targetType, targetId);
        String id = Ids.newId("alias");
        entities.putAlias(new ModelAlias(id, req.name(), req.capabilityTags(), purpose, targetType, targetId, null));
        versioning.publishCurrent(ModelHubSpecs.OT_ALIAS, id, "创建别名登记第 1 版");
        return id;
    }

    public List<AliasView> listAliases() {
        return entities.aliases().stream()
                .map(a -> new AliasView(a.id(), a.name(), a.capabilityTags(), a.purpose(),
                        a.targetType(), a.targetId(), a.currentVersionNo()))
                .toList();
    }

    public String createModelBinding(CreateModelBindingRequest req) {
        if (!entities.hasAlias(req.logicalModelId()) || entities.getProvider(req.providerId()).isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "alias 或 provider 不存在");
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
                req.enabled() == null || req.enabled(),
                req.budgetTokenMonth(), req.budgetCostMonth()));
        versioning.publishCurrent(ModelHubSpecs.OT_BINDING, id, "创建 binding 登记第 1 版");
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
                req.enabled() != null ? req.enabled() : existing.enabled(),
                req.budgetTokenMonth() != null ? req.budgetTokenMonth() : existing.budgetTokenMonth(),
                req.budgetCostMonth() != null ? req.budgetCostMonth() : existing.budgetCostMonth(),
                existing.currentVersionNo());
        entities.putBinding(updated);
        versioning.publishCurrent(ModelHubSpecs.OT_BINDING, id, "更新 binding 发布新版本");
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
                plan.fallbackBindingId(), plan.hardBindingId(), true, hash, plan.fallbackChain(),
                plan.currentVersionNo());
        entities.putPlan(published);
        versioning.publishCurrent(ModelHubSpecs.OT_ROUTING_PLAN, planId, "路由计划发布");
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

    private ModelBindingView toModelBindingView(ModelBinding b) {
        return new ModelBindingView(
                b.id(), b.logicalModelId(), b.providerId(), b.modelName(),
                b.kind(), b.contextWindow(), b.embeddingDim(),
                b.inputPriceMicros(), b.outputPriceMicros(),
                b.rpmLimit(), b.tpmLimit(), b.enabled(),
                b.budgetTokenMonth(), b.budgetCostMonth());
    }

    private RoutingPlanView toView(RoutingPlan p) {
        return new RoutingPlanView(
                p.id(), p.name(), p.type(), p.primaryBindingId(),
                p.fallbackBindingId(), p.hardBindingId(), p.published(), p.snapshotHash(),
                p.fallbackChain());
    }

    /** MH-08（ADR-041 §2 校验）：别名指向的对象存在且用途匹配。 */
    private void validateTarget(String purpose, String targetType, String targetId) {
        if (targetType == null && targetId == null) {
            return;
        }
        if (targetType == null || targetId == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "targetType 与 targetId 必须成对");
        }
        switch (targetType) {
            case ModelHubSpecs.TARGET_ROUTING_PLAN -> {
                if (entities.getPlan(targetId).isEmpty()) {
                    throw new BizException(ErrorCode.NOT_FOUND, "指向的路由计划不存在: " + targetId);
                }
            }
            case ModelHubSpecs.TARGET_BINDING -> {
                ModelBinding b = entities.getModelBinding(targetId)
                        .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "指向的 binding 不存在: " + targetId));
                if (purpose != null && !purposeKindMatches(purpose, b.kind())) {
                    throw new BizException(ErrorCode.BAD_REQUEST,
                            "别名用途 " + purpose + " 与 binding kind " + b.kind() + " 不匹配");
                }
            }
            default -> throw new BizException(ErrorCode.BAD_REQUEST,
                    "targetType 必须是 routing_plan 或 binding: " + targetType);
        }
    }

    /** 用途 → 期望 kind：CHAT/FLOW/DECISION/COMPACTION/EVALUATION/EXTRACTION → chat；EMBEDDING/RERANK/OCR 同名。 */
    static boolean purposeKindMatches(String purpose, String kind) {
        String expected = switch (purpose) {
            case "EMBEDDING" -> "embedding";
            case "RERANK" -> "rerank";
            case "OCR" -> "ocr";
            default -> "chat";
        };
        return expected.equals(kind == null ? "chat" : kind);
    }

    private static String normalizeKind(String kind) {
        String k = kind == null || kind.isBlank() ? "chat" : kind.trim().toLowerCase(Locale.ROOT);
        if (!k.equals("chat") && !k.equals("embedding") && !k.equals("rerank") && !k.equals("ocr")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "binding.kind 必须是 chat/embedding/rerank/ocr");
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
            return null;
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

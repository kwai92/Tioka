package com.tioka.model.api.versioning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.TreeMap;

/**
 * MH-08（ADR-041 §1）：四类 Model Hub 对象的版本 spec 构造与 sys.* 系统别名命名。
 * <p>纯函数，无 Spring/JDBC 依赖；放在 model-api 以便 server 的 V151 Java 迁移
 * （对 biz 模块仅 runtime 依赖）与 model-biz 运行时发布共用，保证 spec 口径单一事实源。
 * <p>ObjectMapper 由调用方传入（ENG-05：统一注入；Flyway 迁移内自带实例，同 V104 先例）。
 * <p>objectType 与 governance {@code CfgStore.updateTypeTablePointer} 的既有映射保持一致。
 */
public final class ModelHubSpecs {

    public static final String OT_PROVIDER = "model_provider";
    public static final String OT_BINDING = "model_binding";
    public static final String OT_ALIAS = "model_alias";
    public static final String OT_ROUTING_PLAN = "model_routing_plan";

    /** 别名指向类型（ADR-041 §1：一个路由计划或一个 binding）。 */
    public static final String TARGET_ROUTING_PLAN = "routing_plan";
    public static final String TARGET_BINDING = "binding";

    private ModelHubSpecs() {
    }

    public static boolean isSupportedType(String objectType) {
        return OT_PROVIDER.equals(objectType) || OT_BINDING.equals(objectType)
                || OT_ALIAS.equals(objectType) || OT_ROUTING_PLAN.equals(objectType);
    }

    /** 供应商（发布型）：协议、Base URL、密钥引用（只存引用）、名称；密钥值不进版本。 */
    public static ObjectNode providerSpec(ObjectMapper mapper, String name, String baseUrl, String apiKeyRef) {
        ObjectNode n = mapper.createObjectNode();
        n.put("name", name);
        n.put("baseUrl", baseUrl);
        n.put("apiKeyRef", apiKeyRef);
        return n;
    }

    /** 模型 binding（发布型）：所属供应商、逻辑模型、模型名、kind、上下文、维度、单价、RPM/TPM。 */
    public static ObjectNode bindingSpec(ObjectMapper mapper, String logicalModelId, String providerId,
                                         String modelName, String kind, Integer contextWindow,
                                         Integer embeddingDim, Long inputPriceMicros, Long outputPriceMicros,
                                         Integer rpmLimit, Integer tpmLimit) {
        ObjectNode n = mapper.createObjectNode();
        n.put("logicalModelId", logicalModelId);
        n.put("providerId", providerId);
        n.put("modelName", modelName);
        n.put("kind", kind == null ? "chat" : kind);
        putInt(n, "contextWindow", contextWindow);
        putInt(n, "embeddingDim", embeddingDim);
        putLong(n, "inputPriceMicros", inputPriceMicros);
        putLong(n, "outputPriceMicros", outputPriceMicros);
        putInt(n, "rpmLimit", rpmLimit);
        putInt(n, "tpmLimit", tpmLimit);
        return n;
    }

    /** 模型别名（发布型）：稳定名称、用途、指向（路由计划或 binding）。 */
    public static ObjectNode aliasSpec(ObjectMapper mapper, String name, String capabilityTags,
                                       String purpose, String targetType, String targetId) {
        ObjectNode n = mapper.createObjectNode();
        n.put("name", name);
        if (capabilityTags != null) {
            n.put("capabilityTags", capabilityTags);
        }
        if (purpose != null) {
            n.put("purpose", purpose);
        }
        if (targetType != null) {
            n.put("targetType", targetType);
        }
        if (targetId != null) {
            n.put("targetId", targetId);
        }
        return n;
    }

    /** 路由计划（发布型）：ADR-024 的 RoutingPlanSpec 现有字段。 */
    public static ObjectNode planSpec(ObjectMapper mapper, String name, String type, String primaryBindingId,
                                      String fallbackBindingId, String hardBindingId, String fallbackChain) {
        ObjectNode n = mapper.createObjectNode();
        n.put("name", name);
        n.put("type", type);
        n.put("primaryBindingId", primaryBindingId);
        if (fallbackBindingId != null) {
            n.put("fallbackBindingId", fallbackBindingId);
        }
        if (hardBindingId != null) {
            n.put("hardBindingId", hardBindingId);
        }
        if (fallbackChain != null) {
            n.put("fallbackChain", fallbackChain);
        }
        return n;
    }

    /** 系统别名固定名：sys.chat / sys.flow / sys.embedding / sys.rerank / sys.decision（按 purpose 小写）。 */
    public static String sysAliasName(String purpose) {
        return "sys." + purpose.toLowerCase(Locale.ROOT);
    }

    /** {@code sys.chat → CHAT}；非 sys.* 前缀返回 null。 */
    public static String sysAliasPurpose(String name) {
        if (name == null || !name.startsWith("sys.") || name.length() <= 4) {
            return null;
        }
        return name.substring(4).toUpperCase(Locale.ROOT);
    }

    /** 键排序后的规范化 JSON（与 governance ConfigSpecNormalizer 的规范化口径一致）。 */
    public static String canonicalJson(ObjectMapper mapper, JsonNode node) {
        try {
            return mapper.writeValueAsString(sort(mapper, node));
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize model hub spec", e);
        }
    }

    /** SHA-256 规范化哈希（与 ConfigSpecNormalizer.contentHash 算法一致）。 */
    public static String contentHash(ObjectMapper mapper, JsonNode node) {
        try {
            byte[] bytes = canonicalJson(mapper, node).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static JsonNode sort(ObjectMapper mapper, JsonNode node) {
        if (node == null || !node.isObject()) {
            return node;
        }
        TreeMap<String, JsonNode> ordered = new TreeMap<>();
        node.fields().forEachRemaining(e -> ordered.put(e.getKey(), e.getValue()));
        ObjectNode out = mapper.createObjectNode();
        ordered.forEach(out::set);
        return out;
    }

    private static void putInt(ObjectNode n, String key, Integer value) {
        if (value != null) {
            n.put(key, value);
        }
    }

    private static void putLong(ObjectNode n, String key, Long value) {
        if (value != null) {
            n.put(key, value);
        }
    }

    /** model_default_plan 一行 → sys.* 别名种子（V151 迁移核心的可测纯函数部分）。 */
    public static AliasSeed sysAliasSeed(String purpose, String routingPlanId, String bindingId) {
        String targetType = routingPlanId != null && !routingPlanId.isBlank()
                ? TARGET_ROUTING_PLAN : TARGET_BINDING;
        String targetId = TARGET_ROUTING_PLAN.equals(targetType) ? routingPlanId : bindingId;
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalStateException("model_default_plan 行缺少指向: purpose=" + purpose);
        }
        return new AliasSeed(
                "alias_sys_" + purpose.toLowerCase(Locale.ROOT),
                sysAliasName(purpose),
                purpose,
                targetType,
                targetId);
    }

    /**
     * sys.* 别名种子（V151：model_default_plan → 系统别名）。
     *
     * @param id         固定 {@code alias_sys_<purpose 小写>}，保证迁移幂等
     * @param name       {@code sys.<purpose 小写>}
     * @param purpose    ModelPurpose 名
     * @param targetType {@code routing_plan | binding}
     * @param targetId   指向对象 ID
     */
    public record AliasSeed(String id, String name, String purpose, String targetType, String targetId) {
        public ObjectNode spec(ObjectMapper mapper) {
            return aliasSpec(mapper, name, null, purpose, targetType, targetId);
        }
    }
}

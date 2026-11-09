package com.tioka.model.api.dto;

public final class ProviderDtos {
    private ProviderDtos() {}
    public record CreateProviderRequest(String name, String baseUrl, String apiKeyPlain) {}
    public record ProviderView(String id, String name, String baseUrl, String apiKeyRef) {}
    /** 兼容 MH-08 之前的 logical model 创建（现为无指向别名）；新代码用 {@link CreateAliasRequest}。 */
    public record CreateLogicalModelRequest(String name, String capabilityTags) {}

    /** MH-08：创建别名（ADR-041 §1：稳定名称、用途、指向）。 */
    public record CreateAliasRequest(
            String name, String capabilityTags, String purpose,
            String targetType, String targetId) {}

    /** MH-08：别名视图；{@code currentVersionNo} 为当前生效版本。 */
    public record AliasView(
            String id, String name, String capabilityTags, String purpose,
            String targetType, String targetId, Integer currentVersionNo) {}

    /**
     * FE-03 / V117：创建 binding；短构造兼容旧调用方。
     * <p>P2-L5（V218）：{@code budgetTokenMonth}/{@code budgetCostMonth} 为供给侧月度预算，
     * NULL = 不限（金额单位微美分，与 model_usage.cost_micros 一致）。
     */
    public record CreateModelBindingRequest(
            String logicalModelId, String providerId, String modelName,
            String kind, Integer embeddingDim,
            Integer contextWindow,
            Long inputPriceMicros, Long outputPriceMicros,
            Integer rpmLimit, Integer tpmLimit,
            Boolean enabled,
            Long budgetTokenMonth, Long budgetCostMonth) {
        public CreateModelBindingRequest(String logicalModelId, String providerId, String modelName) {
            this(logicalModelId, providerId, modelName, null, null, null, null, null, null, null, null,
                    null, null);
        }
        public CreateModelBindingRequest(String logicalModelId, String providerId, String modelName,
                                    String kind, Integer embeddingDim) {
            this(logicalModelId, providerId, modelName, kind, embeddingDim, null, null, null, null, null,
                    null, null, null);
        }
    }

    public record UpdateModelBindingRequest(
            String kind, Integer embeddingDim, Integer contextWindow,
            Long inputPriceMicros, Long outputPriceMicros,
            Integer rpmLimit, Integer tpmLimit, Boolean enabled,
            Long budgetTokenMonth, Long budgetCostMonth) {}

    public record ModelBindingView(
            String id, String logicalModelId, String providerId, String modelName,
            String kind, Integer contextWindow, Integer embeddingDim,
            long inputPriceMicros, long outputPriceMicros,
            Integer rpmLimit, Integer tpmLimit, boolean enabled,
            Long budgetTokenMonth, Long budgetCostMonth) {}

    public record CreateRoutingPlanRequest(
            String name,
            String type,
            String primaryBindingId,
            String fallbackBindingId,
            String hardBindingId,
            String fallbackChain
    ) {
        public CreateRoutingPlanRequest(String name, String type, String primaryBindingId) {
            this(name, type, primaryBindingId, null, null, null);
        }
        public CreateRoutingPlanRequest(String name, String type, String primaryBindingId,
                                        String fallbackBindingId, String hardBindingId) {
            this(name, type, primaryBindingId, fallbackBindingId, hardBindingId, null);
        }
    }

    public record RoutingPlanView(
            String id,
            String name,
            String type,
            String primaryBindingId,
            String fallbackBindingId,
            String hardBindingId,
            boolean published,
            String snapshotHash,
            String fallbackChain
    ) {
        public RoutingPlanView(String id, String name, String type, String primaryBindingId,
                               String fallbackBindingId, String hardBindingId,
                               boolean published, String snapshotHash) {
            this(id, name, type, primaryBindingId, fallbackBindingId, hardBindingId,
                    published, snapshotHash, fallbackBindingId);
        }
    }

    /** GOV-12：保存草稿（expectedRevision 乐观锁；首存传 0）。 */
    public record SaveConfigDraftRequest(com.fasterxml.jackson.databind.JsonNode spec, Long expectedRevision) {}

    public record PublishConfigRequest(String note) {}

    public record RollbackConfigRequest(Integer targetVersionNo, String reason) {}

    /** GOV-12：当前生效版本视图（无版本时空对象）。 */
    public record CurrentConfigView(
            String objectType, String objectId, String versionId,
            Integer versionNo, String status, com.fasterxml.jackson.databind.JsonNode spec) {}
}

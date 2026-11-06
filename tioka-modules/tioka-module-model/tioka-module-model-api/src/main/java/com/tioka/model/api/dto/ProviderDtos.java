package com.tioka.model.api.dto;

public final class ProviderDtos {
    private ProviderDtos() {}
    public record CreateProviderRequest(String name, String baseUrl, String apiKeyPlain) {}
    public record ProviderView(String id, String name, String baseUrl, String apiKeyRef) {}
    public record CreateLogicalModelRequest(String name, String capabilityTags) {}

    /** FE-03 / V117：创建 binding；短构造兼容旧调用方。 */
    public record CreateBindingRequest(
            String logicalModelId, String providerId, String modelName,
            String kind, Integer embeddingDim,
            Integer contextWindow,
            Long inputPriceMicros, Long outputPriceMicros,
            Integer rpmLimit, Integer tpmLimit,
            Boolean enabled) {
        public CreateBindingRequest(String logicalModelId, String providerId, String modelName) {
            this(logicalModelId, providerId, modelName, null, null, null, null, null, null, null, null);
        }
        public CreateBindingRequest(String logicalModelId, String providerId, String modelName,
                                    String kind, Integer embeddingDim) {
            this(logicalModelId, providerId, modelName, kind, embeddingDim, null, null, null, null, null, null);
        }
    }

    public record UpdateBindingRequest(
            String kind, Integer embeddingDim, Integer contextWindow,
            Long inputPriceMicros, Long outputPriceMicros,
            Integer rpmLimit, Integer tpmLimit, Boolean enabled) {}

    public record BindingView(
            String id, String logicalModelId, String providerId, String modelName,
            String kind, Integer contextWindow, Integer embeddingDim,
            long inputPriceMicros, long outputPriceMicros,
            Integer rpmLimit, Integer tpmLimit, boolean enabled) {}

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

    public record DefaultPlanView(String purpose, String routingPlanId, String bindingId) {}

    public record UpsertDefaultPlanRequest(String routingPlanId, String bindingId) {}
}

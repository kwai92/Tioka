package com.tioka.model.api.dto;

public final class ProviderDtos {
    private ProviderDtos() {}
    public record CreateProviderRequest(String name, String baseUrl, String apiKeyPlain) {}
    public record ProviderView(String id, String name, String baseUrl, String apiKeyRef) {}
    public record CreateLogicalModelRequest(String name, String capabilityTags) {}
    public record CreateBindingRequest(String logicalModelId, String providerId, String modelName) {}
    public record CreateRoutingPlanRequest(
            String name,
            String type,
            String primaryBindingId,
            String fallbackBindingId,
            String hardBindingId
    ) {
        public CreateRoutingPlanRequest(String name, String type, String primaryBindingId) {
            this(name, type, primaryBindingId, null, null);
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
            String snapshotHash
    ) {}
}

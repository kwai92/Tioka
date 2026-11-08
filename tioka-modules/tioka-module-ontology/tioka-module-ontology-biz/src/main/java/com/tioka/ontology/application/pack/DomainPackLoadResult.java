package com.tioka.ontology.application.pack;

public record DomainPackLoadResult(String packKey, String version, Status status, String error) {

    public enum Status {
        ENABLED,
        FAILED
    }

    public static DomainPackLoadResult enabled(String packKey, String version) {
        return new DomainPackLoadResult(packKey, version, Status.ENABLED, null);
    }

    public static DomainPackLoadResult failed(String packKey, String version, String error) {
        return new DomainPackLoadResult(packKey, version, Status.FAILED, error);
    }
}

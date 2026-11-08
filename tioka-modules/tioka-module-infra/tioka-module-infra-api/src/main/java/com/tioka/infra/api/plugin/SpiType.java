package com.tioka.infra.api.plugin;

import java.util.Set;

/**
 * ADR-026 插件 SPI 类型。权限允许集按类型收紧（M3-06 §1）。
 */
public enum SpiType {
    DOMAIN_PACK(Set.of("cpu")),
    FLOW_NODE(Set.of("cpu")),
    CONNECTOR(Set.of("cpu", "network")),
    NOTIFICATION(Set.of("cpu", "network")),
    OTHER(Set.of("cpu", "network", "filesystem"));

    private final Set<String> allowedPermissions;

    SpiType(Set<String> allowedPermissions) {
        this.allowedPermissions = allowedPermissions;
    }

    public Set<String> allowedPermissions() {
        return allowedPermissions;
    }
}

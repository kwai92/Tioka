package com.tioka.infra.api.plugin;

public record PluginLoadResult(
        String pluginKey,
        String version,
        PluginStatus status,
        String error
) {
    public enum PluginStatus {
        ENABLED,
        FAILED
    }

    public static PluginLoadResult enabled(String pluginKey, String version) {
        return new PluginLoadResult(pluginKey, version, PluginStatus.ENABLED, null);
    }

    public static PluginLoadResult failed(String pluginKey, String version, String error) {
        return new PluginLoadResult(pluginKey, version, PluginStatus.FAILED, error);
    }
}

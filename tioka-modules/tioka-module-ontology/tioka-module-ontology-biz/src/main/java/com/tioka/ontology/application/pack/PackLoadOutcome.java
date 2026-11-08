package com.tioka.ontology.application.pack;

/**
 * 插件宿主启用结果的精简视图（避免 ont-biz 编译期依赖 infra-api）。
 */
public record PackLoadOutcome(String pluginKey, String version, boolean enabled, String error) {

    public static PackLoadOutcome enabled(String pluginKey, String version) {
        return new PackLoadOutcome(pluginKey, version, true, null);
    }

    public static PackLoadOutcome failed(String pluginKey, String version, String error) {
        return new PackLoadOutcome(pluginKey, version, false, error);
    }
}

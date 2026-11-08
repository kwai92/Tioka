package com.tioka.infra.api.plugin;

import java.util.List;

/**
 * 插件清单（jar 内 tioka-plugin.json）。
 */
public record PluginDescriptor(
        String pluginKey,
        String version,
        SpiType spiType,
        String spiVersion,
        List<String> permissions,
        String entryClass,
        String signature
) {
}

package com.tioka.ontology.application.pack;

import com.tioka.infra.api.plugin.PluginHost;
import com.tioka.infra.api.plugin.PluginLoadResult;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link PackPluginGateway} 生产适配器：接 infra FND-12 {@link PluginHost}
 * （plugin_registry / plugin_adapter 落表 + 宿主生命周期）。加载结果换算为
 * {@link PackLoadOutcome}；宿主加载失败不抛出，返回 FAILED。
 */
public class PluginHostPackGateway implements PackPluginGateway {

    private final PluginHost host;

    public PluginHostPackGateway(PluginHost host) {
        this.host = Objects.requireNonNull(host, "host");
    }

    @Override
    public PackLoadOutcome enable(Path jarPath) {
        PluginLoadResult result = host.enable(jarPath);
        if (result == null) {
            return PackLoadOutcome.failed("?", "?", "host returned null");
        }
        return result.status() == PluginLoadResult.PluginStatus.ENABLED
                ? PackLoadOutcome.enabled(result.pluginKey(), result.version())
                : PackLoadOutcome.failed(result.pluginKey(), result.version(), result.error());
    }

    @Override
    public Optional<Object> getEntry(String pluginKey, String version) {
        return host.getEntry(pluginKey, version);
    }
}

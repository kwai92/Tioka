package com.tioka.ontology.application.pack;

import java.nio.file.Path;
import java.util.Optional;

/**
 * 领域包对 FND-12 插件宿主的端口。生产侧适配 {@code PluginHost}；本包默认未接线。
 */
public interface PackPluginGateway {

    PackLoadOutcome enable(Path jarPath);

    Optional<Object> getEntry(String pluginKey, String version);
}

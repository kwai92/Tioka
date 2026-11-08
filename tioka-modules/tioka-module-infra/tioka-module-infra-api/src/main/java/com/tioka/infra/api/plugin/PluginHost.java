package com.tioka.infra.api.plugin;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 插件宿主（FND-12）。唯一允许创建外部 jar 类加载器的入口。
 */
public interface PluginHost {

    /** 启用指定 jar（管理员触发）；失败不抛到调用方之外，返回 FAILED。 */
    PluginLoadResult enable(Path jarPath);

    List<PluginLoadResult> list();

    Optional<Object> getEntry(String pluginKey, String version);
}

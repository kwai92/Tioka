package com.tioka.infra.application.plugin;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Set;

/**
 * 每个插件独立类加载器：父加载器只暴露声明的 -api / framework / JDK 包。
 */
public final class PluginClassLoader extends URLClassLoader {

    private static final Set<String> EXPOSED_PREFIXES = Set.of(
            "com.tioka.infra.api.",
            "com.tioka.framework.",
            "java.",
            "javax.",
            "jakarta."
    );

    public PluginClassLoader(URL jarUrl, ClassLoader parent) {
        super(new URL[]{jarUrl}, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return finish(loaded, resolve);
            }
            // 先查插件自身
            try {
                return finish(findClass(name), resolve);
            } catch (ClassNotFoundException ignored) {
                // continue
            }
            if (isExposed(name)) {
                return finish(getParent().loadClass(name), resolve);
            }
            if (name.startsWith("com.tioka.")) {
                throw new ClassNotFoundException("plugin isolation blocked: " + name);
            }
            return finish(getParent().loadClass(name), resolve);
        }
    }

    private Class<?> finish(Class<?> type, boolean resolve) {
        if (resolve) {
            resolveClass(type);
        }
        return type;
    }

    private static boolean isExposed(String name) {
        for (String prefix : EXPOSED_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() throws IOException {
        super.close();
    }
}

package com.tioka.infra.application.plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.infra.api.plugin.PluginDescriptor;
import com.tioka.infra.api.plugin.PluginHost;
import com.tioka.infra.api.plugin.PluginLoadResult;
import com.tioka.infra.api.plugin.SpiType;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * FND-12 插件宿主实现。加载失败隔离；默认由特性开关控制是否注册为 Bean。
 */
public class DefaultPluginHost implements PluginHost {

    private final ObjectMapper objectMapper;
    private final PluginSignatureVerifier signatureVerifier;
    private final ConcurrentMap<String, LoadedPlugin> loaded = new ConcurrentHashMap<>();
    private final List<PluginLoadResult> history = new ArrayList<>();

    public DefaultPluginHost(ObjectMapper objectMapper, PluginSignatureVerifier signatureVerifier) {
        this.objectMapper = objectMapper;
        this.signatureVerifier = signatureVerifier;
    }

    @Override
    public PluginLoadResult enable(Path jarPath) {
        String key = jarPath.getFileName().toString();
        String version = "?";
        try {
            PluginDescriptor descriptor = readDescriptor(jarPath);
            key = descriptor.pluginKey();
            version = descriptor.version();
            byte[] entryBytes = readEntryClassBytes(jarPath, descriptor.entryClass());
            signatureVerifier.verify(descriptor, entryBytes);
            checkPermissions(descriptor);
            checkSpiVersion(descriptor);

            URL jarUrl = jarPath.toUri().toURL();
            PluginClassLoader classLoader = new PluginClassLoader(jarUrl, DefaultPluginHost.class.getClassLoader());
            Class<?> entry = Class.forName(descriptor.entryClass(), true, classLoader);
            Object instance = entry.getDeclaredConstructor().newInstance();

            loaded.put(mapKey(key, version), new LoadedPlugin(descriptor, classLoader, instance));
            PluginLoadResult ok = PluginLoadResult.enabled(key, version);
            synchronized (history) {
                history.add(ok);
            }
            return ok;
        } catch (Exception e) {
            PluginLoadResult failed = PluginLoadResult.failed(key, version, rootMessage(e));
            synchronized (history) {
                history.add(failed);
            }
            return failed;
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? error.toString() : message;
    }

    @Override
    public List<PluginLoadResult> list() {
        synchronized (history) {
            return List.copyOf(history);
        }
    }

    @Override
    public Optional<Object> getEntry(String pluginKey, String version) {
        LoadedPlugin plugin = loaded.get(mapKey(pluginKey, version));
        return plugin == null ? Optional.empty() : Optional.of(plugin.entry());
    }

    private PluginDescriptor readDescriptor(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry("tioka-plugin.json");
            if (entry == null) {
                throw new PluginLoadException("missing tioka-plugin.json");
            }
            try (InputStream in = jar.getInputStream(entry)) {
                return objectMapper.readValue(in, PluginDescriptor.class);
            }
        }
    }

    private static byte[] readEntryClassBytes(Path jarPath, String entryClass) throws IOException {
        String path = entryClass.replace('.', '/') + ".class";
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(path);
            if (entry == null) {
                throw new PluginLoadException("missing entry class: " + entryClass);
            }
            try (InputStream in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static void checkPermissions(PluginDescriptor descriptor) {
        SpiType type = descriptor.spiType();
        if (type == null) {
            throw new PluginLoadException("spiType required");
        }
        List<String> declared = descriptor.permissions() == null ? List.of() : descriptor.permissions();
        for (String permission : declared) {
            if (!type.allowedPermissions().contains(permission)) {
                throw new PluginLoadException(
                        "permission denied: " + permission + " not allowed for " + type);
            }
        }
    }

    private static void checkSpiVersion(PluginDescriptor descriptor) {
        if (descriptor.spiVersion() == null || descriptor.spiVersion().isBlank()) {
            throw new PluginLoadException("spiVersion required");
        }
        if (!descriptor.spiVersion().startsWith("1")) {
            throw new PluginLoadException("incompatible spiVersion: " + descriptor.spiVersion());
        }
    }

    private static String mapKey(String pluginKey, String version) {
        return pluginKey + "@" + version;
    }

    private record LoadedPlugin(PluginDescriptor descriptor, PluginClassLoader classLoader, Object entry) {
    }
}

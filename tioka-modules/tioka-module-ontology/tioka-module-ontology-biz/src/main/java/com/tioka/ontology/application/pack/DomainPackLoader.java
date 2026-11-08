package com.tioka.ontology.application.pack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.ontology.application.def.DefinitionService;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * ONT-05 领域包加载：验权限（仅 cpu）→ 经 FND-12 端口启用 → 登记多版本并存 → 注册模型定义草稿。
 * <p>默认不作为 Spring Bean 装配（特性未接线），不改变 M2 行为。
 */
public class DomainPackLoader {

    private static final Set<String> ALLOWED_PERMISSIONS = Set.of("cpu");
    private static final String MANIFEST_ENTRY = "tioka-plugin.json";
    private static final String SPI_DOMAIN_PACK = "DOMAIN_PACK";

    private final ObjectMapper objectMapper;
    private final PackPluginGateway gateway;
    private final DefinitionService definitionService;
    private final ConcurrentMap<String, DomainPack> loaded = new ConcurrentHashMap<>();
    private final PureFunctionExecutor pureFunctionExecutor;

    public DomainPackLoader(
            ObjectMapper objectMapper,
            PackPluginGateway gateway,
            DefinitionService definitionService) {
        this(objectMapper, gateway, definitionService, new PureFunctionExecutor());
    }

    public DomainPackLoader(
            ObjectMapper objectMapper,
            PackPluginGateway gateway,
            DefinitionService definitionService,
            PureFunctionExecutor pureFunctionExecutor) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.definitionService = Objects.requireNonNull(definitionService, "definitionService");
        this.pureFunctionExecutor = Objects.requireNonNull(pureFunctionExecutor, "pureFunctionExecutor");
    }

    /**
     * 从 jar 加载。声明超出 {@code cpu} 的权限时拒绝，且不调用宿主。
     */
    public DomainPackLoadResult load(Path jarPath) {
        Objects.requireNonNull(jarPath, "jarPath");
        String packKey = jarPath.getFileName().toString();
        String version = "?";
        try {
            DomainPackManifest manifest = readManifest(jarPath);
            packKey = manifest.resolvedPackKey();
            version = manifest.version() == null ? "?" : manifest.version();
            rejectDisallowedPermissions(manifest);

            PackLoadOutcome outcome = gateway.enable(jarPath);
            if (!outcome.enabled()) {
                return DomainPackLoadResult.failed(
                        outcome.pluginKey() == null ? packKey : outcome.pluginKey(),
                        outcome.version() == null ? version : outcome.version(),
                        outcome.error());
            }

            Object entry = gateway.getEntry(outcome.pluginKey(), outcome.version())
                    .orElseThrow(() -> new IllegalStateException(
                            "plugin entry missing after enable: " + outcome.pluginKey() + "@" + outcome.version()));
            if (!(entry instanceof DomainPack pack)) {
                return DomainPackLoadResult.failed(packKey, version, "entryClass is not DomainPack");
            }
            return register(pack);
        } catch (Exception e) {
            return DomainPackLoadResult.failed(packKey, version, rootMessage(e));
        }
    }

    /** 进程内登记（内置包 / 测试 / 宿主已实例化后的入口）。多版本按 packKey@version 并存。 */
    public DomainPackLoadResult register(DomainPack pack) {
        Objects.requireNonNull(pack, "pack");
        Objects.requireNonNull(pack.packKey(), "packKey");
        Objects.requireNonNull(pack.version(), "version");
        Objects.requireNonNull(pack.definition(), "definition");

        loaded.put(mapKey(pack.packKey(), pack.version()), pack);
        definitionService.saveDraft(pack.packKey(), pack.version(), pack.definition());
        return DomainPackLoadResult.enabled(pack.packKey(), pack.version());
    }

    public Optional<DomainPack> get(String packKey, String version) {
        return Optional.ofNullable(loaded.get(mapKey(packKey, version)));
    }

    public List<DomainPack> list(String packKey) {
        String prefix = packKey + "@";
        return loaded.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(e -> e.getValue())
                .toList();
    }

    public PureFunctionExecutor pureFunctionExecutor() {
        return pureFunctionExecutor;
    }

    private DomainPackManifest readManifest(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(MANIFEST_ENTRY);
            if (entry == null) {
                throw new IllegalArgumentException("missing " + MANIFEST_ENTRY);
            }
            try (InputStream in = jar.getInputStream(entry)) {
                return objectMapper.readValue(in, DomainPackManifest.class);
            }
        }
    }

    private static void rejectDisallowedPermissions(DomainPackManifest manifest) {
        String spi = manifest.spiType();
        if (spi != null && !spi.isBlank() && !SPI_DOMAIN_PACK.equalsIgnoreCase(spi)
                && !"domain-pack".equalsIgnoreCase(spi)) {
            throw new IllegalArgumentException("spiType must be DOMAIN_PACK, got: " + spi);
        }
        List<String> permissions = manifest.permissions();
        for (String permission : permissions) {
            if (!ALLOWED_PERMISSIONS.contains(permission)) {
                throw new IllegalArgumentException(
                        "permission denied: " + permission + " not allowed for domain-pack (only cpu)");
            }
        }
    }

    private static String mapKey(String packKey, String version) {
        return packKey + "@" + version;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? error.toString() : message;
    }
}

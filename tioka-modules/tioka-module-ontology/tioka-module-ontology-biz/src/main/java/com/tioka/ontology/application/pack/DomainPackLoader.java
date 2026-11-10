package com.tioka.ontology.application.pack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.ontology.application.def.DefinitionService;
import com.tioka.ontology.application.schema.SchemaRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * ONT-05 领域包加载（M3-03 §10 加载顺序）：
 * 验签（缺省关闭，{@link PackSignatureVerifier}）→ 检查 {@code permissions}（仅 cpu）→
 * 经 FND-12 端口启用（{@link PackPluginGateway}，生产侧 {@link PluginHostPackGateway}）→
 * 独立类加载器（{@link IsolatedPackClassLoader}：只暴露 ontology-api + SPI 契约 + JDK）实例化 →
 * 注册 Schema（SCH-01）→ 登记模型定义草稿（ONT-01）→ 登记 CEL 函数 / 校验器 / 折叠引擎。
 *
 * <p>默认不作为 Spring Bean 装配（{@code tioka.ontology.pack.enabled=true} 打开）。
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
    private final PackSignatureVerifier signatureVerifier;
    private final SchemaRegistry schemaRegistry;
    private final URL ontologyApiCodeSource;

    public DomainPackLoader(
            ObjectMapper objectMapper,
            PackPluginGateway gateway,
            DefinitionService definitionService) {
        this(objectMapper, gateway, definitionService, new PureFunctionExecutor(),
                new PackSignatureVerifier(List.of(), false), null);
    }

    public DomainPackLoader(
            ObjectMapper objectMapper,
            PackPluginGateway gateway,
            DefinitionService definitionService,
            PureFunctionExecutor pureFunctionExecutor) {
        this(objectMapper, gateway, definitionService, pureFunctionExecutor,
                new PackSignatureVerifier(List.of(), false), null);
    }

    /** 全参：验签器（required=false 时跳过）+ Schema 登记表（manifest.schemas 注册）+ ontology-api codeSource。 */
    public DomainPackLoader(
            ObjectMapper objectMapper,
            PackPluginGateway gateway,
            DefinitionService definitionService,
            PureFunctionExecutor pureFunctionExecutor,
            PackSignatureVerifier signatureVerifier,
            SchemaRegistry schemaRegistry) {
        this(objectMapper, gateway, definitionService, pureFunctionExecutor, signatureVerifier,
                schemaRegistry, codeSourceOf(DomainPack.class));
    }

    public DomainPackLoader(
            ObjectMapper objectMapper,
            PackPluginGateway gateway,
            DefinitionService definitionService,
            PureFunctionExecutor pureFunctionExecutor,
            PackSignatureVerifier signatureVerifier,
            SchemaRegistry schemaRegistry,
            URL ontologyApiCodeSource) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.definitionService = Objects.requireNonNull(definitionService, "definitionService");
        this.pureFunctionExecutor = Objects.requireNonNull(pureFunctionExecutor, "pureFunctionExecutor");
        this.signatureVerifier = Objects.requireNonNull(signatureVerifier, "signatureVerifier");
        this.schemaRegistry = schemaRegistry;
        this.ontologyApiCodeSource = ontologyApiCodeSource;
    }

    private static URL codeSourceOf(Class<?> clazz) {
        java.security.ProtectionDomain pd = clazz.getProtectionDomain();
        if (pd != null && pd.getCodeSource() != null && pd.getCodeSource().getLocation() != null) {
            return pd.getCodeSource().getLocation();
        }
        return null;
    }

    /**
     * 从 jar 加载（M3-03 §10 顺序）。声明超出 {@code cpu} 的权限 / 验签失败 → 拒绝，且不调用宿主。
     */
    public DomainPackLoadResult load(Path jarPath) {
        Objects.requireNonNull(jarPath, "jarPath");
        String packKey = jarPath.getFileName().toString();
        String version = "?";
        try {
            DomainPackManifest manifest = readManifest(jarPath);
            packKey = manifest.resolvedPackKey();
            version = manifest.version() == null ? "?" : manifest.version();

            // 1. 权限检查（在宿主与类加载之前）
            rejectDisallowedPermissions(manifest);

            // 2. 验签（缺省关闭；覆盖「清单规范串 + 入口类字节」）
            byte[] entryBytes = readEntryBytes(jarPath, manifest.entryClass());
            signatureVerifier.verify(manifest, entryBytes);

            // 3. 经宿主端口启用（生产侧 plugin_registry / plugin_adapter）
            PackLoadOutcome outcome = gateway.enable(jarPath);
            if (!outcome.enabled()) {
                return DomainPackLoadResult.failed(
                        outcome.pluginKey() == null ? packKey : outcome.pluginKey(),
                        outcome.version() == null ? version : outcome.version(),
                        outcome.error());
            }

            // 4. 独立类加载器实例化（只暴露 ontology-api + SPI 契约 + JDK）
            DomainPack pack = instantiateIsolated(jarPath, manifest);

            // 5. manifest.schemas[] → SchemaRegistry（SCH-01）
            registerSchemas(jarPath, manifest);

            // 6. 登记：模型定义草稿（ONT-01）+ 多版本并存 + 校验器 / 折叠引擎 / CEL 函数挂接
            return register(pack);
        } catch (Exception e) {
            return DomainPackLoadResult.failed(packKey, version, rootMessage(e));
        }
    }

    private DomainPack instantiateIsolated(Path jarPath, DomainPackManifest manifest) throws Exception {
        if (manifest.entryClass() == null || manifest.entryClass().isBlank()) {
            throw new IllegalArgumentException("entryClass required");
        }
        try (IsolatedPackClassLoader classLoader = IsolatedPackClassLoader.forPack(jarPath, ontologyApiCodeSource)) {
            Class<?> entry = Class.forName(manifest.entryClass(), true, classLoader);
            Object instance = entry.getDeclaredConstructor().newInstance();
            if (!(instance instanceof DomainPack pack)) {
                throw new IllegalArgumentException("entryClass is not DomainPack: " + manifest.entryClass());
            }
            return pack;
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

    /** 已登记包的校验器：{@code (packKey, version, validatorId)}。 */
    public Optional<ModelValidator> validator(String packKey, String version, String validatorId) {
        return get(packKey, version).flatMap(p -> p.validators().stream()
                .filter(v -> validatorId.equals(v.validatorId()))
                .findFirst());
    }

    /** 已登记包的折叠引擎。 */
    public Optional<FoldingEngine> folding(String packKey, String version) {
        return get(packKey, version).flatMap(DomainPack::folding);
    }

    /** 已登记包的投影钩子。 */
    public List<ProjectionHook> projections(String packKey, String version) {
        return get(packKey, version).map(DomainPack::projections).orElse(List.of());
    }

    /** 已登记包的 CEL 函数（带实现；供 {@code RuleEvaluator} 挂接 CEL 环境）。 */
    public List<CelFunction> packFunctions() {
        Map<String, CelFunction> out = new LinkedHashMap<>();
        for (DomainPack p : loaded.values()) {
            for (CelFunction f : p.celFunctions()) {
                if (f != null && f.name() != null) {
                    out.putIfAbsent(f.name(), f);
                }
            }
        }
        return List.copyOf(out.values());
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

    private static byte[] readEntryBytes(Path jarPath, String entryClass) throws IOException {
        if (entryClass == null || entryClass.isBlank()) {
            return new byte[0];
        }
        String path = entryClass.replace('.', '/') + ".class";
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(path);
            if (entry == null) {
                return new byte[0];
            }
            try (InputStream in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    /** manifest.schemas[]（jar 内 JSON，文件名 {@code {key}@{version}.json}）→ SchemaRegistry。 */
    private void registerSchemas(Path jarPath, DomainPackManifest manifest) throws IOException {
        if (schemaRegistry == null || manifest.schemas() == null || manifest.schemas().isEmpty()) {
            return;
        }
        String packKey = manifest.resolvedPackKey();
        for (String schemaPath : manifest.schemas()) {
            String fileName = schemaPath;
            int slash = fileName.lastIndexOf('/');
            if (slash >= 0) {
                fileName = fileName.substring(slash + 1);
            }
            if (!fileName.endsWith(".json")) {
                throw new IllegalArgumentException("schema 文件须为 JSON: " + schemaPath);
            }
            String base = fileName.substring(0, fileName.length() - ".json".length());
            int at = base.lastIndexOf('@');
            if (at <= 0) {
                throw new IllegalArgumentException("schema 文件名须为 {key}@{version}.json: " + schemaPath);
            }
            String schemaKey = base.substring(0, at);
            int version = Integer.parseInt(base.substring(at + 1));
            try (JarFile jar = new JarFile(jarPath.toFile())) {
                JarEntry entry = jar.getJarEntry(schemaPath);
                if (entry == null) {
                    throw new IllegalArgumentException("schema 文件缺失: " + schemaPath);
                }
                try (InputStream in = jar.getInputStream(entry)) {
                    JsonNode tree = objectMapper.readTree(in);
                    schemaRegistry.publish(schemaKey, version, tree, packKey);
                }
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

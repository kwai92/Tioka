package com.tioka.ontology.application.pack;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.ontology.api.def.DefStatus;
import com.tioka.ontology.api.def.ModelDefinition;
import com.tioka.ontology.api.def.ModelDefinition.AttrDef;
import com.tioka.ontology.api.def.ModelDefinition.AttrGroup;
import com.tioka.ontology.api.def.ModelDefinition.BusinessKeyRule;
import com.tioka.ontology.api.def.ModelDefinition.ObjectType;
import com.tioka.ontology.api.def.ModelDefinition.StatementParam;
import com.tioka.ontology.api.def.ModelDefinition.StatementType;
import com.tioka.ontology.api.def.ModelDefinition.Vocabulary;
import com.tioka.ontology.api.def.PathValueKind;
import com.tioka.ontology.api.def.PublishedDefinition;
import com.tioka.ontology.application.def.DefinitionService;
import com.tioka.ontology.application.schema.SchemaRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ONT-05 验收：networkDenied / reload（M3-03 §10）。
 */
class DomainPackLoaderIT {

    private final ObjectMapper mapper = new ObjectMapper();
    private DefinitionService definitionService;
    private DomainPackLoader loader;
    private RecordingGateway gateway;

    @TempDir
    Path temp;

    @BeforeEach
    void setUp() {
        definitionService = new DefinitionService(mapper, new SchemaRegistry(mapper));
        gateway = new RecordingGateway();
        loader = new DomainPackLoader(mapper, gateway, definitionService);
    }

    @Test
    void networkDenied() throws Exception {
        Path jar = writePluginJar(
                "lease.bad",
                "1.0.0",
                List.of("network"),
                "com.demo.UnusedEntry");

        DomainPackLoadResult result = loader.load(jar);

        assertEquals(DomainPackLoadResult.Status.FAILED, result.status());
        assertTrue(
                result.error() != null && result.error().toLowerCase().contains("permission"),
                () -> "error=" + result.error());
        assertTrue(gateway.enableCalls.isEmpty(), "不得把声明 network 的领域包交给宿主加载");
    }

    @Test
    void signatureMismatch拒绝且不交宿主() throws Exception {
        // required=true + 无效公钥下的假签名 → 拒载，且 gateway.enable 不被调用
        var verifier = new PackSignatureVerifier(List.of(
                java.util.HexFormat.of().formatHex(rsaPublicEncoded())), true);
        DomainPackLoader strict = new DomainPackLoader(mapper, gateway, definitionService,
                new PureFunctionExecutor(), verifier, new SchemaRegistry(mapper));
        Path jar = writePluginJar("lease.signed", "1.0.0", List.of("cpu"), "com.demo.UnusedEntry");

        DomainPackLoadResult result = strict.load(jar);

        assertEquals(DomainPackLoadResult.Status.FAILED, result.status());
        assertTrue(result.error() != null && result.error().contains("signature"),
                () -> "error=" + result.error());
        assertTrue(gateway.enableCalls.isEmpty(), "验签失败不得把领域包交给宿主加载");
    }

    @Test
    void runtimeCompiledJar端到端加载与schema注册() throws Exception {
        // 1. 运行时编译入口类（实现 DomainPack SPI，带一个 CEL 函数实现）
        Path outDir = Files.createDirectories(temp.resolve("classes"));
        String source = """
                import com.tioka.ontology.api.def.ModelDefinition;
                import com.tioka.ontology.api.def.PathValueKind;
                import com.tioka.ontology.application.pack.*;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;

                public class LeasePack implements DomainPack {
                    public String packKey() { return "lease-pack"; }
                    public String version() { return "1.0.0"; }
                    public ModelDefinition definition() {
                        return new ModelDefinition(
                                1, "lease.contract", "@contract.model",
                                new ModelDefinition.BusinessKeyRule(List.of("storeCode", "contractNo"), "fileHash"),
                                List.of(), List.of(), List.of(), Map.of(),
                                ModelDefinition.ModificationVocabulary.empty(),
                                List.of(), List.of(), List.of());
                    }
                    public PromptBundle prompts() { return PromptBundle.empty(); }
                    public List<ModelValidator> validators() { return List.of(); }
                    public Optional<FoldingEngine> folding() { return Optional.empty(); }
                    public List<ProjectionHook> projections() { return List.of(); }
                    public List<CelFunction> celFunctions() {
                        return List.of(new CelFunction("packMonths", "包内月数", arg -> 12L));
                    }
                }
                """;
        Path sourceFile = temp.resolve("LeasePack.java");
        Files.writeString(sourceFile, source);
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        try (var fm = compiler.getStandardFileManager(null, null, null)) {
            var result = compiler.getTask(null, fm, null,
                    List.of("-classpath", System.getProperty("java.class.path"), "-d", outDir.toString()),
                    null, fm.getJavaFileObjects(sourceFile)).call();
            assertTrue(Boolean.TRUE.equals(result), "运行时编译 LeasePack 失败");
        }

        // 2. 打包 jar：清单（带 schema 声明）+ 入口类 + schema 文件
        String manifestJson = """
                {
                  "pluginKey": "lease-pack",
                  "version": "1.0.0",
                  "spiType": "DOMAIN_PACK",
                  "spiVersion": "1",
                  "permissions": ["cpu"],
                  "entryClass": "LeasePack",
                  "definitions": [],
                  "schemas": ["schemas/lease.contract.feeItems@2.json"],
                  "prompts": "prompts/"
                }
                """;
        SchemaRegistry schemaRegistry = new SchemaRegistry(mapper);
        EnablingGateway enabling = new EnablingGateway();
        DomainPackLoader endToEnd = new DomainPackLoader(mapper, enabling, definitionService,
                new PureFunctionExecutor(), new PackSignatureVerifier(List.of(), false), schemaRegistry);
        Path jar = temp.resolve("lease-pack-1.0.0.jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("tioka-plugin.json"));
            jos.write(manifestJson.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("LeasePack.class"));
            jos.write(Files.readAllBytes(outDir.resolve("LeasePack.class")));
            jos.closeEntry();
            jos.putNextEntry(new JarEntry("schemas/lease.contract.feeItems@2.json"));
            jos.write(("""
                    {"type":"object","required":["amount"],
                     "properties":{"amount":{"type":"integer","title":"金额"}}}
                    """).getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        // 3. 端到端加载：实例化（隔离类加载器）→ schema 注册 → 登记
        DomainPackLoadResult result = endToEnd.load(jar);
        assertEquals(DomainPackLoadResult.Status.ENABLED, result.status(), () -> "error=" + result.error());
        assertEquals("lease-pack", result.packKey());

        DomainPack pack = endToEnd.get("lease-pack", "1.0.0").orElseThrow();
        assertTrue(pack.getClass().getClassLoader() != DomainPack.class.getClassLoader(),
                "入口类必须由隔离类加载器加载");
        assertEquals("packMonths", pack.celFunctions().get(0).name());
        assertTrue(endToEnd.packFunctions().stream().anyMatch(f -> "packMonths".equals(f.name())));

        // manifest.schemas[] → SchemaRegistry（{key}@{version}.json 命名约定）
        assertTrue(schemaRegistry.get("lease.contract.feeItems@2").isPresent());
        assertTrue(schemaRegistry.validate("lease.contract.feeItems@2",
                mapper.readTree("{\"amount\": 1}")).isEmpty());
        assertTrue(schemaRegistry.validate("lease.contract.feeItems@2",
                mapper.readTree("{}")).stream().anyMatch(e -> "SCHEMA_VIOLATION".equals(e.code())));

        // 登记的模型定义落 DefinitionService 草稿
        assertTrue(definitionService.getDraft("lease.contract").isPresent());
    }

    @Test
    void register挂接validatorsFoldingFunctions() {
        var violation = new Violation("lease.amount-traceable", "/amount", "missing sources", Violation.Severity.REJECT);
        ModelValidator validator = new ModelValidator() {
            @Override public String validatorId() { return "lease.amount-traceable"; }
            @Override public List<Violation> validate(Map<String, Object> draft, com.tioka.ontology.api.doc.DocumentView docs) {
                return List.of(violation);
            }
        };
        FoldingEngine folding = (storeKey, chain) -> Map.of("storeKey", storeKey);
        ProjectionHook hook = new ProjectionHook() {
            @Override public String hookId() { return "lease.writeback"; }
            @Override public void project(Map<String, Object> confirmedModel) { }
        };
        CelFunction fn = new CelFunction("packRate", "包内费率", arg -> 0.05);
        DomainPack pack = new DomainPack() {
            @Override public String packKey() { return "lease"; }
            @Override public String version() { return "1.0.0"; }
            @Override public ModelDefinition definition() { return sampleDef(); }
            @Override public PromptBundle prompts() { return PromptBundle.empty(); }
            @Override public List<ModelValidator> validators() { return List.of(validator); }
            @Override public Optional<FoldingEngine> folding() { return Optional.of(folding); }
            @Override public List<ProjectionHook> projections() { return List.of(hook); }
            @Override public List<CelFunction> celFunctions() { return List.of(fn); }
        };

        assertEquals(DomainPackLoadResult.Status.ENABLED, loader.register(pack).status());

        assertEquals(1, loader.validator("lease", "1.0.0", "lease.amount-traceable").orElseThrow()
                .validate(Map.of(), null).size());
        assertEquals("S001", loader.folding("lease", "1.0.0").orElseThrow()
                .fold("S001", List.of()).get("storeKey"));
        assertEquals("lease.writeback", loader.projections("lease", "1.0.0").get(0).hookId());
        assertTrue(loader.packFunctions().stream().anyMatch(f -> "packRate".equals(f.name())));
        assertTrue(loader.validator("lease", "1.0.0", "missing").isEmpty());
        assertTrue(loader.folding("lease", "9.9.9").isEmpty());
    }

    private static byte[] rsaPublicEncoded() throws Exception {
        var gen = java.security.KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair().getPublic().getEncoded();
    }

    @Test
    void reload() {
        ModelDefinition v1Def = sampleDef();
        ModelDefinition v2Def = new ModelDefinition(
                v1Def.schemaVersion(),
                v1Def.defKey(),
                v1Def.entityType(),
                v1Def.businessKey(),
                v1Def.objectTypes(),
                List.of(),
                v1Def.topics(),
                v1Def.vocabularies(),
                v1Def.modifications(),
                v1Def.validatorIds(),
                v1Def.baseViews(),
                v1Def.packRulePaths());

        DomainPack packV1 = new StubDomainPack("lease", "1.0.0", v1Def);
        DomainPack packV2 = new StubDomainPack("lease", "2.0.0", v2Def);

        assertEquals(DomainPackLoadResult.Status.ENABLED, loader.register(packV1).status());
        PublishedDefinition published = definitionService.publish(
                "lease.contract",
                id -> "lease.amount-traceable".equals(id),
                path -> false);
        assertEquals(DefStatus.PUBLISHED, published.status());
        String publishedHash = published.contentHash();
        int publishedVersion = published.version();

        assertEquals(DomainPackLoadResult.Status.ENABLED, loader.register(packV2).status());

        assertTrue(loader.get("lease", "1.0.0").isPresent());
        assertTrue(loader.get("lease", "2.0.0").isPresent());
        assertEquals(
                v1Def.statementTypes().size(),
                loader.get("lease", "1.0.0").orElseThrow().definition().statementTypes().size());
        assertEquals(0, loader.get("lease", "2.0.0").orElseThrow().definition().statementTypes().size());
        assertNotEquals(
                loader.get("lease", "1.0.0").orElseThrow().definition().statementTypes(),
                loader.get("lease", "2.0.0").orElseThrow().definition().statementTypes());

        PublishedDefinition stillPinned = definitionService
                .getVersion("lease.contract", publishedVersion)
                .orElseThrow();
        assertEquals(publishedHash, stillPinned.contentHash());
        assertEquals("1.0.0", stillPinned.packVersion());
    }

    private static ModelDefinition sampleDef() {
        AttrDef amount = AttrDef.leaf("amount", PathValueKind.AMOUNT, "CNY", null, List.of("rent"));
        AttrDef segments = new AttrDef(
                "segments", PathValueKind.ARRAY, null, null, List.of(), List.of(amount));
        AttrGroup sched = new AttrGroup("SCHED", List.of(segments));
        AttrGroup calc = new AttrGroup("CALC", List.of(
                AttrDef.leaf("baseAmount", PathValueKind.AMOUNT, "CNY", null, List.of("rent"))));
        ObjectType feeItem = new ObjectType(
                "feeItem",
                "feeItems",
                List.of("feeCode", "subject"),
                List.of(sched, calc),
                List.of(),
                List.of());
        StatementType obligation = new StatementType(
                "OBLIGATION",
                List.of(new StatementParam("action", PathValueKind.ENUM, "actions", List.of("repair"))));
        return new ModelDefinition(
                1,
                "lease.contract",
                "@contract.model",
                new BusinessKeyRule(List.of("storeCode", "contractNo"), "fileHash"),
                List.of(feeItem),
                List.of(obligation),
                List.of(),
                Map.of("actions", new Vocabulary("actions", List.of("REPAIR", "PAY"))),
                ModelDefinition.ModificationVocabulary.empty(),
                List.of("lease.amount-traceable"),
                List.of(),
                List.of());
    }

    private Path writePluginJar(String packKey, String version, List<String> permissions, String entryClass)
            throws Exception {
        String json = """
                {
                  "pluginKey": "%s",
                  "packKey": "%s",
                  "version": "%s",
                  "spiType": "DOMAIN_PACK",
                  "spiVersion": "1",
                  "permissions": %s,
                  "entryClass": "%s",
                  "signature": "test",
                  "definitions": ["defs/model.yaml"],
                  "schemas": [],
                  "prompts": "prompts/"
                }
                """.formatted(
                packKey,
                packKey,
                version,
                mapper.writeValueAsString(permissions),
                entryClass);
        Path jar = temp.resolve(packKey + "-" + version + ".jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            jos.putNextEntry(new JarEntry("tioka-plugin.json"));
            jos.write(json.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        return jar;
    }

    private static final class RecordingGateway implements PackPluginGateway {
        private final List<Path> enableCalls = new ArrayList<>();
        private final ConcurrentMap<String, Object> entries = new ConcurrentHashMap<>();

        @Override
        public PackLoadOutcome enable(Path jarPath) {
            enableCalls.add(jarPath);
            return PackLoadOutcome.failed("?", "?", "unexpected enable in this test");
        }

        @Override
        public Optional<Object> getEntry(String pluginKey, String version) {
            return Optional.ofNullable(entries.get(pluginKey + "@" + version));
        }
    }

    /** 端到端测试用：宿主端口放行。 */
    private static final class EnablingGateway implements PackPluginGateway {
        @Override
        public PackLoadOutcome enable(Path jarPath) {
            return PackLoadOutcome.enabled("lease-pack", "1.0.0");
        }

        @Override
        public Optional<Object> getEntry(String pluginKey, String version) {
            return Optional.empty();
        }
    }

    private record StubDomainPack(String packKey, String version, ModelDefinition definition) implements DomainPack {
        @Override
        public PromptBundle prompts() {
            return PromptBundle.empty();
        }

        @Override
        public List<ModelValidator> validators() {
            return List.of();
        }

        @Override
        public Optional<FoldingEngine> folding() {
            return Optional.empty();
        }

        @Override
        public List<ProjectionHook> projections() {
            return List.of();
        }

        @Override
        public List<CelFunction> celFunctions() {
            return List.of();
        }
    }
}

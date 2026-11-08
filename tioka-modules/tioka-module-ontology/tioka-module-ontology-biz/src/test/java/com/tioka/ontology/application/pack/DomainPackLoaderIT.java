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

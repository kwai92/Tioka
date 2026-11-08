package com.tioka.ontology.application.def;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.ontology.api.def.DefStatus;
import com.tioka.ontology.api.def.ModelDefinition;
import com.tioka.ontology.api.def.ModelDefinition.AttrDef;
import com.tioka.ontology.api.def.ModelDefinition.AttrGroup;
import com.tioka.ontology.api.def.ModelDefinition.BusinessKeyRule;
import com.tioka.ontology.api.def.ModelDefinition.FieldMapping;
import com.tioka.ontology.api.def.ModelDefinition.FieldView;
import com.tioka.ontology.api.def.ModelDefinition.ObjectType;
import com.tioka.ontology.api.def.ModelDefinition.StatementParam;
import com.tioka.ontology.api.def.ModelDefinition.StatementType;
import com.tioka.ontology.api.def.ModelDefinition.Vocabulary;
import com.tioka.ontology.api.def.PathValueKind;
import com.tioka.ontology.api.def.PublishedDefinition;
import com.tioka.ontology.application.schema.SchemaRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefinitionServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private SchemaRegistry schemaRegistry;
    private DefinitionService service;

    @BeforeEach
    void setUp() {
        schemaRegistry = new SchemaRegistry(mapper);
        service = new DefinitionService(mapper, schemaRegistry);
    }

    @Test
    void publishHappyPathBuildsCatalogAndSchemas() {
        ModelDefinition def = PathCatalogBuilderTest.sampleDef();
        service.saveDraft("lease", "1.0.0", def);

        PublishedDefinition published = service.publish(
                "lease.contract",
                id -> "lease.amount-traceable".equals(id),
                path -> false);

        assertEquals(1, published.version());
        assertEquals(DefStatus.PUBLISHED, published.status());
        assertFalse(published.pathCatalog().isEmpty());
        assertTrue(schemaRegistry.get("lease.contract.feeItem@1").isPresent());
        assertTrue(schemaRegistry.get("lease.contract.statement.OBLIGATION@1").isPresent());
    }

    @Test
    void rejectsMissingValidator() {
        service.saveDraft("lease", "1.0.0", PathCatalogBuilderTest.sampleDef());
        DefinitionPublishException ex = assertThrows(
                DefinitionPublishException.class,
                () -> service.publish("lease.contract", id -> false, path -> false));
        assertTrue(ex.issues().stream().anyMatch(i -> "VALIDATOR_MISSING".equals(i.code())));
    }

    @Test
    void rejectsUnclosedVocabulary() {
        ModelDefinition def = new ModelDefinition(
                1,
                "lease.contract",
                "@contract.model",
                new BusinessKeyRule(List.of("storeCode"), "fileHash"),
                List.of(),
                List.of(new StatementType(
                        "OBLIGATION",
                        List.of(new StatementParam("action", PathValueKind.ENUM, "missingVocab", List.of())))),
                List.of(),
                Map.of(),
                ModelDefinition.ModificationVocabulary.empty(),
                List.of(),
                List.of(),
                List.of());
        service.saveDraft("lease", "1.0.0", def);
        DefinitionPublishException ex = assertThrows(
                DefinitionPublishException.class,
                () -> service.publish("lease.contract", id -> true, path -> false));
        assertTrue(ex.issues().stream().anyMatch(i -> "VOCAB_REF_UNCLOSED".equals(i.code())));
    }

    @Test
    void rejectsFieldViewPathNotInCatalog() {
        ModelDefinition base = PathCatalogBuilderTest.sampleDef();
        ModelDefinition def = new ModelDefinition(
                base.schemaVersion(),
                base.defKey(),
                base.entityType(),
                base.businessKey(),
                base.objectTypes(),
                base.statementTypes(),
                base.topics(),
                base.vocabularies(),
                base.modifications(),
                base.validatorIds(),
                List.of(new FieldView("flat", List.of(
                        new FieldMapping("ghost", "m.feeItems[*].missing.path")))),
                List.of());
        service.saveDraft("lease", "1.0.0", def);
        DefinitionPublishException ex = assertThrows(
                DefinitionPublishException.class,
                () -> service.publish(
                        "lease.contract",
                        id -> "lease.amount-traceable".equals(id),
                        path -> false));
        assertTrue(ex.issues().stream().anyMatch(i -> "PATH_NOT_IN_CATALOG".equals(i.code())));
    }

    @Test
    void rejectsRemovedPathStillUsedByScenario() {
        ModelDefinition v1 = PathCatalogBuilderTest.sampleDef();
        service.saveDraft("lease", "1.0.0", v1);
        service.publish("lease.contract", id -> "lease.amount-traceable".equals(id), path -> false);

        // v2：去掉 amount 叶子
        AttrDef segments = new AttrDef(
                "segments", PathValueKind.ARRAY, null, null, List.of(),
                List.of(AttrDef.leaf("label", PathValueKind.TEXT)));
        ObjectType feeItem = new ObjectType(
                "feeItem", "feeItems", List.of("feeCode", "subject"),
                List.of(new AttrGroup("SCHED", List.of(segments)),
                        new AttrGroup("CALC", List.of(
                                AttrDef.leaf("baseAmount", PathValueKind.AMOUNT, "CNY", null, List.of("rent"))))),
                List.of(), List.of());
        ModelDefinition v2 = new ModelDefinition(
                1, "lease.contract", "@contract.model",
                new BusinessKeyRule(List.of("storeCode", "contractNo"), "fileHash"),
                List.of(feeItem),
                List.of(new StatementType("OBLIGATION", List.of(
                        new StatementParam("action", PathValueKind.ENUM, "actions", List.of("repair"))))),
                List.of(),
                Map.of("actions", new Vocabulary("actions", List.of("REPAIR", "PAY"))),
                ModelDefinition.ModificationVocabulary.empty(),
                List.of("lease.amount-traceable"),
                List.of(),
                List.of());
        service.saveDraft("lease", "1.0.1", v2);

        String removed = "m.feeItems[*].sched.segments[*].amount";
        DefinitionPublishException ex = assertThrows(
                DefinitionPublishException.class,
                () -> service.publish(
                        "lease.contract",
                        id -> "lease.amount-traceable".equals(id),
                        path -> removed.equals(path)));
        assertTrue(ex.issues().stream().anyMatch(i -> "PATH_REMOVED_IN_USE".equals(i.code())));
    }

    @Test
    void rollbackAndDiff() {
        ModelDefinition v1 = PathCatalogBuilderTest.sampleDef();
        service.saveDraft("lease", "1.0.0", v1);
        PublishedDefinition p1 = service.publish(
                "lease.contract", id -> "lease.amount-traceable".equals(id), path -> false);

        AttrGroup calc = new AttrGroup("CALC", List.of(
                AttrDef.leaf("baseAmount", PathValueKind.AMOUNT, "CNY", null, List.of("rent")),
                AttrDef.leaf("note", PathValueKind.TEXT)));
        ObjectType feeItem = new ObjectType(
                "feeItem", "feeItems", List.of("feeCode", "subject"),
                List.of(v1.objectTypes().get(0).groups().get(0), calc),
                List.of(), List.of());
        ModelDefinition v2Def = new ModelDefinition(
                1, "lease.contract", "@contract.model",
                v1.businessKey(),
                List.of(feeItem),
                v1.statementTypes(),
                v1.topics(),
                v1.vocabularies(),
                v1.modifications(),
                v1.validatorIds(),
                List.of(),
                List.of());
        service.saveDraft("lease", "1.1.0", v2Def);
        PublishedDefinition p2 = service.publish(
                "lease.contract", id -> "lease.amount-traceable".equals(id), path -> false);

        assertEquals(2, p2.version());
        DefinitionService.PathDiff pathDiff = service.pathDiff("lease.contract", 1, 2);
        assertTrue(pathDiff.added().contains("m.feeItems[*].calc.note"));

        List<Map<String, Object>> ops = service.diff("lease.contract", 1, 2);
        assertFalse(ops.isEmpty());

        PublishedDefinition rolled = service.rollback("lease.contract", p1.version());
        assertEquals(1, rolled.version());
        assertEquals(1, service.currentPublishedVersion("lease.contract").orElseThrow().version());
        assertTrue(service.getDraft("lease.contract").isPresent());
    }

    @Test
    void packRulePathMustExist() {
        ModelDefinition base = PathCatalogBuilderTest.sampleDef();
        ModelDefinition def = new ModelDefinition(
                base.schemaVersion(), base.defKey(), base.entityType(), base.businessKey(),
                base.objectTypes(), base.statementTypes(), base.topics(), base.vocabularies(),
                base.modifications(), base.validatorIds(), base.baseViews(),
                List.of("m.not.exists"));
        service.saveDraft("lease", "1.0.0", def);
        DefinitionPublishException ex = assertThrows(
                DefinitionPublishException.class,
                () -> service.publish(
                        "lease.contract",
                        id -> "lease.amount-traceable".equals(id),
                        path -> false));
        assertTrue(ex.issues().stream().anyMatch(i -> "PATH_NOT_IN_CATALOG".equals(i.code())));
    }
}

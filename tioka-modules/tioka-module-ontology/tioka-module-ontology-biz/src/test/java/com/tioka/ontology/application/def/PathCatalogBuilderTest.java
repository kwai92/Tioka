package com.tioka.ontology.application.def;

import com.tioka.ontology.api.def.ModelDefinition;
import com.tioka.ontology.api.def.ModelDefinition.AttrDef;
import com.tioka.ontology.api.def.ModelDefinition.AttrGroup;
import com.tioka.ontology.api.def.ModelDefinition.BusinessKeyRule;
import com.tioka.ontology.api.def.ModelDefinition.ObjectType;
import com.tioka.ontology.api.def.ModelDefinition.StatementParam;
import com.tioka.ontology.api.def.ModelDefinition.StatementType;
import com.tioka.ontology.api.def.ModelDefinition.Vocabulary;
import com.tioka.ontology.api.def.PathCatalogEntry;
import com.tioka.ontology.api.def.PathValueKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathCatalogBuilderTest {

    @Test
    void buildsObjectAndStatementPaths() {
        ModelDefinition def = sampleDef();
        List<PathCatalogEntry> catalog = PathCatalogBuilder.build(def);
        Map<String, PathCatalogEntry> byPath = catalog.stream()
                .collect(Collectors.toMap(PathCatalogEntry::path, e -> e));

        assertTrue(byPath.containsKey("m.feeItems[*].sched.segments[*].amount"));
        PathCatalogEntry amount = byPath.get("m.feeItems[*].sched.segments[*].amount");
        assertEquals(PathValueKind.AMOUNT, amount.kind());
        assertEquals("CNY", amount.unit());

        assertTrue(byPath.containsKey("m.statements[OBLIGATION].action"));
        PathCatalogEntry action = byPath.get("m.statements[OBLIGATION].action");
        assertEquals(PathValueKind.ENUM, action.kind());
        assertEquals(List.of("REPAIR", "PAY"), action.enumValues());
    }

    @Test
    void catalogContainsNestedGroupLeavesOnly() {
        Set<String> paths = PathCatalogBuilder.build(sampleDef()).stream()
                .map(PathCatalogEntry::path)
                .collect(Collectors.toSet());
        assertTrue(paths.contains("m.feeItems[*].calc.baseAmount"));
        assertEquals(3, paths.size());
    }

    static ModelDefinition sampleDef() {
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
}

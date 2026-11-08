package com.tioka.ontology.application.def;

import com.tioka.ontology.api.def.ModelDefinition;
import com.tioka.ontology.api.def.ModelDefinition.FieldMapping;
import com.tioka.ontology.api.def.ModelDefinition.FieldView;
import com.tioka.ontology.api.def.ModelDefinition.StatementParam;
import com.tioka.ontology.api.def.ModelDefinition.StatementType;
import com.tioka.ontology.api.def.PathCatalogEntry;
import com.tioka.ontology.api.def.PathValueKind;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * M3-03 §6 发布校验：
 * <ol>
 *   <li>领域包声明的 validatorIds 都有实现</li>
 *   <li>词表引用闭合</li>
 *   <li>字段视图、领域包自带规则引用的路径都在目录里</li>
 *   <li>与上一版 diff 删除的路径未被已发布场景引用</li>
 * </ol>
 */
public final class DefinitionPublishValidator {

    private DefinitionPublishValidator() {
    }

    public static List<DefinitionPublishIssue> validate(
            ModelDefinition def,
            List<PathCatalogEntry> catalog,
            Predicate<String> validatorExists,
            Set<String> previousPaths,
            Predicate<String> scenarioReferencesPath) {
        List<DefinitionPublishIssue> issues = new ArrayList<>();

        for (String validatorId : def.validatorIds()) {
            if (!validatorExists.test(validatorId)) {
                issues.add(new DefinitionPublishIssue(
                        "VALIDATOR_MISSING",
                        validatorId,
                        "validator not registered: " + validatorId));
            }
        }

        for (StatementType st : def.statementTypes()) {
            for (StatementParam param : st.params()) {
                if (param.kind() != PathValueKind.ENUM) {
                    continue;
                }
                String vocab = param.enumVocabulary();
                if (vocab == null || vocab.isBlank() || !def.vocabularies().containsKey(vocab)) {
                    issues.add(new DefinitionPublishIssue(
                            "VOCAB_REF_UNCLOSED",
                            "statements[" + st.typeKey() + "]." + param.name(),
                            "enum vocabulary missing: " + vocab));
                }
            }
        }

        Set<String> catalogPaths = catalog.stream()
                .map(PathCatalogEntry::path)
                .collect(Collectors.toCollection(HashSet::new));

        for (FieldView view : def.baseViews()) {
            for (FieldMapping mapping : view.fields()) {
                if (!catalogPaths.contains(mapping.path())) {
                    issues.add(new DefinitionPublishIssue(
                            "PATH_NOT_IN_CATALOG",
                            mapping.path(),
                            "field view path not in catalog: " + mapping.path()));
                }
            }
        }
        for (String rulePath : def.packRulePaths()) {
            if (!catalogPaths.contains(rulePath)) {
                issues.add(new DefinitionPublishIssue(
                        "PATH_NOT_IN_CATALOG",
                        rulePath,
                        "pack rule path not in catalog: " + rulePath));
            }
        }

        if (previousPaths != null && !previousPaths.isEmpty()) {
            for (String removed : previousPaths) {
                if (catalogPaths.contains(removed)) {
                    continue;
                }
                if (scenarioReferencesPath.test(removed)) {
                    issues.add(new DefinitionPublishIssue(
                            "PATH_REMOVED_IN_USE",
                            removed,
                            "removed path still referenced by published scenario: " + removed));
                }
            }
        }
        return issues;
    }
}

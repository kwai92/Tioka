package com.tioka.governance.api.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约冒烟：ConfigVersionApi 方法面与 ADR-041 §2 / M3-00 GOV-12 对齐。
 */
class ConfigVersionApiContractTest {

    @Test
    void exposesLifecycleOperations() {
        Set<String> names = Arrays.stream(ConfigVersionApi.class.getMethods())
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertTrue(names.containsAll(Set.of(
                "saveDraft",
                "validate",
                "analyzeImpact",
                "diff",
                "publish",
                "rollback",
                "exportPublished",
                "importAsDrafts"
        )), () -> "missing ops in " + names);
    }
}

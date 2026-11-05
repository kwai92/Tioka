package com.tioka.framework.arch;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * W0-09：规则定义驻留 framework；完整扫描在 tioka-server 集成测试执行。
 */
class ModuleDependencyRulesTest {

    @Test
    void runtimeMustNotDependOnConnectorRuleDefined() {
        ArchRule runtimeNoConnector = noClasses()
                .that().resideInAPackage("com.tioka.runtime..")
                .and().resideOutsideOfPackage("com.tioka.runtime.api..")
                .should().dependOnClassesThat().resideInAPackage("com.tioka.connector..")
                .because("runtime-biz 禁止直接依赖 connector")
                .allowEmptyShould(true);
        assertNotNull(runtimeNoConnector);
    }
}

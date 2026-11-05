package com.tioka.server.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * W0-09：禁止 runtime-biz 直接依赖 connector；biz 应用层互依扫描起步。
 */
class ModuleDependencyArchTest {

    static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.tioka");
    }

    @Test
    void runtimeBizMustNotDependOnConnector() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.tioka.runtime..")
                .and().resideOutsideOfPackage("com.tioka.runtime.api..")
                .should().dependOnClassesThat().resideInAPackage("com.tioka.connector..")
                .because("runtime-biz 禁止直接依赖 connector");
        rule.check(classes);
    }

    @Test
    void applicationServicesShouldNotDependOnForeignApplicationPackages() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.tioka.runtime.application..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.tioka.connector.application..",
                        "com.tioka.chat.application..",
                        "com.tioka.flow.application.."
                )
                .allowEmptyShould(true);
        rule.check(classes);
    }
}

package com.tioka.server.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ADR-014 D5：仅白名单组件可调用 SecretStore.get。
 */
class SecretStoreArchTest {
    static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.tioka");
    }

    @Test
    void onlyWhitelistedPackagesMayCallSecretStoreGet() {
        noClasses()
                .that().resideInAPackage("com.tioka..")
                .and().resideOutsideOfPackage("com.tioka.model..")
                .and().resideOutsideOfPackage("com.tioka.mcp..")
                .and().resideOutsideOfPackage("com.tioka.infra..")
                .and().resideOutsideOfPackage("com.tioka.system.infrastructure.spi..")
                .and().resideOutsideOfPackage("com.tioka.system.infrastructure..")
                .and().resideOutsideOfPackage("com.tioka.runtime.infrastructure..")
                .and().resideOutsideOfPackage("com.tioka.knowledge.infrastructure.embedding..")
                .and().resideOutsideOfPackage("com.tioka.flow.infrastructure..")
                .should().callMethod(com.tioka.system.api.spi.SecretStore.class, "get", String.class, String.class)
                .because("SecretStore.get 仅允许 LlmClient/McpTransport/ObjectStorage/EmbeddingClient/SecretStore 实现调用（ADR-014 D5）")
                .allowEmptyShould(true)
                .check(classes);
    }
}

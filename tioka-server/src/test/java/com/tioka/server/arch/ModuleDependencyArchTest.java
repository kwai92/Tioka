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

    @Test
    void flowAndChatMustNotBypassToolGatewayViaMcpToolCallApi() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("com.tioka.flow..", "com.tioka.chat..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("com.tioka.mcp.api.McpToolCallApi")
                .because("Flow/Chat 工具调用须经 ToolGateway，禁止直调 McpToolCallApi");
        rule.check(classes);
    }

    @Test
    void businessMustNotCreateRawHttpClient() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("com.tioka.system.application.egress..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("java.net.http.HttpClient")
                .because("出网须经 EgressClient；仅 DefaultEgressClient 可持有 HttpClient");
        rule.check(classes);
    }

    @Test
    void chatFlowAgentMustNotDependOnDeprecatedOrchApi() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage("com.tioka.chat..", "com.tioka.flow..", "com.tioka.agent..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("com.tioka.orchestration.api.OrchApi")
                .because("Chat/Flow/Agent 人审面走 OrchAttentionApi；ingest 走 engine.OrchApi");
        rule.check(classes);
    }
}

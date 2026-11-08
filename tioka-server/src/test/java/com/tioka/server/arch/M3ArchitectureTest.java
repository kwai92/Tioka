package com.tioka.server.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * M3 静态闸门（M3-00 第 8.4 节）。现存违例进冻结库；各包只能从冻结库删除自己的条目。
 */
@AnalyzeClasses(packages = "com.tioka", importOptions = ImportOption.DoNotIncludeTests.class)
class M3ArchitectureTest {

    /**
     * 执行侧只经只读投影读配置，不直接读 cfg_* 持久化类型（ADR-041 第 2 节）。
     * 允许：governance 配置子域、server。
     */
    @ArchTest
    static final ArchRule executionReadsConfigViaProjection = FreezingArchRule.freeze(
            noClasses().that().resideInAnyPackage(
                            "com.tioka.runtime.application..",
                            "com.tioka.runtime.kernel..",
                            "com.tioka.model.application..",
                            "com.tioka.agent.application..",
                            "com.tioka.flow.application..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.tioka.governance.infrastructure.persistence..",
                            "com.tioka.governance.infrastructure.mapper..")
                    .allowEmptyShould(true)
                    .because("执行侧只经只读投影读配置（M3-00 8.4 executionReadsConfigViaProjection）"));

    /**
     * 版本内容不得直接依赖凭证密文类型（ADR-042 第 1.6 节骨架规则）。
     * 完整序列化拒绝在 GOV-12 / MH-12 实现；此处禁止执行模块引用 LocalSecretStore。
     */
    @ArchTest
    static final ArchRule secretsNeverInConfigVersion = FreezingArchRule.freeze(
            noClasses().that().resideInAnyPackage("com.tioka.governance.application.config..")
                    .should().dependOnClassesThat().haveSimpleNameContaining("SecretStore")
                    .allowEmptyShould(true)
                    .because("版本内容不得纳入凭证密文（M3-00 8.4 secretsNeverInConfigVersion）"));

    /** 只有治理模块可以依赖 cedar-java；其他模块经 PolicyApi（M3-05）。 */
    @ArchTest
    static final ArchRule cedarOnlyInGovernance = FreezingArchRule.freeze(
            noClasses().that().resideOutsideOfPackages(
                            "com.tioka.governance..",
                            "com.tioka.server..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.cedarpolicy..",
                            "com.cedar..")
                    .allowEmptyShould(true)
                    .because("只有治理模块可依赖 cedar-java（M3-00 8.4 cedarOnlyInGovernance）"));

    /** 只有插件宿主可以创建类加载器加载外部 jar（M3-06 / FND-12）。 */
    @ArchTest
    static final ArchRule pluginsOnlyViaHost = FreezingArchRule.freeze(
            noClasses().that().resideOutsideOfPackages(
                            "com.tioka.infra.application.plugin..",
                            "com.tioka.infra.infrastructure.plugin..",
                            "com.tioka.server..")
                    .should().callConstructor(java.net.URLClassLoader.class, java.net.URL[].class)
                    .allowEmptyShould(true)
                    .because("只有插件宿主可加载外部 jar（M3-00 8.4 pluginsOnlyViaHost）"));

    /** 除 connector-biz 外不得依赖数据集 JDBC 实现包（M3-02）。 */
    @ArchTest
    static final ArchRule onlyConnectorTouchesJdbcDatasource = FreezingArchRule.freeze(
            noClasses().that().resideOutsideOfPackage("com.tioka.connector..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.tioka.connector.application.dataset.jdbc..",
                            "com.tioka.connector.infrastructure.dataset.jdbc..")
                    .allowEmptyShould(true)
                    .because("除 connector 外不得依赖数据集 JDBC 实现（M3-00 8.4 onlyConnectorTouchesJdbcDatasource）"));

    /** ontology.domain 不依赖 Spring（M3-03）。 */
    @ArchTest
    static final ArchRule ontologyDomainNoSpring = FreezingArchRule.freeze(
            noClasses().that().resideInAPackage("com.tioka.ontology.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                    .allowEmptyShould(true)
                    .because("ontology.domain 不依赖 Spring（M3-00 8.4 ontologyDomainNoSpring）"));

    /** 不再引用旧引擎包（ADR-043 第 13 节）；FLW-09 合入后冻结库应为空。 */
    @ArchTest
    static final ArchRule flowEngineNoLegacyState = FreezingArchRule.freeze(
            noClasses().that().resideInAnyPackage("com.tioka.flow..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.tioka.flow.engine.legacy..",
                            "com.tioka.flow.application.engine.legacy..")
                    .allowEmptyShould(true)
                    .because("不再引用旧 Flow 引擎包（M3-00 8.4 flowEngineNoLegacyState）"));

    /**
     * ToolCallContext 只经工厂构造（为 MAS-01 grantId 扩展预留；M3-00 8.4 toolCallsCarryGrant）。
     */
    @ArchTest
    static final ArchRule toolCallsCarryGrant = FreezingArchRule.freeze(
            noClasses().that().resideOutsideOfPackages(
                            "com.tioka.runtime.api.spi..",
                            "com.tioka.server..")
                    .should().callConstructor(
                            com.tioka.runtime.api.spi.ToolGateway.ToolCallContext.class,
                            com.tioka.framework.security.Principal.class,
                            String.class, String.class, String.class, String.class,
                            java.util.List.class, java.util.Map.class)
                    .allowEmptyShould(true)
                    .because("ToolCallContext 须经工厂构造，后续强制带授予 ID（M3-00 8.4 toolCallsCarryGrant）"));
}

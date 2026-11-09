package com.tioka.server.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-L8：全局「biz 仅依赖他模块 api」分层守护 + skill/eval 边界守护。
 *
 * <p>ADR-030：15 个域模块拆 *-api / *-biz，biz 间依赖只允许指向他模块 api。
 * 此前仅靠 Maven 编译期约束（各 *-biz 的 pom 不声明他模块 *-biz 依赖），
 * 本类把该约束提升为 ArchUnit 全局规则，防止依赖绕过 Maven 直连字节码
 * （例如经 provided/test 坐标或未来的 fat-jar 打包混入）。
 *
 * <p>规则语义：{@code com.tioka.<m>.(application|infrastructure|domain|kernel)..}
 * 的类不得依赖其他模块的同层包；依赖 {@code *.api..}、{@code com.tioka.framework..}
 * 与本模块自身合法。server / 测试代码不在守护范围（server 由
 * {@code TiokaArchitectureTest#serverDependsOnlyOnApi} 单独守护）。
 */
class LayeredDependencyArchTest {

    /** tioka-modules/pom.xml 的 15 个域模块根包（与 P1L8ArchSuiteIntegrityTest 基线一致）。 */
    private static final List<String> MODULES = List.of(
            "system", "infra", "runtime", "orchestration", "chat", "knowledge", "flow",
            "connector", "model", "agent", "mcp", "skill", "ontology", "governance", "eval");

    /** biz 侧分层包（部分模块没有全部四层，allowEmptyShould 兜底）。 */
    private static final List<String> LAYERS = List.of(
            "application", "infrastructure", "domain", "kernel");

    static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.tioka");
    }

    @Test
    void bizLayersMustNotDependOnForeignModuleLayers() {
        List<String> failures = new ArrayList<>();
        for (String module : MODULES) {
            String[] ownLayers = LAYERS.stream()
                    .map(layer -> "com.tioka." + module + "." + layer + "..")
                    .toArray(String[]::new);
            String[] foreignLayers = MODULES.stream()
                    .filter(m -> !m.equals(module))
                    .flatMap(m -> LAYERS.stream().map(layer -> "com.tioka." + m + "." + layer + ".."))
                    .toArray(String[]::new);
            ArchRule rule = noClasses()
                    .that().resideInAnyPackage(ownLayers)
                    .should().dependOnClassesThat().resideInAnyPackage(foreignLayers)
                    .allowEmptyShould(true)
                    .because("模块 " + module + " 的 biz 分层只能依赖他模块 api 与 framework（ADR-030）");
            try {
                rule.check(classes);
            } catch (AssertionError e) {
                failures.add(e.getMessage());
            }
        }
        assertTrue(failures.isEmpty(),
                () -> "存在跨模块 biz 直连违例（应只依赖他模块 api）：\n" + String.join("\n", failures));
    }

    /** P2-L8：skill 与 eval 是独立收口域，禁止互相直连（须经 system/api 编排）。 */
    @Test
    void skillAndEvalMustNotDependOnEachOther() {
        noClasses().that().resideInAPackage("com.tioka.skill..")
                .should().dependOnClassesThat().resideInAPackage("com.tioka.eval..")
                .allowEmptyShould(true)
                .because("skill 与 eval 互相独立，禁止直连（ADR-030 / P2-L8）")
                .check(classes);

        noClasses().that().resideInAPackage("com.tioka.eval..")
                .should().dependOnClassesThat().resideInAPackage("com.tioka.skill..")
                .allowEmptyShould(true)
                .because("eval 与 skill 互相独立，禁止直连（ADR-030 / P2-L8）")
                .check(classes);
    }

    /** P2-L8：域模块不得反向依赖装配层 server（依赖方向只能 server -> 模块）。 */
    @Test
    void skillAndEvalMustNotDependOnServer() {
        noClasses().that().resideInAnyPackage("com.tioka.skill..", "com.tioka.eval..")
                .should().dependOnClassesThat().resideInAPackage("com.tioka.server..")
                .allowEmptyShould(true)
                .because("域模块禁止反向依赖装配层 tioka-server（ADR-030 / P2-L8）")
                .check(classes);
    }
}

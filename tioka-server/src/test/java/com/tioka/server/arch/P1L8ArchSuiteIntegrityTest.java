package com.tioka.server.arch;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-L8：架构验收套件完整性守护（离线、纯文件断言，不启动 Spring，不重跑 ArchUnit 引擎）。
 *
 * <p>目的：把「ArchUnit 套件 + 契约测试 + 15 模块基线 + 模块依赖文档」锁在同一处，
 * 防止后续重构静默删除测试类或回退模块树。真正的架构规则仍由
 * {@code TiokaArchitectureTest} / {@code M2ArchitectureTest} / {@code M3ArchitectureTest} /
 * {@code ModuleDependencyArchTest} / {@code PersistenceArchTest} / {@code SecretStoreArchTest} 执行。
 */
class P1L8ArchSuiteIntegrityTest {

    private static final Pattern POM_MODULE =
            Pattern.compile("<module>\\s*([^<]+?)\\s*</module>");

    private static final List<String> ARCH_UNIT_SUITE = List.of(
            "TiokaArchitectureTest.java",
            "M2ArchitectureTest.java",
            "M3ArchitectureTest.java",
            "ModuleDependencyArchTest.java",
            "PersistenceArchTest.java",
            "SecretStoreArchTest.java");

    private static final List<String> CONTRACT_SUITE = List.of(
            "tioka-framework/src/test/java/com/tioka/framework/messaging/EnvelopeContractTest.java",
            "tioka-modules/tioka-module-runtime/tioka-module-runtime-api/src/test/java/com/tioka/runtime/api/kernel/RuntimeEventSchemaContractTest.java",
            "tioka-modules/tioka-module-runtime/tioka-module-runtime-api/src/test/java/com/tioka/runtime/api/kernel/TurnStatusContractTest.java",
            "tioka-server/src/test/java/com/tioka/server/messaging/MessagingIdempotencyKeyContractTest.java");

    private static Path repoRoot;

    @BeforeAll
    static void resolveRepoRoot() {
        repoRoot = findRepoRoot();
    }

    @Test
    void pomDeclaresFifteenDomainModules() throws IOException {
        Path pom = repoRoot.resolve("tioka-modules/pom.xml");
        assertTrue(Files.isRegularFile(pom), "缺少 tioka-modules/pom.xml：" + pom);

        Set<String> modules = new LinkedHashSet<>();
        Matcher matcher = POM_MODULE.matcher(Files.readString(pom, StandardCharsets.UTF_8));
        while (matcher.find()) {
            modules.add(matcher.group(1));
        }

        assertEquals(15, modules.size(), () -> "tioka-modules 模块数应为 15，实际 " + modules.size() + "：" + modules);
        assertTrue(modules.containsAll(List.of(
                        "tioka-module-ontology",
                        "tioka-module-governance",
                        "tioka-module-eval")),
                () -> "15 模块基线必须包含 ontology/governance/eval，实际：" + modules);
    }

    @Test
    void moduleDependenciesDocMatchesFifteen() throws IOException {
        Path doc = repoRoot.resolve("docs/architecture/module-dependencies.md");
        assertTrue(Files.isRegularFile(doc), "缺少模块依赖文档：" + doc);
        String text = Files.readString(doc, StandardCharsets.UTF_8);

        assertTrue(text.contains("tioka-module-ontology"),
                "模块依赖文档须回写 ontology（X8 锁）");
        assertTrue(text.contains("tioka-module-governance"),
                "模块依赖文档须回写 governance（X8 锁）");
        assertTrue(text.contains("tioka-module-eval"),
                "模块依赖文档须回写 eval（X8 锁）");
    }

    @Test
    void archunitSuitePresent() {
        Path archDir = repoRoot.resolve("tioka-server/src/test/java/com/tioka/server/arch");
        assertTrue(Files.isDirectory(archDir), "缺少 ArchUnit 测试目录：" + archDir);
        for (String name : ARCH_UNIT_SUITE) {
            Path file = archDir.resolve(name);
            assertTrue(Files.isRegularFile(file), "ArchUnit 套件缺少 " + name + "：" + file);
        }
    }

    @Test
    void contractSuitePresent() {
        for (String relative : CONTRACT_SUITE) {
            Path file = repoRoot.resolve(relative);
            assertTrue(Files.isRegularFile(file), "契约测试套件缺少 " + relative + "：" + file);
        }
    }

    /**
     * 从 {@code user.dir} 向上找到同时含 {@code tioka-modules} 与 {@code docs} 的仓库根，
     * 兼容 Maven 在子模块（tioka-server）目录执行测试的场景（同 EnvelopeContractTest 做法）。
     */
    private static Path findRepoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (Path cur = dir; cur != null; cur = cur.getParent()) {
            if (Files.isDirectory(cur.resolve("tioka-modules"))
                    && Files.isDirectory(cur.resolve("docs"))) {
                return cur;
            }
        }
        throw new IllegalStateException(
                "无法从 " + dir + " 向上找到仓库根（需同时含 tioka-modules 与 docs）");
    }
}

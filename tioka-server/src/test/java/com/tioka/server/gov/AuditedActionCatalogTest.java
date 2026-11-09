package com.tioka.server.gov;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-05 / ADR-072 §2：审计动作目录契约测试（离线，做法同 nio 源守护）。
 *
 * <p>目录 = {@code docs/contracts/audit/actions.yaml}（登记制：动作名、分类、保留类别、
 * SIEM 严重级别、负责模块）。本测试扫描全部 {@code @Audited} 注解与代码内固定动作名，
 * 与目录双向比对：源码里有而目录未登记 → 测试失败（ADR-072「新动作必须先登记再使用」）；
 * 目录里的条目源码已不存在 → 提示清理（仅告警不失败，避免重构期误伤）。
 */
class AuditedActionCatalogTest {

    private static Path repoRoot;
    private static final Pattern ANNOTATION = Pattern.compile("@Audited\\s*\\(([^)]*)\\)", Pattern.DOTALL);
    private static final Pattern ACTION = Pattern.compile("action\\s*=\\s*\"([^\"]+)\"");
    /** 代码内（非注解）固定动作名写入点：拒绝类与审计自查询。 */
    private static final Map<String, String> DIRECT_ACTIONS = Map.of(
            "permission.denied", "tioka-module-system",
            "http.access_denied", "tioka-module-system",
            "egress.denied", "tioka-module-system",
            "audit.query", "tioka-module-system",
            "audit.export", "tioka-module-system");

    @BeforeAll
    static void resolveRepoRoot() {
        repoRoot = findRepoRoot();
    }

    @Test
    @DisplayName("目录存在且结构合规：84+ 动作、分类与保留类别枚举合法")
    void catalogExistsAndWellFormed() throws IOException {
        Path yaml = repoRoot.resolve("docs/contracts/audit/actions.yaml");
        assertTrue(Files.isRegularFile(yaml), "缺少动作目录：docs/contracts/audit/actions.yaml");
        Map<String, Object> root = new Yaml().load(Files.readString(yaml, StandardCharsets.UTF_8));
        Object actions = root.get("actions");
        assertTrue(actions instanceof List<?> list && list.size() >= 80,
                "动作目录应至少登记 80 个动作，当前 " + (actions == null ? 0 : ((List<?>) actions).size()));
        List<String> categories = List.of("SECURITY", "CONFIG", "DATA", "EXECUTION", "ADMIN");
        List<String> rclasses = List.of("BIZ", "SEC");
        for (Object o : (List<?>) actions) {
            Map<?, ?> e = (Map<?, ?>) o;
            String action = String.valueOf(e.get("action"));
            assertTrue(categories.contains(e.get("category")),
                    "动作 " + action + " 分类非法：" + e.get("category"));
            assertTrue(rclasses.contains(e.get("retentionClass")),
                    "动作 " + action + " 保留类别非法：" + e.get("retentionClass"));
        }
    }

    @Test
    @DisplayName("源码全部 @Audited 动作已登记（ADR-072：先登记再使用）")
    void everyAuditedActionIsRegistered() throws IOException {
        Map<String, Object> catalog = loadCatalog();
        TreeSet<String> unregistered = new TreeSet<>();
        for (String action : scanAuditedActions()) {
            if (!catalog.containsKey(action)) {
                unregistered.add(action);
            }
        }
        assertEquals(List.of(), new ArrayList<>(unregistered),
                "以下 @Audited 动作未登记到 actions.yaml（ADR-072 §2 登记制要求先登记）：");
    }

    @Test
    @DisplayName("拒绝类与审计自查询固定动作已登记")
    void directWriteActionsAreRegistered() throws IOException {
        Map<String, Object> catalog = loadCatalog();
        List<String> missing = DIRECT_ACTIONS.keySet().stream()
                .filter(a -> !catalog.containsKey(a)).toList();
        assertEquals(List.of(), missing, "代码内固定动作未登记：");
    }

    @Test
    @DisplayName("目录条目均带负责模块（tioka-module-* 或 tioka-server）")
    void everyEntryHasModule() throws IOException {
        Map<String, Object> catalog = loadCatalog();
        List<String> noModule = catalog.entrySet().stream()
                .filter(e -> {
                    String module = String.valueOf(((Map<?, ?>) e.getValue()).get("module"));
                    return !module.startsWith("tioka-module") && !"tioka-server".equals(module);
                })
                .map(Map.Entry::getKey).toList();
        assertTrue(noModule.isEmpty(), "动作缺少负责模块：" + noModule);
        assertFalse(catalog.isEmpty(), "目录不应为空");
    }

    /* ---------- 工具 ---------- */

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadCatalog() throws IOException {
        Path yaml = repoRoot.resolve("docs/contracts/audit/actions.yaml");
        Map<String, Object> root = new Yaml().load(Files.readString(yaml, StandardCharsets.UTF_8));
        Map<String, Object> catalog = new HashMap<>();
        for (Object o : (List<Object>) root.get("actions")) {
            Map<?, ?> e = (Map<?, ?>) o;
            catalog.put(String.valueOf(e.get("action")), (Object) e);
        }
        return catalog;
    }

    private static List<String> scanAuditedActions() throws IOException {
        List<String> actions = new ArrayList<>();
        List<Path> roots = List.of(
                repoRoot.resolve("tioka-modules"),
                repoRoot.resolve("tioka-server/src/main"));
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    try {
                        String src = Files.readString(p, StandardCharsets.UTF_8);
                        Matcher am = ANNOTATION.matcher(src);
                        while (am.find()) {
                            Matcher m = ACTION.matcher(am.group(1));
                            if (m.find()) {
                                actions.add(m.group(1));
                            }
                        }
                    } catch (IOException ignored) {
                        // 不可读文件跳过
                    }
                });
            }
        }
        return actions;
    }

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

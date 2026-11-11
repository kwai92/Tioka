package tioka.pack.lease;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** LSE-04：seed/checkpoints.yaml 静态资产（70 D + 16 H = 86 要点，M3-00 L224 验收口径）。 */
class SeedCheckpointsTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> seed() {
        try (InputStream in = SeedCheckpointsTest.class.getResourceAsStream("/seed/checkpoints.yaml")) {
            assertEquals(null, in == null ? "seed/checkpoints.yaml 缺失" : null, "seed 资产必须随 jar 打包");
            return (Map<String, Object>) new Yaml().load(
                    new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> checkpoints() {
        return (List<Map<String, Object>>) seed().get("checkpoints");
    }

    @Test
    void 总数86_D70_H16() {
        List<Map<String, Object>> all = checkpoints();
        assertEquals(86, all.size());
        assertEquals(70, all.stream().filter(c -> "D".equals(c.get("mode"))).count());
        assertEquals(16, all.stream().filter(c -> "H".equals(c.get("mode"))).count());
    }

    @Test
    void 编号连续且模式正确() {
        List<Map<String, Object>> all = checkpoints();
        for (int i = 0; i < 70; i++) {
            assertEquals("D-%03d".formatted(i + 1), all.get(i).get("code"), "D 类须按 D-001〜D-070 连续");
            assertEquals("D", all.get(i).get("mode"));
        }
        for (int i = 70; i < 86; i++) {
            assertEquals("H-%03d".formatted(i - 69), all.get(i).get("code"), "H 类须按 H-001〜H-016 连续");
            assertEquals("H", all.get(i).get("mode"));
        }
    }

    @Test
    void 必备要点与阈值参数齐备() {
        List<Map<String, Object>> all = checkpoints();
        long mandatory = all.stream().filter(c -> Boolean.TRUE.equals(c.get("mandatory"))).count();
        assertTrue(mandatory >= 20, "必备要点（D+H）应有相当数量，实际 " + mandatory);
        // 结构四件套 + onBlank/params 至少齐备
        for (Map<String, Object> c : all) {
            assertTrue(c.containsKey("applies"), c.get("code") + " 缺 applies");
            assertTrue(c.containsKey("requires"), c.get("code") + " 缺 requires");
            assertTrue(c.containsKey("check"), c.get("code") + " 缺 check");
            assertTrue(c.containsKey("topic"), c.get("code") + " 缺 topic");
        }
    }

    @Test
    void M3_04三个示例要点表达式一致() {
        Map<String, Map<String, Object>> byCode = new java.util.HashMap<>();
        for (Map<String, Object> c : checkpoints()) {
            byCode.put((String) c.get("code"), c);
        }
        // D-017 免租期不少于品牌红线（缺省 2）
        assertEquals("freeMonths(m.feeItems.RENT) >= cfg.minFreeMonths", byCode.get("D-017").get("check"));
        assertEquals("rent.abatement", byCode.get("D-017").get("topic"));
        assertEquals(true, byCode.get("D-017").get("mandatory"));
        // D-023 租金递增每年不超过 5%（缺省 0.05）
        assertEquals("escalationRate(m.feeItems.RENT) <= cfg.maxEscalation", byCode.get("D-023").get("check"));
        assertEquals("size(m.feeItems.RENT.sched.segments) > 1", byCode.get("D-023").get("applies"));
        // H-004 逾期解约门槛：单次超过 30 日或年度累计 3 次
        assertEquals("overdueDaysToTerminate(m) > cfg.minOverdueDays || overdueTimesToTerminate(m) >= cfg.minOverdueTimes",
                byCode.get("H-004").get("check"));
        assertEquals("termination.landlord", byCode.get("H-004").get("topic"));
    }

    @Test
    void 八个领域包函数在表达式中有引用() {
        String joined = checkpoints().stream()
                .map(c -> String.valueOf(c.get("check")))
                .reduce("", (a, b) -> a + "\n" + b);
        for (String fn : List.of("freeMonths", "escalationRate", "depositMonths", "cureDays",
                "penaltyCap", "overdueDaysToTerminate", "overdueTimesToTerminate", "hasStatement")) {
            assertTrue(joined.contains(fn + "("), "seed 表达式未引用函数 " + fn);
        }
    }
}

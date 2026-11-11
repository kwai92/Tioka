package tioka.pack.lease.fold;

import tioka.pack.lease.LeasePackConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LSE-03 FoldingEngineGoldenTest：10 组样本租约的金标准状态（逐分段比对）+ 乱序 3 排列性质测试。
 * 场景覆盖五阶段与 R-1〜R-5、R-06/R-12/R-14/R-16 触发。
 */
class FoldingEngineGoldenTest {

    private static final String STORE = "lease:S001";

    private final LeaseFoldEngine engine = new LeaseFoldEngine(new LeasePackConfig());

    // ── 链构建 ───────────────────────────────────────────────

    private static Map<String, Object> fee(String feeKey, Object baseAmount) {
        return new java.util.LinkedHashMap<>(Map.of("feeKey", feeKey, "baseAmount", baseAmount));
    }

    private static Map<String, Object> contract(String instanceKey, String contractNo, String kind,
                                                String effectiveDate, String termStart, String termEnd) {
        Map<String, Object> c = new java.util.LinkedHashMap<>();
        c.put("instanceKey", instanceKey);
        c.put("contractNo", contractNo);
        c.put("kind", kind);
        c.put("signDate", "2025-12-20");
        c.put("effectiveDate", effectiveDate);
        if (termStart != null) {
            c.put("termStart", termStart);
        }
        if (termEnd != null) {
            c.put("termEnd", termEnd);
        }
        c.put("subjects", List.of(Map.of("code", "S1")));
        c.put("feeItems", new ArrayList<Map<String, Object>>());
        c.put("deposits", new ArrayList<Map<String, Object>>());
        c.put("modifications", new ArrayList<Map<String, Object>>());
        return c;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> feeListOf(Map<String, Object> contract) {
        return (List<Map<String, Object>>) contract.get("feeItems");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> depListOf(Map<String, Object> contract) {
        return (List<Map<String, Object>>) contract.get("deposits");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> modListOf(Map<String, Object> contract) {
        return (List<Map<String, Object>>) contract.get("modifications");
    }

    /** 断言某费用键的分段（feeKey,start,end,amount,freeMonths,contractNo,renewed）。 */
    private static void assertSegment(Map<String, Object> state, int index, String feeKey,
                                      String start, String end, Double amount, Double freeMonths,
                                      String contractNo, boolean renewed) {
        List<Map<String, Object>> segments = seg(state);
        assertTrue(index < segments.size(), () -> "分段不足: " + segments);
        Map<String, Object> s = segments.get(index);
        assertEquals(feeKey, s.get("feeKey"), () -> "segments=" + segments);
        assertEquals(start, s.get("start"), () -> "segments=" + segments);
        assertEquals(end, s.get("end"), () -> "segments=" + segments);
        assertEquals(amount, s.get("amount") == null ? null : ((Number) s.get("amount")).doubleValue(),
                () -> "segments=" + segments);
        assertEquals(freeMonths, s.get("freeMonths") == null ? null : ((Number) s.get("freeMonths")).doubleValue(),
                () -> "segments=" + segments);
        assertEquals(contractNo, s.get("contractNo"), () -> "segments=" + segments);
        assertEquals(renewed, s.get("renewed"), () -> "segments=" + segments);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> seg(Map<String, Object> state) {
        return (List<Map<String, Object>>) state.get("segments");
    }

    @SuppressWarnings("unchecked")
    private static List<String> reviewRules(Map<String, Object> state) {
        return ((List<Map<String, Object>>) state.get("reviewItems")).stream()
                .map(r -> (String) r.get("rule")).sorted().toList();
    }

    // ── 10 组金标准 ──────────────────────────────────────────

    @Test
    void G1_单份主合同() {
        Map<String, Object> c = contract("lease:S001:HT-001", "HT-001", "主合同",
                "2026-01-01", "2026-01-01", "2028-12-31");
        feeListOf(c).add(fee("RENT@S1", 10000));
        depListOf(c).add(Map.of("key", "保证金@S1", "depositType", "履约保证金", "amount", 30000));

        Map<String, Object> state = engine.fold(STORE, List.of(c));

        assertEquals(1, state.get("contractCount"));
        assertEquals(1, seg(state).size());
        assertSegment(state, 0, "RENT@S1", "2026-01-01", "2028-12-31", 10000.0, null, "HT-001", false);
        assertEquals(List.of(), reviewRules(state), "无复核项");
        assertEquals(List.of(), state.get("flags"));
    }

    @Test
    void G2_主合同加免租补充() {
        Map<String, Object> main = contract("lease:S001:HT-001", "HT-001", "主合同",
                "2026-01-01", "2026-01-01", "2028-12-31");
        feeListOf(main).add(fee("RENT@S1", 10000));
        Map<String, Object> supplement = contract("lease:S001:HT-002", "HT-002", "补充",
                "2026-03-01", null, null);
        Map<String, Object> abateFee = new java.util.LinkedHashMap<>();
        abateFee.put("feeKey", "RENT@S1");
        abateFee.put("abate", Map.of("freeMonths", 2, "start", "2026-04-01"));
        feeListOf(supplement).add(abateFee);

        Map<String, Object> state = engine.fold(STORE, List.of(main, supplement));

        assertEquals(2, state.get("contractCount"));
        assertEquals(1, seg(state).size());
        // 金额取自主合同；免租由补充在其范围内覆盖
        assertSegment(state, 0, "RENT@S1", "2026-01-01", "2028-12-31", 10000.0, 2.0, "HT-001", false);
        // 合同号推导：取值来源合同 HT-001 与当事方建立合同 HT-001，生效较晚为 HT-001
        assertEquals(List.of("R-3"), reviewRules(state), "专项协议在其费用范围内优先（R-3）");
    }

    @Test
    void G3_主合同加调价变更() {
        Map<String, Object> main = contract("lease:S001:HT-001", "HT-001", "主合同",
                "2026-01-01", "2026-01-01", "2028-12-31");
        feeListOf(main).add(fee("RENT@S1", 10000));
        Map<String, Object> change = contract("lease:S001:HT-002", "HT-002", "变更",
                "2027-01-01", null, null);
        Map<String, Object> adjust = fee("RENT@S1", 11000);
        adjust.put("effectiveDate", "2027-01-01");
        feeListOf(change).add(adjust);

        Map<String, Object> state = engine.fold(STORE, List.of(main, change));

        assertEquals(2, seg(state).size());
        assertSegment(state, 0, "RENT@S1", "2026-01-01", "2027-01-01", 10000.0, null, "HT-001", false);
        assertSegment(state, 1, "RENT@S1", "2027-01-01", "2028-12-31", 11000.0, null, "HT-002", false);
        assertEquals(List.of("R-3"), reviewRules(state));
    }

    @Test
    void G4_续约其他条款不变() {
        Map<String, Object> main = contract("lease:S001:HT-001", "HT-001", "主合同",
                "2026-01-01", "2026-01-01", "2028-12-31");
        feeListOf(main).add(fee("RENT@S1", 10000));
        Map<String, Object> renew = contract("lease:S001:HT-002", "HT-002", "续约",
                "2029-01-01", "2029-01-01", "2031-12-31");
        renew.put("renewInherit", "其他条款不变");
        Map<String, Object> renewFee = fee("RENT@S1", 11500);
        renewFee.put("renewal", true);
        feeListOf(renew).add(renewFee);

        Map<String, Object> state = engine.fold(STORE, List.of(main, renew));

        assertEquals(2, seg(state).size());
        assertSegment(state, 0, "RENT@S1", "2026-01-01", "2028-12-31", 10000.0, null, "HT-001", false);
        assertSegment(state, 1, "RENT@S1", "2029-01-01", "2031-12-31", 11500.0, null, "HT-002", true);
        assertEquals(List.of("R-06"), reviewRules(state), "N1 缺省进复核（续约「其他条款不变」递增是否继续）");
    }

    @Test
    void G5_主合同加终止() {
        Map<String, Object> main = contract("lease:S001:HT-001", "HT-001", "主合同",
                "2026-01-01", "2026-01-01", "2028-12-31");
        feeListOf(main).add(fee("RENT@S1", 10000));
        depListOf(main).add(Map.of("key", "保证金@S1", "depositType", "履约保证金", "amount", 30000));
        Map<String, Object> termination = contract("lease:S001:HT-002", "HT-002", "终止",
                "2027-06-30", null, "2027-06-30");

        Map<String, Object> state = engine.fold(STORE, List.of(main, termination));

        assertEquals(1, seg(state).size());
        assertSegment(state, 0, "RENT@S1", "2026-01-01", "2027-06-30", 10000.0, null, "HT-001", false);
        assertEquals(List.of("R-12"), reviewRules(state), "押金退还条款未见 → 结算待复核");
    }

    @Test
    void G6_主合同加转让() {
        Map<String, Object> main = contract("lease:S001:HT-001", "HT-001", "主合同",
                "2026-01-01", "2026-01-01", "2028-12-31");
        feeListOf(main).add(fee("RENT@S1", 10000));
        Map<String, Object> transfer = contract("lease:S001:HT-002", "HT-002", "转让",
                "2027-01-01", null, null);
        transfer.put("refs", List.of(Map.of("instanceKey", "lease:S001:HT-001", "version", 1)));
        modListOf(transfer).add(Map.of("type", "主体变更", "target", "甲方"));

        Map<String, Object> state = engine.fold(STORE, List.of(main, transfer));

        assertEquals(1, seg(state).size());
        // 合同号推导：当事方条目建立合同（HT-002 转让，生效较晚）晚于取值来源合同 HT-001 → 取 HT-002
        assertSegment(state, 0, "RENT@S1", "2026-01-01", "2028-12-31", 10000.0, null, "HT-002", false);
        assertEquals(List.of("R-14", "R-16"), reviewRules(state));
    }

    @Test
    void G7_临时调价到期恢复() {
        Map<String, Object> main = contract("lease:S001:HT-001", "HT-001", "主合同",
                "2026-01-01", "2026-01-01", "2028-12-31");
        feeListOf(main).add(fee("RENT@S1", 10000));
        Map<String, Object> temp = contract("lease:S001:HT-002", "HT-002", "补充",
                "2026-04-01", null, null);
        temp.put("temporary", true);
        temp.put("restoreDate", "2026-10-01");
        Map<String, Object> tempFee = fee("RENT@S1", 8000);
        tempFee.put("effectiveDate", "2026-04-01");
        feeListOf(temp).add(tempFee);

        Map<String, Object> state = engine.fold(STORE, List.of(main, temp));

        assertEquals(3, seg(state).size());
        assertSegment(state, 0, "RENT@S1", "2026-01-01", "2026-04-01", 10000.0, null, "HT-001", false);
        assertSegment(state, 1, "RENT@S1", "2026-04-01", "2026-10-01", 8000.0, null, "HT-002", false);
        assertSegment(state, 2, "RENT@S1", "2026-10-01", "2028-12-31", 10000.0, null, "HT-001", false);
        assertEquals(List.of("R-2", "R-3"), reviewRules(state));
    }

    @Test
    void G8_迁址重新建档() {
        Map<String, Object> main = contract("lease:S001:HT-001", "HT-001", "主合同",
                "2026-01-01", "2026-01-01", "2028-12-31");
        feeListOf(main).add(fee("RENT@S1", 10000));
        Map<String, Object> relocate = contract("lease:S001:HT-002", "HT-002", "变更",
                "2026-06-01", null, null);
        modListOf(relocate).add(Map.of("type", "迁址", "target", "S1"));

        Map<String, Object> state = engine.fold(STORE, List.of(main, relocate));

        assertEquals(1, seg(state).size());
        assertEquals(List.of("relocated"), state.get("flags"));
        assertEquals(List.of("R-1"), reviewRules(state), "迁址重新建档（R-1）");
    }

    @Test
    void G9_重叠减免() {
        Map<String, Object> main = contract("lease:S001:HT-001", "HT-001", "主合同",
                "2026-01-01", "2026-01-01", "2028-12-31");
        Map<String, Object> mainFee = fee("RENT@S1", 10000);
        mainFee.put("abate", Map.of("freeMonths", 2, "start", "2026-01-01", "end", "2026-03-01"));
        feeListOf(main).add(mainFee);
        Map<String, Object> supplement = contract("lease:S001:HT-002", "HT-002", "补充",
                "2026-02-01", null, null);
        Map<String, Object> abateFee = new java.util.LinkedHashMap<>();
        abateFee.put("feeKey", "RENT@S1");
        abateFee.put("abate", Map.of("freeMonths", 1, "start", "2026-02-15", "end", "2026-03-15"));
        feeListOf(supplement).add(abateFee);

        Map<String, Object> state = engine.fold(STORE, List.of(main, supplement));

        assertEquals(1, seg(state).size());
        // 择优：作用范围更窄（带期间）且生效日更晚的减免胜出 → 1 个月
        assertSegment(state, 0, "RENT@S1", "2026-01-01", "2028-12-31", 10000.0, 1.0, "HT-001", false);
        assertEquals(List.of("R-3", "R-4"), reviewRules(state), "R-4 重叠减免合并不叠加");
    }

    @Test
    void G10_附条件减免进复核() {
        Map<String, Object> main = contract("lease:S001:HT-001", "HT-001", "主合同",
                "2026-01-01", "2026-01-01", "2028-12-31");
        feeListOf(main).add(fee("RENT@S1", 10000));
        Map<String, Object> supplement = contract("lease:S001:HT-002", "HT-002", "补充",
                "2026-03-01", null, null);
        Map<String, Object> abateFee = new java.util.LinkedHashMap<>();
        abateFee.put("feeKey", "RENT@S1");
        abateFee.put("abate", Map.of("freeMonths", 2, "start", "2026-04-01",
                "condition", "开业后月营业额低于 30 万时生效"));
        feeListOf(supplement).add(abateFee);

        Map<String, Object> state = engine.fold(STORE, List.of(main, supplement));

        assertSegment(state, 0, "RENT@S1", "2026-01-01", "2028-12-31", 10000.0, 2.0, "HT-001", false);
        assertEquals(List.of("R-3", "R-5"), reviewRules(state), "R-5 附条件减免写 reviewItems，不自行决定");
    }

    // ── 乱序 3 排列性质测试（同输入任意顺序 stateMap 相同）────

    private static List<Map<String, Object>> threeContractChain() {
        Map<String, Object> main = contract("lease:S001:HT-001", "HT-001", "主合同",
                "2026-01-01", "2026-01-01", "2028-12-31");
        feeListOf(main).add(fee("RENT@S1", 10000));
        depListOf(main).add(Map.of("key", "保证金@S1", "depositType", "履约保证金", "amount", 30000));
        Map<String, Object> change = contract("lease:S001:HT-002", "HT-002", "变更",
                "2027-01-01", null, null);
        Map<String, Object> adjust = fee("RENT@S1", 11000);
        adjust.put("effectiveDate", "2027-01-01");
        feeListOf(change).add(adjust);
        Map<String, Object> renew = contract("lease:S001:HT-003", "HT-003", "续约",
                "2029-01-01", "2029-01-01", "2031-12-31");
        renew.put("renewInherit", "其他条款不变");
        Map<String, Object> renewFee = fee("RENT@S1", 11500);
        renewFee.put("renewal", true);
        feeListOf(renew).add(renewFee);
        return List.of(main, change, renew);
    }

    @Test
    void 乱序排列1结果相同() {
        List<Map<String, Object>> chain = threeContractChain();
        List<Map<String, Object>> perm = new ArrayList<>(chain);
        java.util.Collections.reverse(perm);
        Map<String, Object> s1 = engine.fold(STORE, chain);
        Map<String, Object> s2 = engine.fold(STORE, perm);
        assertEquals(s1, s2, "乱序输入折叠结果必须相同");
    }

    @Test
    void 乱序排列2结果相同() {
        List<Map<String, Object>> chain = threeContractChain();
        List<Map<String, Object>> perm = List.of(chain.get(2), chain.get(0), chain.get(1));
        Map<String, Object> s1 = engine.fold(STORE, chain);
        Map<String, Object> s2 = engine.fold(STORE, perm);
        assertEquals(s1, s2, "乱序输入折叠结果必须相同");
    }

    @Test
    void 乱序排列3结果相同且分段确定() {
        List<Map<String, Object>> chain = threeContractChain();
        List<Map<String, Object>> perm = List.of(chain.get(1), chain.get(2), chain.get(0));
        Map<String, Object> s1 = engine.fold(STORE, chain);
        Map<String, Object> s2 = engine.fold(STORE, perm);
        assertEquals(s1, s2);
        // 分段确定性：RENT@S1 三段（基准 / 调价 / 续约）
        assertEquals(3, seg(s1).size());
        assertSegment(s1, 0, "RENT@S1", "2026-01-01", "2027-01-01", 10000.0, null, "HT-001", false);
        assertSegment(s1, 1, "RENT@S1", "2027-01-01", "2028-12-31", 11000.0, null, "HT-002", false);
        assertSegment(s1, 2, "RENT@S1", "2029-01-01", "2031-12-31", 11500.0, null, "HT-003", true);
    }
}

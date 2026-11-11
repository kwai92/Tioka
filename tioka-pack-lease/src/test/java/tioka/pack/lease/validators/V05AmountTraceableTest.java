package tioka.pack.lease.validators;

import com.tioka.ontology.application.pack.Violation;
import tioka.pack.lease.LeasePackConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V05 金额溯源：≥4 例（含换算）。 */
class V05AmountTraceableTest {

    private final V05AmountTraceableValidator v = new V05AmountTraceableValidator(new LeasePackConfig());

    @Test
    void 通过_等值金额事实() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("calc", Map.of("baseAmount", DraftFixture.cell(5000, "KNOWN",
                DraftFixture.source("u1", "月租金为人民币 5000 元"))));
        assertTrue(v.validate(draft, null).isEmpty());
    }

    @Test
    void 通过_万元年换算到元月() {
        // 6 万元/年 → 50000/12 = 4166.67 元/月，容差 0.02 内
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("calc", Map.of("baseAmount", DraftFixture.cell(4166.67, "KNOWN",
                DraftFixture.source("u1", "年租金 5万元/年"))));
        assertTrue(v.validate(draft, null).isEmpty(), () -> "换算 60000/12 应与 4166.67 等值");
    }

    @Test
    void 拒收_引文金额不等值() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("calc", Map.of("baseAmount", DraftFixture.cell(6000, "KNOWN",
                DraftFixture.source("u1", "月租金为人民币 5000 元"))));
        List<Violation> out = v.validate(draft, null);
        assertEquals(1, out.size());
        assertEquals(Violation.Severity.REJECT, out.get(0).severity());
        assertTrue(out.get(0).message().contains("金额溯源失败"));
    }

    @Test
    void 交互_中文大写等值_无来源拒收() {
        // 交互①：中文大写 壹万贰仟 → 12000
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("calc", Map.of("baseAmount", DraftFixture.cell(12000, "KNOWN",
                DraftFixture.source("u1", "月租金人民币壹万贰仟元整"))));
        assertTrue(v.validate(draft, null).isEmpty());
        // 交互②：无来源 → 拒收
        Map<String, Object> draft2 = DraftFixture.draft();
        Map<String, Object> item2 = DraftFixture.feeItem(draft2, "RENT@S1");
        item2.put("calc", Map.of("baseAmount", DraftFixture.cell(5000, "KNOWN")));
        List<Violation> out = v.validate(draft2, null);
        assertEquals(1, out.size());
        // 边界：空白单元不判
        Map<String, Object> draft3 = DraftFixture.draft();
        Map<String, Object> item3 = DraftFixture.feeItem(draft3, "RENT@S1");
        item3.put("calc", Map.of("baseAmount", DraftFixture.cell(null, "BLANK")));
        assertTrue(v.validate(draft3, null).isEmpty());
    }
}

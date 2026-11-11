package tioka.pack.lease.validators;

import com.tioka.ontology.application.pack.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V02 一次性金额不是月额：≥4 例。 */
class V02OneoffNotMonthlyTest {

    private final V02OneoffNotMonthlyValidator v = new V02OneoffNotMonthlyValidator();

    @Test
    void 通过_月额单位() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("calc", Map.of("baseAmount", DraftFixture.cell(5000, "KNOWN",
                DraftFixture.source("u1", "月租金 5000", 5000, "元/月"))));
        assertTrue(v.validate(draft, null).isEmpty());
    }

    @Test
    void 拒收_一次性总额单位() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("calc", Map.of("baseAmount", DraftFixture.cell(150000, "KNOWN",
                DraftFixture.source("u1", "一次性进场费 150000", 150000, "元"))));
        List<Violation> out = v.validate(draft, null);
        assertEquals(1, out.size());
        assertEquals(Violation.Severity.REJECT, out.get(0).severity());
        assertTrue(out.get(0).message().contains("一次性金额不是月额"));
    }

    @Test
    void 边界_无显式单位不判() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("calc", Map.of("baseAmount", DraftFixture.cell(5000, "KNOWN",
                DraftFixture.source("u1", "月租金 5000"))));
        assertTrue(v.validate(draft, null).isEmpty(), "无显式单位交给 V05 溯源判定");
    }

    @Test
    void 交互_万元年通过_元日拒收() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("calc", Map.of("baseAmount", DraftFixture.cell(5000, "KNOWN",
                DraftFixture.source("u1", "年租金 6 万", 60000, "万元/年"))));
        assertTrue(v.validate(draft, null).isEmpty(), "带期间后缀（年）的万元/年 通过");
        Map<String, Object> draft2 = DraftFixture.draft();
        Map<String, Object> item2 = DraftFixture.feeItem(draft2, "RENT@S1");
        item2.put("calc", Map.of("baseAmount", DraftFixture.cell(100, "KNOWN",
                DraftFixture.source("u1", "按日 100", 100, "元/日"))));
        assertTrue(v.validate(draft2, null).isEmpty(), "元/日 带期间后缀，同样通过（换算归 V05）");
    }
}

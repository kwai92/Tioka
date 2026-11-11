package tioka.pack.lease.validators;

import com.tioka.ontology.application.pack.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V03 提成比例不是税率：≥4 例。 */
class V03CommissionNotTaxTest {

    private final V03CommissionNotTaxValidator v = new V03CommissionNotTaxValidator();

    @Test
    void 通过_租金税率为真税率() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("tax", Map.of("rate", DraftFixture.cell(0.09, "KNOWN",
                DraftFixture.source("u1", "增值税税率 9%"))));
        assertTrue(v.validate(draft, null).isEmpty());
    }

    @Test
    void 拒收_提成费用税来自提成条款() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> item = DraftFixture.feeItem(draft, "COMMISSION@S1");
        item.put("tax", Map.of("rate", DraftFixture.cell(0.15, "KNOWN",
                DraftFixture.source("u1", "按销售额 15% 提成"))));
        List<Violation> out = v.validate(draft, null);
        assertEquals(1, out.size());
        assertEquals(Violation.Severity.REJECT, out.get(0).severity());
        assertTrue(out.get(0).message().contains("提成比例不是税率"));
    }

    @Test
    void 边界_无tax组不判() {
        Map<String, Object> draft = DraftFixture.draft();
        DraftFixture.feeItem(draft, "COMMISSION@S1");
        assertTrue(v.validate(draft, null).isEmpty());
    }

    @Test
    void 交互_非提成费用不判_仅提成码受检() {
        // RENT 费用即便引文含「提成」也不属 V03 口径（费种身份由 V08 兜底）
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("tax", Map.of("rate", DraftFixture.cell(0.15, "KNOWN",
                DraftFixture.source("u1", "按销售额 15% 提成"))));
        assertTrue(v.validate(draft, null).isEmpty());
    }
}

package tioka.pack.lease.validators;

import com.tioka.ontology.application.pack.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V12 续约 / 调价金额完整：≥4 例。 */
class V12RenewalAmountCompleteTest {

    private final V12RenewalAmountCompleteValidator v = new V12RenewalAmountCompleteValidator();

    @Test
    void 通过_续约费用金额明示() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("renewal", true);
        item.put("calc", Map.of("baseAmount", DraftFixture.cell(5500, "KNOWN")));
        assertTrue(v.validate(draft, null).isEmpty());
    }

    @Test
    void 拒收_续约费用金额缺失() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("renewal", true);
        List<Violation> out = v.validate(draft, null);
        assertEquals(1, out.size());
        assertEquals(Violation.Severity.REJECT, out.get(0).severity());
        assertTrue(out.get(0).message().contains("续约/调价金额完整"));
    }

    @Test
    void 边界_非续约上下文不判() {
        Map<String, Object> draft = DraftFixture.draft();
        DraftFixture.feeItem(draft, "RENT@S1");
        assertTrue(v.validate(draft, null).isEmpty());
    }

    @Test
    void 交互_续约合同上下文_待复核状态也拒() {
        Map<String, Object> draft = DraftFixture.draft();
        draft.put("contracts", List.of(DraftFixture.contract("续约")));
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("calc", Map.of("baseAmount", DraftFixture.cell(5500, "REVIEW")));
        List<Violation> out = v.validate(draft, null);
        assertEquals(1, out.size(), "续约合同上下文：金额状态非明示（待复核）也拒收");
    }
}

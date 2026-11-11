package tioka.pack.lease.validators;

import com.tioka.ontology.application.pack.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V06 空白不是 0：≥4 例。 */
class V06BlankNotZeroTest {

    private final V06BlankNotZeroValidator v = new V06BlankNotZeroValidator();

    @Test
    void 通过_空白且值为空() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("calc", Map.of("baseAmount", DraftFixture.cell(null, "BLANK",
                DraftFixture.source("u1", "（留白）"))));
        assertTrue(v.validate(draft, null).isEmpty());
    }

    @Test
    void 拒收_空白但填0() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("pay", Map.of("payAmount", DraftFixture.cell(0, "BLANK")));
        List<Violation> out = v.validate(draft, null);
        assertEquals(1, out.size());
        assertEquals(Violation.Severity.REJECT, out.get(0).severity());
        assertTrue(out.get(0).message().contains("空白不是 0"));
    }

    @Test
    void 边界_明示0不判_非金额空白不判() {
        // 明示 0（KNOWN）不属于空白口径
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("pay", Map.of("payAmount", DraftFixture.cell(0, "KNOWN")));
        assertTrue(v.validate(draft, null).isEmpty());
        // 空白但值为非金额
        Map<String, Object> draft2 = DraftFixture.draft();
        Map<String, Object> item2 = DraftFixture.feeItem(draft2, "RENT@S1");
        item2.put("pay", Map.of("payDay", DraftFixture.cell("每月 5 日前", "BLANK")));
        assertTrue(v.validate(draft2, null).isEmpty());
    }

    @Test
    void 交互_押金与税率的空白0() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> d = DraftFixture.deposit(draft, "保证金@S1");
        d.put("amount", DraftFixture.cell(0, "BLANK"));
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("tax", Map.of("rate", DraftFixture.cell(0, "BLANK")));
        List<Violation> out = v.validate(draft, null);
        assertEquals(2, out.size(), "押金与税率两处空白 0 都拒收");
    }
}

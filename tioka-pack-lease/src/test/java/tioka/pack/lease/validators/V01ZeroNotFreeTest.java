package tioka.pack.lease.validators;

import com.tioka.ontology.application.pack.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V01 0 元不是免租：通过 / 拒收 / 边界 / 交互 ≥4 例。 */
class V01ZeroNotFreeTest {

    private final V01ZeroNotFreeValidator v = new V01ZeroNotFreeValidator();

    @Test
    void 通过_免租月数为正() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("abate", Map.of("freeMonths", DraftFixture.cell(2, "KNOWN")));
        assertTrue(v.validate(draft, null).isEmpty());
    }

    @Test
    void 拒收_免租月数为0() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("abate", Map.of("freeMonths", DraftFixture.cell(0, "KNOWN")));
        List<Violation> out = v.validate(draft, null);
        assertEquals(1, out.size());
        assertEquals(Violation.Severity.REJECT, out.get(0).severity());
        assertTrue(out.get(0).message().contains("0 元不是免租"));
    }

    @Test
    void 边界_无abate单元不判() {
        Map<String, Object> draft = DraftFixture.draft();
        DraftFixture.feeItem(draft, "RENT@S1");
        assertTrue(v.validate(draft, null).isEmpty());
    }

    @Test
    void 交互_减免金额0也拒_空白0交给V06() {
        // 交互①：abate 单元金额为 0 → V01 拒
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("abate", DraftFixture.cell(0, "KNOWN"));
        assertEquals(1, v.validate(draft, null).size());
        // 交互②：freeMonths 状态 BLANK 且值为 0 → V01 不判（空白不是 0 由 V06 兜底）
        Map<String, Object> draft2 = DraftFixture.draft();
        Map<String, Object> item2 = DraftFixture.feeItem(draft2, "RENT@S1");
        item2.put("abate", Map.of("freeMonths", DraftFixture.cell(0, "BLANK")));
        assertTrue(v.validate(draft2, null).isEmpty(), "BLANK 状态 0 值不属于 V01 口径");
    }
}

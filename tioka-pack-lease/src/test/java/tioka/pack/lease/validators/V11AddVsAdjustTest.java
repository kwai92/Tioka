package tioka.pack.lease.validators;

import com.tioka.ontology.application.pack.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V11 新增还是调整：≥4 例。 */
class V11AddVsAdjustTest {

    private final V11AddVsAdjustValidator v = new V11AddVsAdjustValidator();

    @Test
    void 通过_单条无重复() {
        Map<String, Object> draft = DraftFixture.draft();
        DraftFixture.feeItem(draft, "RENT@S1");
        assertTrue(v.validate(draft, null).isEmpty());
    }

    @Test
    void 拒收_同身份第二条声明为新增() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> first = DraftFixture.feeItem(draft, "RENT@S1");
        first.put("op", "ADD");
        Map<String, Object> second = DraftFixture.feeItem(draft, "RENT@S1#2");
        second.put("op", "ADD");
        List<Violation> out = v.validate(draft, null);
        assertEquals(1, out.size());
        assertEquals(Violation.Severity.REJECT, out.get(0).severity());
        assertTrue(out.get(0).message().contains("应为调整 ADJUST"));
    }

    @Test
    void 通过_同身份第二条为调整() {
        Map<String, Object> draft = DraftFixture.draft();
        DraftFixture.feeItem(draft, "RENT@S1");
        Map<String, Object> second = DraftFixture.feeItem(draft, "RENT@S1#2");
        second.put("op", "ADJUST");
        assertTrue(v.validate(draft, null).isEmpty());
    }

    @Test
    void 交互_未带op的第二条不判_不同标的同类费用不算重复() {
        // 未声明 op → 无法判定意图，不判（由覆盖检查兜底）
        Map<String, Object> draft = DraftFixture.draft();
        DraftFixture.feeItem(draft, "RENT@S1");
        DraftFixture.feeItem(draft, "RENT@S1#2");
        assertTrue(v.validate(draft, null).isEmpty());
        // 不同标的（RENT@S1 / RENT@S2）各自新增是合法的
        Map<String, Object> draft2 = DraftFixture.draft();
        DraftFixture.feeItem(draft2, "RENT@S1").put("op", "ADD");
        DraftFixture.feeItem(draft2, "RENT@S2").put("op", "ADD");
        assertTrue(v.validate(draft2, null).isEmpty());
    }
}

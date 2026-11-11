package tioka.pack.lease.validators;

import com.tioka.ontology.application.pack.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V07 一个金额事实只归一个费用项：≥4 例。 */
class V07OneFactOneItemTest {

    private final V07OneFactOneItemValidator v = new V07OneFactOneItemValidator();

    private Map<String, Object> rentWith(Map<String, Object> draft, String key, String unitId, Object amount) {
        Map<String, Object> item = DraftFixture.feeItem(draft, key);
        item.put("calc", Map.of("baseAmount", DraftFixture.cell(amount, "KNOWN",
                DraftFixture.source(unitId, "月租金 5000 元", amount, "元/月"))));
        return item;
    }

    @Test
    void 通过_事实各自归属() {
        Map<String, Object> draft = DraftFixture.draft();
        rentWith(draft, "RENT@S1", "u1", 5000);
        rentWith(draft, "MGMT@S1", "u2", 200);
        assertTrue(v.validate(draft, null).isEmpty());
    }

    @Test
    void 拒收_同一事实归两个费用项() {
        Map<String, Object> draft = DraftFixture.draft();
        rentWith(draft, "RENT@S1", "u1", 5000);
        rentWith(draft, "MGMT@S1", "u1", 5000);
        List<Violation> out = v.validate(draft, null);
        assertEquals(2, out.size(), "两个都退回");
        assertEquals(Violation.Severity.REJECT, out.get(0).severity());
    }

    @Test
    void 边界_同单元不同金额不算同一事实() {
        Map<String, Object> draft = DraftFixture.draft();
        rentWith(draft, "RENT@S1", "u1", 5000);
        rentWith(draft, "MGMT@S1", "u1", 200);
        assertTrue(v.validate(draft, null).isEmpty(), "同单元不同金额（如含两笔）不判重");
    }

    @Test
    void 交互_三个费用项共享事实各退一次() {
        Map<String, Object> draft = DraftFixture.draft();
        rentWith(draft, "RENT@S1", "u1", 5000);
        rentWith(draft, "MGMT@S1", "u1", 5000);
        rentWith(draft, "PROMO@S1", "u1", 5000);
        List<Violation> out = v.validate(draft, null);
        assertEquals(3, out.size(), "三个涉事费用项各退一次");
    }
}

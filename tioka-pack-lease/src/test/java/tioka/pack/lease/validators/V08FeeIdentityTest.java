package tioka.pack.lease.validators;

import com.tioka.ontology.application.pack.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V08 费种身份一致：≥4 例。 */
class V08FeeIdentityTest {

    private final V08FeeIdentityValidator v = new V08FeeIdentityValidator();

    @Test
    void 通过_引文与费种一致() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("calc", Map.of("baseAmount", DraftFixture.cell(5000, "KNOWN",
                DraftFixture.source("u1", "月租金 5000 元"))));
        assertTrue(v.validate(draft, null).isEmpty());
    }

    @Test
    void 拒收_租金项引文是物业费() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("calc", Map.of("baseAmount", DraftFixture.cell(200, "KNOWN",
                DraftFixture.source("u1", "物业管理费每月 200 元"))));
        List<Violation> out = v.validate(draft, null);
        assertEquals(1, out.size());
        assertEquals(Violation.Severity.REJECT, out.get(0).severity());
        assertTrue(out.get(0).message().contains("费种身份一致"));
    }

    @Test
    void 边界_无来源不判() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("calc", Map.of("baseAmount", DraftFixture.cell(5000, "KNOWN")));
        assertTrue(v.validate(draft, null).isEmpty());
    }

    @Test
    void 交互_多来源其一匹配即通过() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> item = DraftFixture.feeItem(draft, "MGMT@S1");
        item.put("calc", Map.of("baseAmount", DraftFixture.cell(200, "KNOWN",
                DraftFixture.source("u1", "见合同附件费用表"),
                DraftFixture.source("u2", "物业管理费每月 200 元"))));
        assertTrue(v.validate(draft, null).isEmpty());
    }
}

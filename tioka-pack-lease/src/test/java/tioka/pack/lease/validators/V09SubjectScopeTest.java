package tioka.pack.lease.validators;

import com.tioka.ontology.application.pack.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V09 标的归属：≥4 例。 */
class V09SubjectScopeTest {

    private final V09SubjectScopeValidator v = new V09SubjectScopeValidator();

    @Test
    void 通过_标的在清点内() {
        Map<String, Object> draft = DraftFixture.draft();
        DraftFixture.subject(draft, "S1");
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("calc", Map.of("baseAmount", DraftFixture.cell(5000, "KNOWN",
                DraftFixture.source("u1", "S1 铺位月租金 5000 元"))));
        assertTrue(v.validate(draft, null).isEmpty());
    }

    @Test
    void 拒收_缺标的() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("subject", null);
        List<Violation> out = v.validate(draft, null);
        assertEquals(1, out.size());
        assertTrue(out.get(0).message().contains("缺标的"));
    }

    @Test
    void 拒收_标的不在清点() {
        Map<String, Object> draft = DraftFixture.draft();
        DraftFixture.subject(draft, "S1");
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S2");
        item.put("subject", "S9");
        List<Violation> out = v.validate(draft, null);
        assertEquals(1, out.size());
        assertTrue(out.get(0).message().contains("不在本模型清点"));
    }

    @Test
    void 交互_跨标的引用拒收() {
        Map<String, Object> draft = DraftFixture.draft();
        DraftFixture.subject(draft, "S1");
        DraftFixture.subject(draft, "S2");
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("subject", "S1");
        item.put("calc", Map.of("baseAmount", DraftFixture.cell(5000, "KNOWN",
                DraftFixture.source("u1", "S2 铺与 S1 铺合计月租金 5000 元"))));
        List<Violation> out = v.validate(draft, null);
        assertEquals(1, out.size());
        assertEquals(Violation.Severity.REJECT, out.get(0).severity());
        assertTrue(out.get(0).message().contains("跨标的引用"));
    }
}

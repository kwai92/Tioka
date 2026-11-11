package tioka.pack.lease.validators;

import com.tioka.ontology.application.pack.Violation;
import tioka.pack.lease.LeasePackConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V16 覆盖检查：≥4 例（REVIEW，列出未归属单元）。 */
class V16CoverageValidatorTest {

    private final V16CoverageValidator v = new V16CoverageValidator(new LeasePackConfig());

    private static Map<String, Object> unit(String unitId, String text) {
        Map<String, Object> u = new java.util.LinkedHashMap<>();
        u.put("unitId", unitId);
        u.put("text", text);
        return u;
    }

    @Test
    void 通过_全部单元已归属() {
        Map<String, Object> draft = DraftFixture.draft();
        draft.put("documentUnits", List.of(unit("u1", "月租金 5000 元")));
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("calc", Map.of("baseAmount", DraftFixture.cell(5000, "KNOWN",
                DraftFixture.source("u1", "月租金 5000 元"))));
        assertTrue(v.validate(draft, null).isEmpty());
    }

    @Test
    void 复核_未归属单元被列出() {
        Map<String, Object> draft = DraftFixture.draft();
        draft.put("documentUnits", List.of(
                unit("u1", "月租金 5000 元"),
                unit("u2", "承租方应于每月 5 日前支付")));
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("calc", Map.of("baseAmount", DraftFixture.cell(5000, "KNOWN",
                DraftFixture.source("u1", "月租金 5000 元"))));
        List<Violation> out = v.validate(draft, null);
        assertEquals(1, out.size());
        assertEquals(Violation.Severity.REVIEW, out.get(0).severity());
        assertTrue(out.get(0).message().contains("u2"));
    }

    @Test
    void 边界_无证据单元索引不判_无关单元不判() {
        // 无 documentUnits → 不判
        Map<String, Object> draft = DraftFixture.draft();
        assertTrue(v.validate(draft, null).isEmpty());
        // 单元无金额/期限/义务措辞 → 不需要归属
        Map<String, Object> draft2 = DraftFixture.draft();
        draft2.put("documentUnits", List.of(unit("u0", "本合同一式两份")));
        assertTrue(v.validate(draft2, null).isEmpty());
    }

    @Test
    void 交互_明确排除视为已覆盖() {
        Map<String, Object> draft = DraftFixture.draft();
        draft.put("documentUnits", List.of(unit("u1", "月租金 5000 元"), unit("u2", "水电押金 2000 元")));
        draft.put("excludedUnits", List.of("u2"));
        Map<String, Object> item = DraftFixture.feeItem(draft, "RENT@S1");
        item.put("calc", Map.of("baseAmount", DraftFixture.cell(5000, "KNOWN",
                DraftFixture.source("u1", "月租金 5000 元"))));
        assertTrue(v.validate(draft, null).isEmpty());
    }
}

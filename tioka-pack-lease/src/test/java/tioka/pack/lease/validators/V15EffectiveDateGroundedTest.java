package tioka.pack.lease.validators;

import com.tioka.ontology.application.pack.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V15 生效日有据：≥4 例（REVIEW）。 */
class V15EffectiveDateGroundedTest {

    private final V15EffectiveDateGroundedValidator v = new V15EffectiveDateGroundedValidator();

    @Test
    void 通过_生效日有依据() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> mod = DraftFixture.modification("调价");
        mod.put("effectiveDate", DraftFixture.cell("2026-06-01", "KNOWN",
                DraftFixture.source("u1", "自 2026 年 6 月 1 日起调整")));
        draft.put("modifications", List.of(mod));
        assertTrue(v.validate(draft, null).isEmpty());
    }

    @Test
    void 复核_生效日标未知() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> mod = DraftFixture.modification("调价");
        mod.put("effectiveDate", DraftFixture.cell(null, "UNKNOWN"));
        draft.put("modifications", List.of(mod));
        List<Violation> out = v.validate(draft, null);
        assertEquals(1, out.size());
        assertEquals(Violation.Severity.REVIEW, out.get(0).severity());
        assertTrue(out.get(0).message().contains("暂不应用"));
    }

    @Test
    void 边界_无修改声明不判() {
        Map<String, Object> draft = DraftFixture.draft();
        assertTrue(v.validate(draft, null).isEmpty());
    }

    @Test
    void 交互_未带生效日单元也复核() {
        Map<String, Object> draft = DraftFixture.draft();
        draft.put("modifications", List.of(DraftFixture.modification("调价")));
        List<Violation> out = v.validate(draft, null);
        assertEquals(1, out.size());
        assertEquals(Violation.Severity.REVIEW, out.get(0).severity());
    }
}

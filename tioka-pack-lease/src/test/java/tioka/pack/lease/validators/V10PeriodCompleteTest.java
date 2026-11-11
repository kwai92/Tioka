package tioka.pack.lease.validators;

import com.tioka.ontology.application.pack.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V10 期间完整：≥4 例（唯一回填）。 */
class V10PeriodCompleteTest {

    private final V10PeriodCompleteValidator v = new V10PeriodCompleteValidator();

    @Test
    void 通过_起止齐全() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> s = DraftFixture.subject(draft, "S1");
        s.put("termStart", DraftFixture.cell("2026-01-01", "KNOWN"));
        s.put("termEnd", DraftFixture.cell("2028-12-31", "KNOWN"));
        assertTrue(v.validate(draft, null).isEmpty());
    }

    @Test
    void 拒收_单侧空白且无法回填() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> s = DraftFixture.subject(draft, "S1");
        s.put("termStart", DraftFixture.cell("2026-01-01", "KNOWN",
                DraftFixture.source("u1", "租期自 2026年01月01 日起")));
        s.put("termEnd", DraftFixture.cell(null, "BLANK"));
        List<Violation> out = v.validate(draft, null);
        assertEquals(1, out.size());
        assertEquals(Violation.Severity.REJECT, out.get(0).severity());
        assertTrue(out.get(0).message().contains("期间完整"));
    }

    @Test
    void 通过_唯一日期对回填() {
        // 另一侧来源引文含起止两个日期事实 → 可唯一回填，不拒收
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> s = DraftFixture.subject(draft, "S1");
        s.put("termStart", DraftFixture.cell("2026-01-01", "KNOWN",
                DraftFixture.source("u1", "租期自 2026年01月01日 起至 2028年12月31日 止")));
        s.put("termEnd", DraftFixture.cell(null, "BLANK"));
        assertTrue(v.validate(draft, null).isEmpty(), "唯一成对日期事实可回填");
    }

    @Test
    void 交互_多个日期事实回填不了拒收_两侧缺失不判() {
        // 交互①：引文含 3 个日期（含历史日期）→ 非唯一，拒收
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> s = DraftFixture.subject(draft, "S1");
        s.put("termStart", DraftFixture.cell("2026-01-01", "KNOWN",
                DraftFixture.source("u1", "2025年6月1日 签署，租期自 2026年01月01日 至 2028年12月31日")));
        s.put("termEnd", DraftFixture.cell(null, "BLANK"));
        List<Violation> out = v.validate(draft, null);
        assertEquals(1, out.size());
        // 边界：另一侧也空白 → 不在此判定
        Map<String, Object> draft2 = DraftFixture.draft();
        Map<String, Object> s2 = DraftFixture.subject(draft2, "S1");
        s2.put("termStart", DraftFixture.cell(null, "BLANK"));
        s2.put("termEnd", DraftFixture.cell(null, "BLANK"));
        assertTrue(v.validate(draft2, null).isEmpty());
    }
}

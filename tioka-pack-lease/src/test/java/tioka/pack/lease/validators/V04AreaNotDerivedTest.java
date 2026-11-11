package tioka.pack.lease.validators;

import com.tioka.ontology.application.pack.Violation;
import tioka.pack.lease.LeasePackConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V04 面积不从租金反推：≥4 例。 */
class V04AreaNotDerivedTest {

    private final V04AreaNotDerivedValidator v = new V04AreaNotDerivedValidator(new LeasePackConfig());

    @Test
    void 通过_事实角色且引文含面积单位() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> s = DraftFixture.subject(draft, "S1");
        Map<String, Object> area = DraftFixture.cell(120, "KNOWN", Map.of(
                "unitId", "u1", "quote", "建筑面积 120 ㎡", "role", "事实"));
        s.put("area", area);
        assertTrue(v.validate(draft, null).isEmpty());
    }

    @Test
    void 拒收_从租金反推() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> s = DraftFixture.subject(draft, "S1");
        s.put("area", DraftFixture.cell(133, "DERIVED",
                DraftFixture.source("u1", "月租金 8000 元，按 60 元/月 反推")));
        List<Violation> out = v.validate(draft, null);
        assertEquals(1, out.size());
        assertEquals(Violation.Severity.REJECT, out.get(0).severity());
        assertTrue(out.get(0).message().contains("面积不从租金反推"));
    }

    @Test
    void 拒收_有单位但角色非事实() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> s = DraftFixture.subject(draft, "S1");
        s.put("area", DraftFixture.cell(120, "DERIVED", Map.of(
                "unitId", "u1", "quote", "约 120 平方米", "role", "其他")));
        List<Violation> out = v.validate(draft, null);
        assertEquals(1, out.size());
    }

    @Test
    void 边界_无面积单元不判_交互() {
        Map<String, Object> draft = DraftFixture.draft();
        DraftFixture.subject(draft, "S1");
        assertTrue(v.validate(draft, null).isEmpty(), "无 area 单元不判");
        // 交互：多来源其一满足即通过
        Map<String, Object> draft2 = DraftFixture.draft();
        Map<String, Object> s2 = DraftFixture.subject(draft2, "S1");
        s2.put("area", DraftFixture.cell(120, "KNOWN",
                DraftFixture.source("u1", "租金条款"),
                Map.of("unitId", "u2", "quote", "计租面积 120㎡", "role", "事实")));
        assertTrue(v.validate(draft2, null).isEmpty());
    }
}

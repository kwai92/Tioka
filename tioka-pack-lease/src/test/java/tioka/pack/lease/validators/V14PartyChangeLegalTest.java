package tioka.pack.lease.validators;

import com.tioka.ontology.application.pack.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V14 主体变更合法：≥4 例。 */
class V14PartyChangeLegalTest {

    private final V14PartyChangeLegalValidator v = new V14PartyChangeLegalValidator();

    @Test
    void 通过_转让合同中的主体变更() {
        Map<String, Object> draft = DraftFixture.draft();
        draft.put("contracts", List.of(DraftFixture.contract("转让")));
        draft.put("modifications", List.of(DraftFixture.modification("主体变更")));
        assertTrue(v.validate(draft, null).isEmpty());
    }

    @Test
    void 拒收_补充协议中的主体变更() {
        Map<String, Object> draft = DraftFixture.draft();
        draft.put("contracts", List.of(DraftFixture.contract("补充")));
        draft.put("modifications", List.of(DraftFixture.modification("主体变更")));
        List<Violation> out = v.validate(draft, null);
        assertEquals(1, out.size());
        assertEquals(Violation.Severity.REJECT, out.get(0).severity());
        assertTrue(out.get(0).message().contains("主体变更合法"));
    }

    @Test
    void 边界_无主体变更不判() {
        Map<String, Object> draft = DraftFixture.draft();
        draft.put("contracts", List.of(DraftFixture.contract("补充")));
        draft.put("modifications", List.of(DraftFixture.modification("调价")));
        assertTrue(v.validate(draft, null).isEmpty());
    }

    @Test
    void 交互_回购合法_当事方changed标记同口径() {
        // 回购合同合法
        Map<String, Object> draft = DraftFixture.draft();
        draft.put("contracts", List.of(DraftFixture.contract("回购")));
        draft.put("modifications", List.of(DraftFixture.modification("主体变更")));
        assertTrue(v.validate(draft, null).isEmpty());
        // parties.changed 标记同样受 V14 约束
        Map<String, Object> draft2 = DraftFixture.draft();
        draft2.put("contracts", List.of(DraftFixture.contract("主合同")));
        Map<String, Object> party = new java.util.LinkedHashMap<>();
        party.put("name", "新出租方");
        party.put("role", "甲方");
        party.put("changed", true);
        draft2.put("parties", List.of(party));
        List<Violation> out = v.validate(draft2, null);
        assertEquals(1, out.size());
    }
}

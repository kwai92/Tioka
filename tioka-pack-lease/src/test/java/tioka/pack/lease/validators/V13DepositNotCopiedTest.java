package tioka.pack.lease.validators;

import com.tioka.ontology.application.pack.Violation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V13 押金不抄旧额：≥4 例。 */
class V13DepositNotCopiedTest {

    private final V13DepositNotCopiedValidator v = new V13DepositNotCopiedValidator();

    @Test
    void 通过_押金来自本合同证据() {
        Map<String, Object> draft = DraftFixture.draft();
        draft.put("priorDepositSourceIds", List.of("old-u1"));
        Map<String, Object> d = DraftFixture.deposit(draft, "保证金@S1");
        d.put("amount", DraftFixture.cell(30000, "KNOWN", DraftFixture.source("new-u1", "保证金 30000 元")));
        assertTrue(v.validate(draft, null).isEmpty());
    }

    @Test
    void 拒收_押金抄前序合同证据() {
        Map<String, Object> draft = DraftFixture.draft();
        draft.put("priorDepositSourceIds", List.of("old-u1"));
        Map<String, Object> d = DraftFixture.deposit(draft, "保证金@S1");
        d.put("amount", DraftFixture.cell(30000, "KNOWN", DraftFixture.source("old-u1", "保证金 30000 元")));
        List<Violation> out = v.validate(draft, null);
        assertEquals(1, out.size());
        assertEquals(Violation.Severity.REJECT, out.get(0).severity());
        assertTrue(out.get(0).message().contains("押金不抄旧额"));
    }

    @Test
    void 边界_无前序清单不判() {
        Map<String, Object> draft = DraftFixture.draft();
        Map<String, Object> d = DraftFixture.deposit(draft, "保证金@S1");
        d.put("amount", DraftFixture.cell(30000, "KNOWN", DraftFixture.source("old-u1", "保证金 30000 元")));
        assertTrue(v.validate(draft, null).isEmpty());
    }

    @Test
    void 交互_多来源部分抄袭也拒() {
        Map<String, Object> draft = DraftFixture.draft();
        draft.put("priorDepositSourceIds", List.of("old-u1"));
        Map<String, Object> d = DraftFixture.deposit(draft, "保证金@S1");
        d.put("amount", DraftFixture.cell(30000, "KNOWN",
                DraftFixture.source("new-u1", "本次调整说明"),
                DraftFixture.source("old-u1", "保证金 30000 元")));
        List<Violation> out = v.validate(draft, null);
        assertEquals(1, out.size());
    }
}

package tioka.pack.lease.validators;

import com.tioka.ontology.api.doc.DocumentView;
import com.tioka.ontology.application.pack.Violation;

import java.util.List;
import java.util.Map;

/** V14 主体变更合法（REJECT，M3-04 L93）：只出现在甲方变更、转让、回购合同里。 */
public final class V14PartyChangeLegalValidator extends BaseLeaseValidator {

    public static final String ID = "lease.V14.party-change-legal";

    private static final List<String> LEGAL_KINDS = List.of("甲方变更", "转让", "回购");
    private static final List<String> CHANGE_TYPES = List.of("主体变更", "甲方变更");

    @Override
    public String validatorId() {
        return ID;
    }

    @Override
    protected void doValidate(Map<String, Object> draft, DocumentView docs, List<Violation> out) {
        boolean hasChange = false;
        for (Object m : Drafts.list(draft, "modifications")) {
            Object type = Drafts.objMap(m).get("type");
            if (type != null && CHANGE_TYPES.contains(type.toString())) {
                hasChange = true;
            }
        }
        // 当事方条目直接带 changed 标记同样算主体变更
        for (Object p : Drafts.list(draft, "parties")) {
            Object changed = Drafts.objMap(p).get("changed");
            if (changed != null && Boolean.parseBoolean(changed.toString())) {
                hasChange = true;
            }
        }
        if (!hasChange) {
            return;
        }
        String kind = null;
        List<Object> contracts = Drafts.list(draft, "contracts");
        if (!contracts.isEmpty()) {
            kind = (String) Drafts.objMap(contracts.get(0)).get("kind");
        }
        if (kind == null || !LEGAL_KINDS.contains(kind)) {
            add(out, "modifications", "主体变更合法：主体变更只出现在甲方变更、转让、回购合同里（当前 kind="
                    + (kind == null ? "缺省" : kind) + "）");
        }
    }
}

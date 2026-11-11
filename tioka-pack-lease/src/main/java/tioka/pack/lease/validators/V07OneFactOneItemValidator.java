package tioka.pack.lease.validators;

import com.tioka.ontology.api.doc.DocumentView;
import com.tioka.ontology.application.pack.Violation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** V07 一个金额事实只归一个费用项（REJECT，两个都退回，M3-04 L86）。 */
public final class V07OneFactOneItemValidator extends BaseLeaseValidator {

    public static final String ID = "lease.V07.one-fact-one-item";

    @Override
    public String validatorId() {
        return ID;
    }

    @Override
    protected void doValidate(Map<String, Object> draft, DocumentView docs, List<Violation> out) {
        // 事实键（unitId + 金额）→ 首个归属费用项；重复即两侧都退回
        Map<String, String> owner = new HashMap<>();
        Map<String, Boolean> duplicated = new HashMap<>();
        for (Map.Entry<String, Object> e : feeItems(draft)) {
            for (Map<String, Object> src : sourcesOf(e.getValue())) {
                String unitId = src.get("unitId") == null ? "" : src.get("unitId").toString();
                Double amount = Drafts.asNumber(src.get("amount"));
                if (unitId.isEmpty() || amount == null) {
                    continue;
                }
                String fact = unitId + "#" + amount;
                String prev = owner.putIfAbsent(fact, e.getKey());
                if (prev != null && !prev.equals(e.getKey())) {
                    duplicated.put(fact, true);
                }
            }
        }
        for (Map.Entry<String, Object> e : feeItems(draft)) {
            for (Map<String, Object> src : sourcesOf(e.getValue())) {
                String unitId = src.get("unitId") == null ? "" : src.get("unitId").toString();
                Double amount = Drafts.asNumber(src.get("amount"));
                if (unitId.isEmpty() || amount == null) {
                    continue;
                }
                if (duplicated.containsKey(unitId + "#" + amount)) {
                    add(out, "feeItems[" + e.getKey() + "]",
                            "一个金额事实只归一个费用项：同一证据金额被多个费用项引用，两个都退回");
                    break;
                }
            }
        }
    }

    /** 费用项下所有金额来源（calc.baseAmount 为主）。 */
    private List<Map<String, Object>> sourcesOf(Object feeItem) {
        return Drafts.sources(Drafts.cell(feeItem, "calc.baseAmount"));
    }
}

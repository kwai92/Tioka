package tioka.pack.lease.validators;

import com.tioka.ontology.api.doc.DocumentView;
import com.tioka.ontology.application.pack.Violation;

import java.util.List;
import java.util.Map;

/** V12 续约 / 调价金额完整（REJECT，M3-04 L91）。 */
public final class V12RenewalAmountCompleteValidator extends BaseLeaseValidator {

    public static final String ID = "lease.V12.renewal-amount-complete";

    @Override
    public String validatorId() {
        return ID;
    }

    @Override
    protected void doValidate(Map<String, Object> draft, DocumentView docs, List<Violation> out) {
        boolean renewalContract = false;
        for (Object c : Drafts.list(draft, "contracts")) {
            Map<String, Object> contract = Drafts.objMap(c);
            Object kind = contract.get("kind");
            if (kind != null && kind.toString().contains("续约")) {
                renewalContract = true;
            }
        }
        for (Map.Entry<String, Object> e : feeItems(draft)) {
            Map<String, Object> item = Drafts.objMap(e.getValue());
            Object renew = item.get("renewal");
            if (!renewalContract && !(renew != null && Boolean.parseBoolean(renew.toString()))) {
                continue;
            }
            Map<String, Object> base = Drafts.cell(item, "calc.baseAmount");
            Double value = Drafts.asNumber(Drafts.cellValue(base));
            String status = Drafts.cellStatus(base);
            if (base.isEmpty() || value == null || (status != null && !"KNOWN".equals(status))) {
                add(out, "feeItems[" + e.getKey() + "].calc.baseAmount",
                        "续约/调价金额完整：续约期取值缺失或非明示");
            }
        }
    }
}

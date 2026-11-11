package tioka.pack.lease.validators;

import com.tioka.ontology.api.doc.DocumentView;
import com.tioka.ontology.application.pack.Violation;

import java.util.List;
import java.util.Map;

/** V13 押金不抄旧额（REJECT，M3-04 L92）：押金金额来源不得复用前序合同的证据单元。 */
public final class V13DepositNotCopiedValidator extends BaseLeaseValidator {

    public static final String ID = "lease.V13.deposit-not-copied";

    @Override
    public String validatorId() {
        return ID;
    }

    @Override
    protected void doValidate(Map<String, Object> draft, DocumentView docs, List<Violation> out) {
        List<Object> priorIds = Drafts.list(draft, "priorDepositSourceIds");
        if (priorIds.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> e : deposits(draft)) {
            for (Map<String, Object> src : Drafts.sources(Drafts.cell(e.getValue(), "amount"))) {
                String unitId = src.get("unitId") == null ? "" : src.get("unitId").toString();
                if (unitId.isEmpty()) {
                    continue;
                }
                for (Object prior : priorIds) {
                    if (unitId.equals(prior.toString())) {
                        add(out, "deposits[" + e.getKey() + "].amount",
                                "押金不抄旧额：金额来源复用前序合同证据单元 " + unitId);
                        break;
                    }
                }
            }
        }
    }
}

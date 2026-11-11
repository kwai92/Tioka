package tioka.pack.lease.validators;

import com.tioka.ontology.api.doc.DocumentView;
import com.tioka.ontology.application.pack.Violation;

import java.util.List;
import java.util.Map;

/** V03 提成比例不是税率（REJECT，M3-04 L82）。 */
public final class V03CommissionNotTaxValidator extends BaseLeaseValidator {

    public static final String ID = "lease.V03.commission-not-tax";

    @Override
    public String validatorId() {
        return ID;
    }

    @Override
    protected void doValidate(Map<String, Object> draft, DocumentView docs, List<Violation> out) {
        for (Map.Entry<String, Object> e : feeItems(draft)) {
            if (!"COMMISSION".equals(feeCodeOf(e.getKey()))) {
                continue;
            }
            Map<String, Object> rate = Drafts.cell(e.getValue(), "tax.rate");
            if (rate.isEmpty()) {
                continue;
            }
            for (Map<String, Object> src : Drafts.sources(rate)) {
                String quote = src.get("quote") == null ? "" : src.get("quote").toString();
                if (quote.contains("提成") || quote.contains("扣点")) {
                    add(out, "feeItems[" + e.getKey() + "].tax.rate", "提成比例不是税率：来源为提成/扣点条款");
                }
            }
        }
    }
}

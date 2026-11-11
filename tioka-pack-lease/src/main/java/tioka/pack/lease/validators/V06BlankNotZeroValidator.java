package tioka.pack.lease.validators;

import com.tioka.ontology.api.doc.DocumentView;
import com.tioka.ontology.application.pack.Violation;

import java.util.List;
import java.util.Map;

/** V06 空白不是 0（REJECT，M3-04 L85）。 */
public final class V06BlankNotZeroValidator extends BaseLeaseValidator {

    public static final String ID = "lease.V06.blank-not-zero";

    /** 需要巡检的金额取值路径。 */
    private static final List<String> AMOUNT_PATHS = List.of(
            "calc.baseAmount", "pay.payAmount", "abate.freeMonths", "tax.rate");

    @Override
    public String validatorId() {
        return ID;
    }

    @Override
    protected void doValidate(Map<String, Object> draft, DocumentView docs, List<Violation> out) {
        for (Map.Entry<String, Object> e : feeItems(draft)) {
            for (String p : AMOUNT_PATHS) {
                checkBlankZero(out, "feeItems[" + e.getKey() + "]." + p, Drafts.cell(e.getValue(), p));
            }
        }
        for (Map.Entry<String, Object> e : deposits(draft)) {
            checkBlankZero(out, "deposits[" + e.getKey() + "].amount", Drafts.cell(e.getValue(), "amount"));
        }
    }

    private void checkBlankZero(List<Violation> out, String path, Map<String, Object> cell) {
        if (cell.isEmpty()) {
            return;
        }
        if (!"BLANK".equals(Drafts.cellStatus(cell))) {
            return;
        }
        Double v = Drafts.asNumber(Drafts.cellValue(cell));
        if (v != null && v == 0d) {
            add(out, path, "空白不是 0：状态为空白时金额不得填 0");
        }
    }
}

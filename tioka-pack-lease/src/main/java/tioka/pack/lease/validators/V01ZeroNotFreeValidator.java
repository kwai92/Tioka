package tioka.pack.lease.validators;

import com.tioka.ontology.api.doc.DocumentView;
import com.tioka.ontology.application.pack.Violation;

import java.util.List;
import java.util.Map;

/** V01 0 元不是免租（REJECT，M3-04 L80）。 */
public final class V01ZeroNotFreeValidator extends BaseLeaseValidator {

    public static final String ID = "lease.V01.zero-not-free";

    @Override
    public String validatorId() {
        return ID;
    }

    @Override
    protected void doValidate(Map<String, Object> draft, DocumentView docs, List<Violation> out) {
        for (Map.Entry<String, Object> e : feeItems(draft)) {
            Map<String, Object> item = Drafts.map(e.getValue(), "abate");
            if (item.isEmpty()) {
                continue;
            }
            // ABATE.freeMonths 为 0，或 ABATE 单元本身金额为 0，都算「0 元当免租」；
            // 空白状态的 0 不属本条口径（空白不是 0 由 V06 兜底）
            Map<String, Object> freeMonthsCell = Drafts.cell(item, "freeMonths");
            if ("BLANK".equals(Drafts.cellStatus(freeMonthsCell))) {
                continue;
            }
            Double months = Drafts.asNumber(Drafts.cellValue(freeMonthsCell));
            if (months != null && months == 0d) {
                add(out, "feeItems[" + e.getKey() + "].abate.freeMonths", "0 元不是免租：免租月数为 0");
                continue;
            }
            Double abateAmount = Drafts.asNumber(Drafts.cellValue(item));
            if (abateAmount != null && abateAmount == 0d) {
                add(out, "feeItems[" + e.getKey() + "].abate", "0 元不是免租：减免金额为 0");
            }
        }
    }
}

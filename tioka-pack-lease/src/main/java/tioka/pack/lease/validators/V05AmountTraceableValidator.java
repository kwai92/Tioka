package tioka.pack.lease.validators;

import com.tioka.ontology.api.doc.DocumentView;
import com.tioka.ontology.application.pack.Violation;
import tioka.pack.lease.LeasePackConfig;

import java.util.List;
import java.util.Map;

/**
 * V05 金额溯源（REJECT，M3-04 L84）：引用证据里能找到归属本项的金额事实，
 * 等值或按标准换算，容差 0.02；金额正则与换算表来自领域包配置。
 */
public final class V05AmountTraceableValidator extends BaseLeaseValidator {

    public static final String ID = "lease.V05.amount-traceable";

    private final Amounts amounts;

    public V05AmountTraceableValidator(LeasePackConfig config) {
        this.amounts = new Amounts(config);
    }

    @Override
    public String validatorId() {
        return ID;
    }

    @Override
    protected void doValidate(Map<String, Object> draft, DocumentView docs, List<Violation> out) {
        for (Map.Entry<String, Object> e : feeItems(draft)) {
            checkCell(out, "feeItems[" + e.getKey() + "].calc.baseAmount",
                    Drafts.cell(e.getValue(), "calc.baseAmount"));
        }
        for (Map.Entry<String, Object> e : deposits(draft)) {
            checkCell(out, "deposits[" + e.getKey() + "].amount", Drafts.cell(e.getValue(), "amount"));
        }
    }

    private void checkCell(List<Violation> out, String path, Map<String, Object> cell) {
        if (cell.isEmpty()) {
            return;
        }
        Double value = Drafts.asNumber(Drafts.cellValue(cell));
        if (value == null) {
            return; // 非金额 / 空白由 V06 管
        }
        if (!amounts.traceable(value, Drafts.sources(cell))) {
            add(out, path, "金额溯源失败：引文中找不到等值（容差 0.02，含换算）的金额事实");
        }
    }
}

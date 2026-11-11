package tioka.pack.lease.validators;

import com.tioka.ontology.api.doc.DocumentView;
import com.tioka.ontology.application.pack.Violation;

import java.util.List;
import java.util.Map;

/**
 * V02 一次性金额不是月额（REJECT，M3-04 L81）。
 * CALC.baseAmount 的来源单位必须带期间后缀（元/月、万元/年、元/日…）；
 * 显式单位无期间（一次性总额）拒收。
 */
public final class V02OneoffNotMonthlyValidator extends BaseLeaseValidator {

    public static final String ID = "lease.V02.oneoff-not-monthly";

    @Override
    public String validatorId() {
        return ID;
    }

    @Override
    protected void doValidate(Map<String, Object> draft, DocumentView docs, List<Violation> out) {
        for (Map.Entry<String, Object> e : feeItems(draft)) {
            Map<String, Object> cell = Drafts.cell(e.getValue(), "calc.baseAmount");
            if (cell.isEmpty()) {
                continue;
            }
            for (Map<String, Object> src : Drafts.sources(cell)) {
                Object unit = src.get("unit");
                if (unit == null) {
                    continue; // 无显式单位交给 V05 溯源
                }
                String u = unit.toString();
                if (!u.contains("月") && !u.contains("年") && !u.contains("日")) {
                    add(out, "feeItems[" + e.getKey() + "].calc.baseAmount",
                            "一次性金额不是月额：来源单位「" + u + "」无期间后缀");
                }
            }
        }
    }
}

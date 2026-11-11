package tioka.pack.lease.validators;

import com.tioka.ontology.api.doc.DocumentView;
import com.tioka.ontology.application.pack.Violation;

import java.util.List;
import java.util.Map;

/**
 * V15 生效日有据（REVIEW，M3-04 L94）：没有依据标未知，依赖它的操作暂不应用。
 */
public final class V15EffectiveDateGroundedValidator extends BaseLeaseValidator {

    public static final String ID = "lease.V15.effective-date-grounded";

    @Override
    public String validatorId() {
        return ID;
    }

    @Override
    protected Violation.Severity severity() {
        return REVIEW;
    }

    @Override
    protected void doValidate(Map<String, Object> draft, DocumentView docs, List<Violation> out) {
        List<Object> modifications = Drafts.list(draft, "modifications");
        for (int i = 0; i < modifications.size(); i++) {
            Map<String, Object> mod = Drafts.objMap(modifications.get(i));
            Map<String, Object> eff = Drafts.cell(mod, "effectiveDate");
            String status = Drafts.cellStatus(eff);
            boolean ungrounded;
            if (eff.isEmpty()) {
                ungrounded = true; // 声明未带生效日单元
            } else {
                ungrounded = "UNKNOWN".equals(status)
                        || (status == null && Drafts.sources(eff).isEmpty());
            }
            if (ungrounded) {
                String type = mod.get("type") == null ? "?" : mod.get("type").toString();
                add(out, "modifications[" + i + "].effectiveDate",
                        "生效日有据：修改声明「" + type + "」生效日无依据标未知，依赖它的操作暂不应用");
            }
        }
    }
}

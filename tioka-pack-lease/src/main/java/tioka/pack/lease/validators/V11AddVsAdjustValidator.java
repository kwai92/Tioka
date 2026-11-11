package tioka.pack.lease.validators;

import com.tioka.ontology.api.doc.DocumentView;
import com.tioka.ontology.application.pack.Violation;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** V11 新增还是调整（REJECT，M3-04 L90）：同标的已有同类费用时是调整。 */
public final class V11AddVsAdjustValidator extends BaseLeaseValidator {

    public static final String ID = "lease.V11.add-vs-adjust";

    @Override
    public String validatorId() {
        return ID;
    }

    @Override
    protected void doValidate(Map<String, Object> draft, DocumentView docs, List<Violation> out) {
        Set<String> seenIdentity = new HashSet<>();
        for (Map.Entry<String, Object> e : feeItems(draft)) {
            String key = e.getKey();
            // 变更声明以「CODE@SUBJECT#n」承载同身份第二条；op=ADD 即声明为新增
            int hash = key.indexOf('#');
            Object op = Drafts.path(e.getValue(), "op");
            String identity = hash > 0 ? key.substring(0, hash) : key;
            if (hash > 0) {
                seenIdentity.add(identity);
                if (op != null && "ADD".equals(op.toString())) {
                    add(out, "feeItems[" + key + "].op",
                            "新增还是调整：同标的已有同类费用（" + identity + "）时应为调整 ADJUST");
                }
            }
        }
    }
}

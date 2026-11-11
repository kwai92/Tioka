package tioka.pack.lease.validators;

import com.tioka.ontology.api.doc.DocumentView;
import com.tioka.ontology.application.pack.ModelValidator;
import com.tioka.ontology.application.pack.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * V01〜V16 公共骨架：遍历 feeItems / deposits / modifications 的空安全迭代器 + 违规收集。
 * severity 按 M3-04 L74-97：V01〜V14 = REJECT，V15〜V16 = REVIEW。
 */
abstract class BaseLeaseValidator implements ModelValidator {

    /** V15/V16 的 REVIEW 严重级。 */
    static final Violation.Severity REVIEW = Violation.Severity.REVIEW;
    /** V01〜V14 的 REJECT 严重级。 */
    static final Violation.Severity REJECT = Violation.Severity.REJECT;

    @Override
    public final List<Violation> validate(Map<String, Object> draft, DocumentView docs) {
        List<Violation> out = new ArrayList<>();
        if (draft != null) {
            doValidate(draft, docs, out);
        }
        return List.copyOf(out);
    }

    /** 子类实现：只读入参，不抛错。 */
    protected abstract void doValidate(Map<String, Object> draft, DocumentView docs, List<Violation> out);

    /** 违规收集。 */
    void add(List<Violation> out, String path, String message) {
        out.add(new Violation(validatorId(), path, message, severity()));
    }

    /** 严重级（缺省 REJECT；V15/V16 覆写）。 */
    Violation.Severity severity() {
        return REJECT;
    }

    /** feeItems 迭代：key（RENT@S1）→ 对象 Map。 */
    Set<Map.Entry<String, Object>> feeItems(Map<String, Object> draft) {
        return Drafts.map(draft, "feeItems").entrySet();
    }

    /** deposits 迭代。 */
    Set<Map.Entry<String, Object>> deposits(Map<String, Object> draft) {
        return Drafts.map(draft, "deposits").entrySet();
    }

    /** feeItem 的费用码（键前缀 @ 之前）。 */
    static String feeCodeOf(String key) {
        int at = key.indexOf('@');
        return at > 0 ? key.substring(0, at) : key;
    }

    /** feeItem 的标的码（键 @ 之后）。 */
    static String subjectOf(String key) {
        int at = key.indexOf('@');
        return at > 0 ? key.substring(at + 1) : null;
    }
}

package tioka.pack.lease.validators;

import com.tioka.ontology.api.doc.DocumentView;
import com.tioka.ontology.application.pack.Violation;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** V09 标的归属（REJECT，M3-04 L88）：缺标的、跨标的引用拒收。 */
public final class V09SubjectScopeValidator extends BaseLeaseValidator {

    public static final String ID = "lease.V09.subject-scope";

    private static final Pattern SUBJECT_REF = Pattern.compile("S[0-9]+");

    @Override
    public String validatorId() {
        return ID;
    }

    @Override
    protected void doValidate(Map<String, Object> draft, DocumentView docs, List<Violation> out) {
        Map<String, Object> subjects = Drafts.map(draft, "subjects");
        for (Map.Entry<String, Object> e : feeItems(draft)) {
            String key = e.getKey();
            Object subject = Drafts.path(e.getValue(), "subject");
            String subjectValue = subject instanceof Map<?, ?> m && m.get("value") != null
                    ? m.get("value").toString()
                    : subject == null ? null : subject.toString();
            if (subjectValue == null || subjectValue.isBlank()) {
                add(out, "feeItems[" + key + "].subject", "标的归属：费用项缺标的");
                continue;
            }
            if (!subjects.isEmpty() && !subjects.containsKey(subjectValue)) {
                add(out, "feeItems[" + key + "].subject", "标的归属：标的 " + subjectValue + " 不在本模型清点中");
                continue;
            }
            // 跨标的引用：来源引文提及非本项标的
            String own = subjectOf(key) != null ? subjectOf(key) : subjectValue;
            for (Map<String, Object> src : Drafts.sources(Drafts.cell(e.getValue(), "calc.baseAmount"))) {
                String quote = src.get("quote") == null ? "" : src.get("quote").toString();
                java.util.regex.Matcher m = SUBJECT_REF.matcher(quote);
                while (m.find()) {
                    if (!m.group().equals(own)) {
                        add(out, "feeItems[" + key + "]", "标的归属：跨标的引用（引文提及 " + m.group() + "）");
                        return;
                    }
                }
            }
        }
    }
}

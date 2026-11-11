package tioka.pack.lease.validators;

import com.tioka.ontology.api.doc.DocumentView;
import com.tioka.ontology.application.pack.Violation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * V10 期间完整（REJECT，M3-04 L89）：只做唯一回填，回填不了拒收。
 * 起止一侧空白时，凭另一侧来源引文中的日期事实回填；无日期事实或多个矛盾日期 → 拒收。
 */
public final class V10PeriodCompleteValidator extends BaseLeaseValidator {

    public static final String ID = "lease.V10.period-complete";

    private static final Pattern DATE = Pattern.compile("[0-9]{4}[年./-][0-9]{1,2}[月./-][0-9]{1,2}");

    @Override
    public String validatorId() {
        return ID;
    }

    @Override
    protected void doValidate(Map<String, Object> draft, DocumentView docs, List<Violation> out) {
        for (Map.Entry<String, Object> e : Drafts.map(draft, "subjects").entrySet()) {
            checkPeriod(out, "subjects[" + e.getKey() + "]", e.getValue(), "termStart", "termEnd");
            checkPeriod(out, "subjects[" + e.getKey() + "]", e.getValue(), "termEnd", "termStart");
        }
    }

    private void checkPeriod(List<Violation> out, String path, Object subject,
                             String side, String other) {
        Map<String, Object> sideCell = Drafts.cell(subject, side);
        if (sideCell.isEmpty()) {
            return; // 整侧缺失不在此判定（由覆盖检查兜底）
        }
        String status = Drafts.cellStatus(sideCell);
        boolean sideBlank = "BLANK".equals(status) || Drafts.cellValue(sideCell) == null;
        if (!sideBlank) {
            return;
        }
        // 另一侧已知时才尝试唯一回填
        Map<String, Object> otherCell = Drafts.cell(subject, other);
        if (otherCell.isEmpty() || ("BLANK".equals(Drafts.cellStatus(otherCell)))) {
            return;
        }
        Set<String> dates = new LinkedHashSet<>();
        for (Map<String, Object> src : Drafts.sources(otherCell)) {
            String quote = src.get("quote") == null ? "" : src.get("quote").toString();
            Matcher m = DATE.matcher(quote);
            while (m.find()) {
                dates.add(m.group());
            }
        }
        if (dates.size() != 2) {
            // 回填不了：0 个或多个（非唯一成对）日期事实
            add(out, path + "." + side, "期间完整：" + side
                    + " 空白且无法唯一回填（来源日期事实 " + dates.size() + " 个）");
        }
    }
}

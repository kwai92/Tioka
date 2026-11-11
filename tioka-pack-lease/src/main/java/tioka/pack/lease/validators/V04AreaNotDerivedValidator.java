package tioka.pack.lease.validators;

import com.tioka.ontology.api.doc.DocumentView;
import com.tioka.ontology.application.pack.Violation;
import tioka.pack.lease.LeasePackConfig;

import java.util.List;
import java.util.Map;

/**
 * V04 面积不从租金反推（REJECT，M3-04 L83）：面积的来源角色必须是「事实」且引文含面积单位。
 */
public final class V04AreaNotDerivedValidator extends BaseLeaseValidator {

    public static final String ID = "lease.V04.area-not-derived";

    private final LeasePackConfig config;

    public V04AreaNotDerivedValidator(LeasePackConfig config) {
        this.config = config;
    }

    @Override
    public String validatorId() {
        return ID;
    }

    @Override
    protected void doValidate(Map<String, Object> draft, DocumentView docs, List<Violation> out) {
        java.util.regex.Pattern areaUnit = java.util.regex.Pattern.compile(config.areaUnitRegex());
        for (Map.Entry<String, Object> e : Drafts.map(draft, "subjects").entrySet()) {
            Map<String, Object> area = Drafts.cell(e.getValue(), "area");
            if (area.isEmpty()) {
                continue;
            }
            boolean ok = false;
            for (Map<String, Object> src : Drafts.sources(area)) {
                String quote = src.get("quote") == null ? "" : src.get("quote").toString();
                Object role = src.get("role");
                boolean factRole = role != null && role.toString().contains("事实");
                boolean hasUnit = areaUnit.matcher(quote).find();
                if (factRole && hasUnit) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                add(out, "subjects[" + e.getKey() + "].area",
                        "面积不从租金反推：来源角色必须是「事实」且引文含面积单位");
            }
        }
    }
}

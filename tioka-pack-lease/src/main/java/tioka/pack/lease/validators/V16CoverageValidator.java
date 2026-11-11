package tioka.pack.lease.validators;

import com.tioka.ontology.api.doc.DocumentView;
import com.tioka.ontology.application.pack.Violation;
import tioka.pack.lease.LeasePackConfig;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * V16 覆盖检查（REVIEW，M3-04 L95）：含金额、期限或「应 / 不得 / 有权」措辞的证据单元，
 * 要么归入对象或陈述，要么明确排除；未归属单元列出（REVIEW，不拒收）。
 */
public final class V16CoverageValidator extends BaseLeaseValidator {

    public static final String ID = "lease.V16.coverage";

    private static final Pattern KEYWORD =
            Pattern.compile("应|不得|有权|期限");

    private final LeasePackConfig config;

    public V16CoverageValidator(LeasePackConfig config) {
        this.config = config;
    }

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
        List<Object> units = Drafts.list(draft, "documentUnits");
        if (units.isEmpty()) {
            return; // 管线未提供证据单元索引时不判定
        }
        Pattern amount = Pattern.compile(config.amountRegex());
        Set<String> covered = coveredUnitIds(draft);

        StringBuilder uncovered = new StringBuilder();
        int count = 0;
        for (Object u : units) {
            Map<String, Object> unit = Drafts.objMap(u);
            String unitId = unit.get("unitId") == null ? "" : unit.get("unitId").toString();
            String text = unit.get("text") == null ? "" : unit.get("text").toString();
            boolean notable = amount.matcher(text).find() || KEYWORD.matcher(text).find();
            if (!notable || covered.contains(unitId)) {
                continue;
            }
            count++;
            if (count <= 5) {
                uncovered.append(unitId).append(' ');
            }
        }
        if (count > 0) {
            add(out, "documentUnits",
                    "覆盖检查：含金额/期限/义务措辞的证据单元未归属也未排除（" + count + " 个：" + uncovered + "…）");
        }
    }

    /** 已归属（各来源引用）与已排除单元集合。 */
    private Set<String> coveredUnitIds(Map<String, Object> draft) {
        Set<String> ids = new HashSet<>();
        for (Object x : Drafts.list(draft, "excludedUnits")) {
            ids.add(x.toString());
        }
        for (Map.Entry<String, Object> e : feeItems(draft)) {
            collectIds(e.getValue(), ids);
        }
        for (Map.Entry<String, Object> e : deposits(draft)) {
            collectIds(e.getValue(), ids);
        }
        for (Object s : Drafts.list(draft, "statements")) {
            collectIds(s, ids);
        }
        return ids;
    }

    /** 递归收集对象内所有 sources[].unitId。 */
    private void collectIds(Object node, Set<String> ids) {
        if (node instanceof Map<?, ?> m) {
            Object sources = m.get("sources");
            if (sources instanceof List<?> list) {
                for (Object s : list) {
                    if (s instanceof Map<?, ?> sm && sm.get("unitId") != null) {
                        ids.add(sm.get("unitId").toString());
                    }
                }
            }
            for (Object v : m.values()) {
                collectIds(v, ids);
            }
        } else if (node instanceof List<?> list) {
            for (Object v : list) {
                collectIds(v, ids);
            }
        }
    }
}

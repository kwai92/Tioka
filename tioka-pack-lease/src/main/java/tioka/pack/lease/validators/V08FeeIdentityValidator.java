package tioka.pack.lease.validators;

import com.tioka.ontology.api.doc.DocumentView;
import com.tioka.ontology.application.pack.Violation;

import java.util.List;
import java.util.Map;

/**
 * V08 费种身份一致（REJECT，M3-04 L87）：来源引文的费种关键词必须与费用码一致。
 */
public final class V08FeeIdentityValidator extends BaseLeaseValidator {

    public static final String ID = "lease.V08.fee-identity";

    /** 费用码 → 身份关键词（词表口径）。 */
    static final Map<String, List<String>> KEYWORDS = Map.of(
            "RENT", List.of("租金", "租费"),
            "MGMT", List.of("物业管理费", "物业费", "管理费"),
            "UTL", List.of("水电", "能源", "水费", "电费"),
            "PROMO", List.of("推广", "宣传"),
            "COMMISSION", List.of("提成", "扣点"),
            "DECOR", List.of("装修"));

    @Override
    public String validatorId() {
        return ID;
    }

    @Override
    protected void doValidate(Map<String, Object> draft, DocumentView docs, List<Violation> out) {
        for (Map.Entry<String, Object> e : feeItems(draft)) {
            String code = feeCodeOf(e.getKey());
            Map<String, Object> cell = Drafts.cell(e.getValue(), "calc.baseAmount");
            List<Map<String, Object>> sources = Drafts.sources(cell);
            if (sources.isEmpty()) {
                continue;
            }
            boolean own = false;
            boolean foreign = false;
            String foreignName = null;
            for (Map<String, Object> src : sources) {
                String quote = src.get("quote") == null ? "" : src.get("quote").toString();
                if (containsAny(quote, KEYWORDS.get(code))) {
                    own = true;
                }
                for (Map.Entry<String, List<String>> kw : KEYWORDS.entrySet()) {
                    if (!kw.getKey().equals(code) && containsAny(quote, kw.getValue())) {
                        foreign = true;
                        foreignName = kw.getKey();
                    }
                }
            }
            if (!own && foreign) {
                add(out, "feeItems[" + e.getKey() + "]",
                        "费种身份一致：引文更像「" + foreignName + "」而非 " + code);
            }
        }
    }

    private static boolean containsAny(String text, List<String> keywords) {
        if (keywords == null) {
            return false;
        }
        for (String k : keywords) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }
}

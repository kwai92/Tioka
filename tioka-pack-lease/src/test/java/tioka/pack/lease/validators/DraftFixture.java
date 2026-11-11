package tioka.pack.lease.validators;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 校验器测试夹具：按 Drafts 约定快速组装草稿 Map。
 */
final class DraftFixture {

    private DraftFixture() {
    }

    static Map<String, Object> draft() {
        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("contracts", new ArrayList<>(List.of(contract("主合同"))));
        draft.put("subjects", new LinkedHashMap<String, Object>());
        draft.put("feeItems", new LinkedHashMap<String, Object>());
        draft.put("deposits", new LinkedHashMap<String, Object>());
        draft.put("modifications", new ArrayList<>());
        draft.put("statements", new ArrayList<>());
        draft.put("parties", new ArrayList<>());
        return draft;
    }

    static Map<String, Object> contract(String kind) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("kind", kind);
        c.put("contractNo", "HT-001");
        c.put("signDate", "2026-01-01");
        c.put("effectiveDate", "2026-01-01");
        return c;
    }

    static Map<String, Object> subject(Map<String, Object> draft, String code) {
        Map<String, Object> subjects = (Map<String, Object>) draft.get("subjects");
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("code", code);
        subjects.put(code, s);
        return s;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> feeItem(Map<String, Object> draft, String key) {
        Map<String, Object> feeItems = (Map<String, Object>) draft.get("feeItems");
        Map<String, Object> item = new LinkedHashMap<>();
        int at = key.indexOf('@');
        item.put("feeCode", at > 0 ? key.substring(0, at) : key);
        item.put("subject", at > 0 ? key.substring(at + 1) : null);
        feeItems.put(key, item);
        return item;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> deposit(Map<String, Object> draft, String key) {
        Map<String, Object> deposits = (Map<String, Object>) draft.get("deposits");
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("depositType", "履约保证金");
        d.put("subject", "S1");
        deposits.put(key, d);
        return d;
    }

    /** 取值单元：value + status + sources。 */
    static Map<String, Object> cell(Object value, String status, Map<String, Object>... sources) {
        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("value", value);
        cell.put("status", status);
        cell.put("sources", new ArrayList<>(List.of(sources)));
        return cell;
    }

    static Map<String, Object> source(String unitId, String quote) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("unitId", unitId);
        s.put("quote", quote);
        return s;
    }

    static Map<String, Object> source(String unitId, String quote, Object amount, String unit) {
        Map<String, Object> s = source(unitId, quote);
        if (amount != null) {
            s.put("amount", amount);
        }
        if (unit != null) {
            s.put("unit", unit);
        }
        return s;
    }

    static Map<String, Object> modification(String type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("target", "RENT@S1");
        return m;
    }
}

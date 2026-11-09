package com.tioka.runtime.application.changeset;

import com.tioka.runtime.api.spi.ChangeSet;
import com.tioka.runtime.infrastructure.persistence.TrustActionDO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TRU-06b：把 trust_action 的 {@link ChangeSet} 渲染为渠道无关审批卡片 card_json
 * （M2-05 第 43 行：card_json = {changeSet, reversibility, estimated, ...}）。
 *
 * <p>表格口径（M2-05 第 102 行）：「对象 | 字段 | 原值 | 新值」前 10 条；
 * UPDATE 只列变化字段；&gt;10 条或 estimated 时 viewAll=true（前端/飞书侧出「查看全部」按钮）。
 */
@Component
public class ChangeSetCardRenderer {

    public static final int MAX_TABLE_ROWS = 10;

    /** 渲染 card_json：标题 = changeSet.summary；带 changeSet / reversibility / estimated / changeStatus。 */
    public Map<String, Object> render(TrustActionDO row, ChangeSet changeSet) {
        Map<String, Object> card = new LinkedHashMap<>();
        String summary = changeSet == null ? "" : changeSet.summary();
        card.put("kind", "changeSet");
        card.put("title", summary == null ? "" : summary);
        card.put("toolName", row.getToolName());
        card.put("preparedId", row.getId());
        card.put("changeStatus", row.getStatus());
        card.put("reversibility", row.getReversibility());
        card.put("estimated", row.getEstimated() != null && row.getEstimated());
        card.put("changeSet", changeSet == null ? Map.of() : changeSet);
        card.put("changeTable", tableRows(changeSet));
        Map<String, Object> totals = new LinkedHashMap<>();
        if (changeSet != null && changeSet.totals() != null) {
            totals.put("records", changeSet.totals().records());
            totals.put("amountMicros", changeSet.totals().amountMicros());
            totals.put("currency", changeSet.totals().currency());
        }
        card.put("changeTotals", totals);
        int changeCount = changeSet == null ? 0 : changeSet.changes().size();
        card.put("viewAll", changeCount > MAX_TABLE_ROWS
                || (row.getEstimated() != null && row.getEstimated()));
        return card;
    }

    /** 「对象 | 字段 | 原值 | 新值」行；UPDATE 只列变化字段；最多 10 条。 */
    public List<Map<String, Object>> tableRows(ChangeSet changeSet) {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (changeSet == null) {
            return rows;
        }
        for (ChangeSet.Change c : changeSet.changes()) {
            if (rows.size() >= MAX_TABLE_ROWS) {
                break;
            }
            if (c.op() != null && "UPDATE".equalsIgnoreCase(c.op().name())) {
                appendDiffRows(rows, c);
            } else {
                // CREATE/SEND/PAY 展示 after；DELETE 展示 before（after 为空）
                appendFieldRows(rows, c, c.after().isEmpty() ? c.before() : c.after(),
                        !c.after().isEmpty());
            }
        }
        return rows;
    }

    private void appendDiffRows(List<Map<String, Object>> rows, ChangeSet.Change c) {
        for (Map.Entry<String, Object> e : c.after().entrySet()) {
            if (rows.size() >= MAX_TABLE_ROWS) {
                return;
            }
            Object before = c.before().get(e.getKey());
            if (before != null && before.equals(e.getValue())) {
                continue;
            }
            rows.add(row(c, e.getKey(), before, e.getValue()));
        }
    }

    private void appendFieldRows(List<Map<String, Object>> rows, ChangeSet.Change c,
                                 Map<String, Object> fields, boolean asAfter) {
        if (fields.isEmpty()) {
            if (rows.size() < MAX_TABLE_ROWS) {
                rows.add(row(c, "-", null, null));
            }
            return;
        }
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (rows.size() >= MAX_TABLE_ROWS) {
                return;
            }
            rows.add(row(c, e.getKey(), asAfter ? null : e.getValue(), asAfter ? e.getValue() : null));
        }
    }

    private Map<String, Object> row(ChangeSet.Change c, String field, Object before, Object after) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("objectType", c.objectType());
        r.put("objectId", c.objectId());
        r.put("op", c.op() == null ? "" : c.op().name());
        r.put("field", field);
        r.put("before", before == null ? "" : String.valueOf(before));
        r.put("after", after == null ? "" : String.valueOf(after));
        return r;
    }
}

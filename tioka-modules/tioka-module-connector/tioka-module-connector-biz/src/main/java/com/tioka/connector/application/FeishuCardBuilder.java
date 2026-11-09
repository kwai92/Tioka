package com.tioka.connector.application;

import com.tioka.connector.api.im.ImMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 飞书卡片构建器（PK-25 / TRU-06b）：把渠道无关 {@link ImMessage} 渲染为飞书卡片 JSON。
 *
 * <p>变更单卡片口径（M2-05 第 100–104 行）：
 * <ul>
 *   <li>标题 = changeSet.summary；标签按可逆性（不可撤销红色、estimated 加「推断」）；</li>
 *   <li>表格「对象 | 字段 | 原值 | 新值」前 10 条（UPDATE 只列变化字段）；</li>
 *   <li>合计记录数/金额（按 currency 格式化）；</li>
 *   <li>按钮：批准 / 拒绝 / 在 Tioka 中查看全部（&gt;10 条或 estimated 时）。</li>
 * </ul>
 */
@Component
public class FeishuCardBuilder {

    /** 渲染进表格的最大变更行数（M2-05 第 102 行）。 */
    public static final int MAX_TABLE_ROWS = 10;

    /** 从 runtime_approval.card_json（含 changeSet/changeTable 字段的 map）组装渠道无关消息。 */
    public ImMessage changeSetCardFromJsonMap(Map<String, Object> cardJson) {
        if (cardJson == null || cardJson.isEmpty()) {
            throw new IllegalArgumentException("card_json 为空，无法组装变更单卡片");
        }
        String summary = String.valueOf(cardJson.getOrDefault("title", ""));
        String changeStatus = stringOf(cardJson.get("changeStatus"));
        String reversibility = stringOf(cardJson.get("reversibility"));
        boolean estimated = Boolean.TRUE.equals(cardJson.get("estimated"));

        List<ImMessage.Row> rows = new ArrayList<>();
        Object table = cardJson.get("changeTable");
        if (table instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                rows.add(new ImMessage.Row(
                        stringOf(m.get("objectType")),
                        stringOf(m.get("objectId")),
                        stringOf(m.get("op")),
                        stringOf(m.get("field")),
                        stringOf(m.get("before")),
                        stringOf(m.get("after"))));
            }
        }
        Map<?, ?> totals = cardJson.get("changeTotals") instanceof Map<?, ?> t ? t : Map.of();
        Long records = totals.get("records") instanceof Number n ? n.longValue() : null;
        Long amountMicros = totals.get("amountMicros") instanceof Number n ? n.longValue() : null;
        String currency = stringOf(totals.get("currency"));
        boolean truncated = Boolean.TRUE.equals(cardJson.get("viewAll"));

        ImMessage.ChangeTable changeTable = new ImMessage.ChangeTable(
                summary, changeStatus, reversibility, estimated, rows, truncated,
                records, amountMicros, currency);
        return new ImMessage("changeSet", summary, null, List.of(), List.of(), List.of(), changeTable, Map.of());
    }

    /** 把 {@link ImMessage} 渲染为飞书卡片 JSON（schema 2.0 风格）。 */
    public Map<String, Object> build(ImMessage message) {
        if (message == null) {
            throw new IllegalArgumentException("message 为空");
        }
        if (message.changeTable() != null) {
            return buildChangeSetCard(message);
        }
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("schema", "2.0");
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("title", Map.of("tag", "plain_text", "content", message.title() == null ? "" : message.title()));
        card.put("header", header);
        List<Map<String, Object>> elements = new ArrayList<>();
        if (message.text() != null && !message.text().isBlank()) {
            elements.add(Map.of("tag", "markdown", "content", message.text()));
        }
        for (String p : message.paragraphs()) {
            elements.add(Map.of("tag", "markdown", "content", p));
        }
        card.put("body", Map.of("elements", elements));
        return card;
    }

    private Map<String, Object> buildChangeSetCard(ImMessage message) {
        ImMessage.ChangeTable ct = message.changeTable();
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("schema", "2.0");

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("title", Map.of("tag", "plain_text", "content", ct.summary() == null ? "" : ct.summary()));
        header.put("template", headerTemplate(ct.reversibility()));
        card.put("header", header);

        List<Map<String, Object>> elements = new ArrayList<>();
        List<String> tags = new ArrayList<>();
        tags.add("状态 " + stringOf(ct.changeStatus()));
        tags.add(reversibilityLabel(ct.reversibility()));
        if (ct.estimated()) {
            tags.add("推断");
        }
        elements.add(Map.of("tag", "markdown", "content", String.join("　｜　", tags)));

        List<ImMessage.Row> shown = ct.rows().size() > MAX_TABLE_ROWS
                ? ct.rows().subList(0, MAX_TABLE_ROWS) : ct.rows();
        List<Map<String, Object>> columns = List.of(
                Map.of("name", "objectType", "title", "对象", "dataType", "text"),
                Map.of("name", "field", "title", "字段", "dataType", "text"),
                Map.of("name", "before", "title", "原值", "dataType", "text"),
                Map.of("name", "after", "title", "新值", "dataType", "text"));
        List<Map<String, Object>> rowMaps = new ArrayList<>();
        for (ImMessage.Row r : shown) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("objectType", r.objectType() + "#" + stringOf(r.objectId()) + " " + stringOf(r.op()));
            row.put("field", stringOf(r.field()));
            row.put("before", stringOf(r.before()));
            row.put("after", stringOf(r.after()));
            rowMaps.add(row);
        }
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("tag", "table");
        table.put("columns", columns);
        table.put("rows", rowMaps);
        elements.add(table);

        String totals = totalsLine(ct);
        if (!totals.isBlank()) {
            elements.add(Map.of("tag", "note",
                    "elements", List.of(Map.of("tag", "plain_text", "content", totals))));
        }

        List<Map<String, Object>> buttons = new ArrayList<>();
        buttons.add(button("批准", "approve"));
        buttons.add(button("拒绝", "reject"));
        if (ct.truncated() || ct.estimated()) {
            buttons.add(button("在 Tioka 中查看全部", "view_all"));
        }
        elements.add(Map.of("tag", "button_group", "buttons", buttons));

        card.put("body", Map.of("elements", elements));
        return card;
    }

    private static String headerTemplate(String reversibility) {
        return "IRREVERSIBLE".equals(reversibility) ? "red" : "blue";
    }

    private static String reversibilityLabel(String reversibility) {
        return switch (stringOf(reversibility)) {
            case "REVERSIBLE" -> "可撤销";
            case "COMPENSABLE" -> "可冲销";
            case "IRREVERSIBLE" -> "不可撤销";
            default -> stringOf(reversibility);
        };
    }

    private static String totalsLine(ImMessage.ChangeTable ct) {
        List<String> parts = new ArrayList<>();
        if (ct.records() != null) {
            parts.add("合计 " + ct.records() + " 条记录");
        }
        if (ct.amountMicros() != null) {
            String currency = ct.currency() == null || ct.currency().isBlank() ? "CNY" : ct.currency();
            parts.add(String.format("金额 %s %s", ct.amountMicros() / 1_000_000.0, currency));
        }
        return String.join("　｜　", parts);
    }

    private static Map<String, Object> button(String text, String action) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("tag", "button");
        b.put("text", Map.of("tag", "plain_text", "content", text));
        b.put("type", "primary".equals(action) || "approve".equals(action) ? "primary" : "default");
        b.put("value", Map.of("action", action));
        return b;
    }

    private static String stringOf(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}

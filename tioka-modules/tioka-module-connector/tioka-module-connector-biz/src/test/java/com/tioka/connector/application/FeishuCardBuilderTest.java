package com.tioka.connector.application;

import com.tioka.connector.api.im.ImMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TRU-06b / PK-25：FeishuCardBuilder 变更单分支（M2-05 第 117 行口径 + 四态快照）。
 */
class FeishuCardBuilderTest {

    private final FeishuCardBuilder builder = new FeishuCardBuilder();

    @Test
    void changeTable12RowsShows10AndViewAll() {
        List<ImMessage.Row> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            rows.add(new ImMessage.Row("order", "ord_" + i, "UPDATE", "status", "OPEN", "SHIPPED"));
        }
        ImMessage msg = changeSetMessage(rows, "IRREVERSIBLE", false, 12L, null, true);

        Map<String, Object> card = builder.build(msg);

        assertEquals("red", ((Map<?, ?>) card.get("header")).get("template"), "不可撤销标签为红色模板");
        Map<?, ?> body = (Map<?, ?>) card.get("body");
        List<?> elements = (List<?>) body.get("elements");
        Map<?, ?> table = elements.stream()
                .filter(e -> ((Map<?, ?>) e).get("tag").equals("table"))
                .map(e -> (Map<?, ?>) e)
                .findFirst().orElseThrow();
        assertEquals(10, ((List<?>) table.get("rows")).size(), "12 条变化只渲染前 10 行");
        String buttons = String.valueOf(elements.stream()
                .filter(e -> ((Map<?, ?>) e).get("tag").equals("button_group"))
                .findFirst().orElseThrow());
        assertTrue(buttons.contains("在 Tioka 中查看全部"), ">10 条时带「查看全部」按钮");
        assertTrue(buttons.contains("批准") && buttons.contains("拒绝"));
    }

    @Test
    void irreversibleHeaderRedAndEstimatedAddsInferredTag() {
        ImMessage irreversible = changeSetMessage(
                List.of(new ImMessage.Row("order", "ord_1", "SEND", "message", "", "hello")),
                "IRREVERSIBLE", false, null, null, false);
        Map<?, ?> header = (Map<?, ?>) builder.build(irreversible).get("header");
        assertEquals("red", header.get("template"));

        ImMessage estimated = changeSetMessage(
                List.of(new ImMessage.Row("tool", "crm.update", "UPDATE", "stage", "A", "B")),
                "REVERSIBLE", true, null, null, true);
        String cardJson = String.valueOf(builder.build(estimated));
        assertTrue(cardJson.contains("推断"), "estimated 时加「推断」标签");
        assertFalse(cardJson.contains("不可撤销"), "REVERSIBLE 不应出现不可撤销标签");
    }

    @Test
    void totalsLineFormatsAmountByCurrency() {
        ImMessage msg = changeSetMessage(
                List.of(new ImMessage.Row("ar", "ar_1", "UPDATE", "amount", "100", "500")),
                "COMPENSABLE", false, 3L, 3_450_000L, false);
        String cardJson = String.valueOf(builder.build(msg));
        assertTrue(cardJson.contains("合计 3 条记录"));
        assertTrue(cardJson.contains("金额 3.45 USD"));
    }

    @Test
    void fourStateSnapshots() {
        // PROPOSED + estimated（推断，带查看全部）
        Map<String, Object> proposed = builder.build(changeSetMessage(
                List.of(new ImMessage.Row("tool", "crm.update", "UPDATE", "stage", "A", "B")),
                "REVERSIBLE", true, null, null, true));
        assertTrue(String.valueOf(proposed).contains("状态 PROPOSED"));
        assertTrue(String.valueOf(proposed).contains("推断"));

        // APPLIED
        Map<String, Object> applied = builder.build(changeSetMessage(
                List.of(new ImMessage.Row("order", "ord_1", "UPDATE", "status", "OPEN", "SHIPPED")),
                "APPLIED", "REVERSIBLE", false, null, null, false));
        assertTrue(String.valueOf(applied).contains("状态 APPLIED"));
        assertTrue(String.valueOf(applied).contains("可撤销"));

        // STALE
        Map<String, Object> stale = builder.build(changeSetMessage(
                List.of(new ImMessage.Row("order", "ord_1", "UPDATE", "status", "OPEN", "SHIPPED")),
                "STALE", "COMPENSABLE", false, null, null, false));
        assertTrue(String.valueOf(stale).contains("状态 STALE"));
        assertTrue(String.valueOf(stale).contains("可冲销"));

        // IRREVERSIBLE + estimated
        Map<String, Object> irreversible = builder.build(changeSetMessage(
                List.of(new ImMessage.Row("order", "ord_1", "SEND", "text", "", "hi")),
                "IRREVERSIBLE", true, 1L, null, true));
        assertTrue(String.valueOf(irreversible).contains("不可撤销"));
        assertTrue(String.valueOf(irreversible).contains("推断"));
    }

    @Test
    void changeSetCardFromJsonMapRoundTrip() {
        Map<String, Object> cardJson = new LinkedHashMap<>();
        cardJson.put("kind", "changeSet");
        cardJson.put("title", "更新订单 ord_1");
        cardJson.put("changeStatus", "PROPOSED");
        cardJson.put("reversibility", "IRREVERSIBLE");
        cardJson.put("estimated", false);
        cardJson.put("viewAll", false);
        cardJson.put("changeTable", List.of(Map.of(
                "objectType", "order", "objectId", "ord_1", "op", "UPDATE",
                "field", "status", "before", "OPEN", "after", "SHIPPED")));

        ImMessage msg = builder.changeSetCardFromJsonMap(cardJson);
        assertEquals("changeSet", msg.kind());
        assertEquals("更新订单 ord_1", msg.title());
        assertEquals("IRREVERSIBLE", msg.changeTable().reversibility());
        assertEquals(1, msg.changeTable().rows().size());

        Map<String, Object> card = builder.build(msg);
        assertEquals("更新订单 ord_1", ((Map<?, ?>) ((Map<?, ?>) card.get("header")).get("title")).get("content"));
        assertTrue(String.valueOf(card).contains("SHIPPED"));
    }

    @Test
    void plainCardWithoutChangeTableKeepsSimpleShape() {
        ImMessage msg = new ImMessage("card", "标题", "正文", List.of(), List.of(), List.of(), null, Map.of());
        Map<String, Object> card = builder.build(msg);
        Map<?, ?> body = (Map<?, ?>) card.get("body");
        List<?> elements = (List<?>) body.get("elements");
        assertTrue(elements.stream().noneMatch(e -> ((Map<?, ?>) e).get("tag").equals("table")));
        assertEquals("正文", ((Map<?, ?>) elements.get(0)).get("content"));
    }

    private ImMessage changeSetMessage(List<ImMessage.Row> rows, String reversibility, boolean estimated,
                                       Long records, Long amountMicros, boolean truncated) {
        return changeSetMessage(rows, "PROPOSED", reversibility, estimated, records, amountMicros, truncated);
    }

    private ImMessage changeSetMessage(List<ImMessage.Row> rows, String status, String reversibility,
                                       boolean estimated, Long records, Long amountMicros, boolean truncated) {
        ImMessage.ChangeTable ct = new ImMessage.ChangeTable(
                "更新订单 ord_1", status, reversibility, estimated, rows, truncated,
                records, amountMicros, amountMicros == null ? null : "USD");
        return new ImMessage("changeSet", ct.summary(), null, List.of(), List.of(), List.of(), ct, Map.of());
    }
}

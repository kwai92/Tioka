package com.tioka.connector.api.im;

import java.util.List;
import java.util.Map;

/**
 * 与渠道无关的消息/卡片描述；适配器负责转为飞书卡片 JSON 等。
 *
 * @param kind        {@code text} / {@code markdown} / {@code card} / {@code changeSet}
 * @param title       卡片标题，可空
 * @param text        正文
 * @param paragraphs  段落
 * @param buttons     按钮
 * @param fields      表单项
 * @param changeTable 变更单表格组件（TRU-06b / M2-05 第 97 行，渠道无关）；无变更单时为 null
 * @param extras      渠道扩展
 */
public record ImMessage(
        String kind,
        String title,
        String text,
        List<String> paragraphs,
        List<Map<String, Object>> buttons,
        List<Map<String, Object>> fields,
        ChangeTable changeTable,
        Map<String, Object> extras) {

    public ImMessage {
        paragraphs = paragraphs == null ? List.of() : List.copyOf(paragraphs);
        buttons = buttons == null ? List.of() : List.copyOf(buttons);
        fields = fields == null ? List.of() : List.copyOf(fields);
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }

    /** 渠道无关变更单组件（M2-05 TRU-06）：飞书侧渲染为卡片 JSON 2.0 的表格组件。 */
    public record ChangeTable(
            String summary,
            String changeStatus,
            String reversibility,
            boolean estimated,
            List<Row> rows,
            boolean truncated,
            Long records,
            Long amountMicros,
            String currency) {
        public ChangeTable {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    /** 表格行：对象 | 字段 | 原值 | 新值（UPDATE 只列变化字段）。 */
    public record Row(
            String objectType,
            String objectId,
            String op,
            String field,
            String before,
            String after) {}
}

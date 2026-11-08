package com.tioka.connector.api.im;

import java.util.List;
import java.util.Map;

/**
 * 与渠道无关的消息/卡片描述；适配器负责转为飞书卡片 JSON 等。
 *
 * @param kind     {@code text} / {@code markdown} / {@code card}
 * @param title    卡片标题，可空
 * @param text     正文
 * @param paragraphs 段落
 * @param buttons  按钮
 * @param fields   表单项
 * @param extras   渠道扩展
 */
public record ImMessage(
        String kind,
        String title,
        String text,
        List<String> paragraphs,
        List<Map<String, Object>> buttons,
        List<Map<String, Object>> fields,
        Map<String, Object> extras) {

    public ImMessage {
        paragraphs = paragraphs == null ? List.of() : List.copyOf(paragraphs);
        buttons = buttons == null ? List.of() : List.copyOf(buttons);
        fields = fields == null ? List.of() : List.copyOf(fields);
        extras = extras == null ? Map.of() : Map.copyOf(extras);
    }
}

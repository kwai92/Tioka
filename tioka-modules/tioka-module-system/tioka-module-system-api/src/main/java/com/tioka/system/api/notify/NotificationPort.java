package com.tioka.system.api.notify;

import java.util.Map;

/**
 * 通知端口（约束 F21）：规则告警与编排 {@code notice} 一律经此发出。
 * M2 实现为 IM 发送；M3 NTF-01 / GOV-13 接管时调用方不改。
 */
public interface NotificationPort {

    void notify(NotificationCommand command);

    /**
     * @param channel    渠道提示，如 {@code im:feishu}、{@code im:binding}
     * @param recipient  接收人（用户 ID / open_id / chat_id，由实现解释）
     * @param template   模板或事件码
     * @param variables  渲染变量
     * @param dedupKey   去重键，可空
     */
    record NotificationCommand(
            String channel,
            String recipient,
            String template,
            Map<String, Object> variables,
            String dedupKey) {

        public NotificationCommand {
            variables = variables == null ? Map.of() : Map.copyOf(variables);
        }
    }
}

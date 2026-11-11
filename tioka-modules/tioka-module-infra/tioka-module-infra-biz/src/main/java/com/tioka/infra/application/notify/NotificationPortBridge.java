package com.tioka.infra.application.notify;

import com.tioka.framework.label.DataLabel;
import com.tioka.infra.api.notify.Notice;
import com.tioka.infra.api.notify.Recipient;
import com.tioka.system.api.notify.NotificationPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M2 {@link NotificationPort} → M3 {@link NotificationService} 桥接（NTF-01 / F21）：
 * 「M3 NTF-01 接管时调用方不改」——RulePauseService / QuotaWarnNotifier 经
 * ObjectProvider 注入本桥，继续用 NotificationCommand 调用。
 *
 * <p>最小映射（偏差登记）：
 * <ul>
 *   <li>noticeKey = dedupKey（空则退 template）；实际 dedup_key = noticeKey:channel:recipient</li>
 *   <li>title = template；variables 渲染为单键值块；actions 空</li>
 *   <li>label = INTERNAL（M2 调用方均为内部运营告警）</li>
 *   <li>recipient 视为 Tioka 用户 ID（workspace 级收件人如 quota scopeId 无用户映射时，
 *       消费侧记 RECIPIENT_UNMAPPED——workspace 级路由待 GOV-13）</li>
 *   <li>投递渠道列表由 {@code tioka.notify.bridge.channels} 配置（缺省 noop；NTF-02 后改 feishu,email）</li>
 * </ul>
 *
 * <p>开关 {@code tioka.notify.bridge.enabled} <b>缺省 false</b>：两个调用方当前静默跳过
 * （端口缺席），启用桥接后行为改变（通知真实入队）；按仓内「新能力默认关闭」惯例显式开启。
 */
public class NotificationPortBridge implements NotificationPort {

    private final NotificationService service;
    private final List<String> channels;

    public NotificationPortBridge(NotificationService service, List<String> channels) {
        this.service = service;
        this.channels = channels == null ? List.of() : List.copyOf(channels);
    }

    @Override
    public void notify(NotificationCommand command) {
        String noticeKey = command.dedupKey() == null || command.dedupKey().isBlank()
                ? command.template()
                : command.dedupKey();
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (command.variables() != null && !command.variables().isEmpty()) {
            blocks.add(Map.of("type", "kv", "values", command.variables()));
        }
        Notice notice = new Notice(
                noticeKey,
                command.template(),
                blocks,
                List.of(),
                DataLabel.INTERNAL);
        service.notify(notice, List.of(new Recipient(command.recipient(), null)), channels);
    }
}

package com.tioka.infra.application.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.f4b6a3.ulid.UlidCreator;
import com.tioka.framework.messaging.DomainEvent;
import com.tioka.framework.messaging.EventPublisher;
import com.tioka.infra.api.mq.InfraMqTopics;
import com.tioka.infra.api.notify.Notice;
import com.tioka.infra.api.notify.Recipient;
import com.tioka.infra.infrastructure.NtfDeliveryRepository;
import com.tioka.infra.infrastructure.persistence.NtfDeliveryDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知门面（NTF-01 / M3-03 §15）。
 *
 * <p>{@code notify(notice, recipients, channels)}：对每个 (recipient, channel) 组合以
 * {@code dedup_key = {noticeKey}:{channel}:{recipient}} 幂等插入 {@code ntf_delivery}
 * （{@code ON CONFLICT DO NOTHING}，重复触发不重复发送——验收 {@code NotificationServiceIT#dedup}），
 * 新插入时经 outbox（{@code tioka.ntf.delivery_requested.v1}）异步投递给渠道消费者。
 *
 * <p>可逆性登记（ADR-033）：notify 是 <b>IRREVERSIBLE</b>——外部消息一旦发出不可撤回；
 * 只发本组织员工、内容 INTERNAL 的通知可按授权书配置免审批（M3-04 授权书侧承载，
 * 本服务不涉及 ToolDescriptor）。
 */
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NtfDeliveryRepository deliveries;
    private final EventPublisher publisher;
    private final ObjectMapper mapper;

    public NotificationService(NtfDeliveryRepository deliveries,
                               EventPublisher publisher,
                               ObjectMapper mapper) {
        this.deliveries = deliveries;
        this.publisher = publisher;
        this.mapper = mapper;
    }

    /**
     * 入队通知。返回本次<b>新插入</b>的 deliveryId（去重命中的组合不在其中）。
     * 必须在事务中调用（outbox 发布为 {@code Propagation.MANDATORY}）。
     */
    @Transactional
    public List<String> notify(Notice notice, List<Recipient> recipients, List<String> channels) {
        if (notice == null || notice.noticeKey() == null || notice.noticeKey().isBlank()) {
            throw new IllegalArgumentException("通知缺少 noticeKey");
        }
        List<String> created = new ArrayList<>();
        List<Recipient> rs = recipients == null ? List.of() : recipients;
        List<String> cs = channels == null ? List.of() : channels;
        for (String channel : cs) {
            if (channel == null || channel.isBlank()) {
                log.warn("notify 跳过空渠道 noticeKey={}", notice.noticeKey());
                continue;
            }
            for (Recipient recipient : rs) {
                String recipientValue = recipientValueOf(recipient);
                if (recipientValue == null) {
                    log.warn("notify 跳过无标识收件人 noticeKey={} channel={}",
                            notice.noticeKey(), channel);
                    continue;
                }
                String dedupKey = notice.noticeKey() + ":" + channel + ":" + recipientValue;
                NtfDeliveryDO row = new NtfDeliveryDO();
                row.setDeliveryId(UlidCreator.getMonotonicUlid().toString());
                row.setDedupKey(dedupKey);
                row.setChannel(channel.toLowerCase(java.util.Locale.ROOT));
                row.setRecipient(recipientValue);
                row.setNoticeJson(writeJson(notice));
                row.setStatus(NtfDeliveryRepository.STATUS_PENDING);
                row.setAttempts(0);
                if (!deliveries.insertIfAbsent(row)) {
                    continue; // 去重核心：同 noticeKey+channel+recipient 已入队
                }
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("deliveryId", row.getDeliveryId());
                payload.put("channel", row.getChannel());
                payload.put("templateKey", notice.noticeKey());
                payload.put("dedupeKey", dedupKey);
                payload.put("recipient", recipientValue);
                publisher.publish(new DomainEvent(
                        InfraMqTopics.NTF_DELIVERY_REQUESTED,
                        InfraMqTopics.TOPIC,
                        row.getDeliveryId(),
                        payload,
                        0,
                        null,
                        "ntf_delivery:" + row.getDeliveryId()));
                created.add(row.getDeliveryId());
            }
        }
        return created;
    }

    private static String recipientValueOf(Recipient recipient) {
        if (recipient == null) {
            return null;
        }
        if (recipient.externalStaffId() != null && !recipient.externalStaffId().isBlank()) {
            return recipient.externalStaffId();
        }
        if (recipient.userId() == null || recipient.userId().isBlank()) {
            return null;
        }
        return recipient.userId();
    }

    private String writeJson(Notice notice) {
        try {
            return mapper.writeValueAsString(notice);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 Notice 失败", e);
        }
    }
}

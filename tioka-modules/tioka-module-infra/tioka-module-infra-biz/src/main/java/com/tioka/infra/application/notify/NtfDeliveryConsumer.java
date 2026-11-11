package com.tioka.infra.application.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.framework.messaging.EventEnvelope;
import com.tioka.framework.messaging.IdempotentConsumer;
import com.tioka.governance.api.label.SinkCheckResult;
import com.tioka.governance.api.label.SinkGuard;
import com.tioka.infra.api.mq.InfraMqTopics;
import com.tioka.infra.api.notify.DeliveryRef;
import com.tioka.infra.api.notify.Notice;
import com.tioka.infra.api.notify.NotificationChannel;
import com.tioka.infra.api.notify.Recipient;
import com.tioka.infra.infrastructure.NtfDeliveryRepository;
import com.tioka.infra.infrastructure.persistence.NtfDeliveryDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;

/**
 * 通知投递消费者 {@code ntf-delivery}（NTF-01）：消费 {@code tioka.ntf.delivery_requested.v1}，
 * 按 deliveryId 回查 {@code ntf_delivery} 驱动状态机。
 *
 * <ul>
 *   <li>①去向许可（ADR-040）：{@code notice.label} 经 {@link NtfSinkKinds} 映射渠道种类后过
 *       {@link SinkGuard}——DENY / REQUIRE_APPROVAL → FAILED + {@code LABEL_DENIED}，不重试
 *       （许可判定是确定性的）；REDACTED：Notice 无单一文本载体，按原文发送并记录日志（偏差登记）。</li>
 *   <li>②收件人解析（FND-09）：{@link RecipientResolver} 查不到 → FAILED + {@code RECIPIENT_UNMAPPED}，
 *       不重试（降级「只发邮件」待 NTF-02）。</li>
 *   <li>③渠道查找（F35）：无注册实现 → FAILED + {@code CHANNEL_NOT_REGISTERED}，不重试
 *       （配置错误非瞬态）。</li>
 *   <li>④发送：直调 {@code channel.send}（ImSendConsumer 直调惯例，无 PureFunctionExecutor 包裹）；
 *       成功 → SENT + external_ref + sent_at；抛异常 → REQUIRES_NEW 记账 attempts/last_error 后
 *       重抛（事务回滚，交既有重试机制重投，max-attempts=3）；第 3 次耗尽 → FAILED + last_error +
 *       告警钩子，不抛（状态机终态自管；死信表不承载 ntf 终态——重放会因非 PENDING 跳过）。</li>
 * </ul>
 *
 * <p>重试退避实际口径（偏差登记）：复用 IdempotentDeliveryService / RocketMqConsumerBootstrap
 * 既有 max-attempts=3 + Broker 重投机制；in-memory 模式为进程内立即重试，RocketMQ 模式为
 * Broker 缺省重投间隔——与 M3-03 原文 1/5/30 分钟不同，正式口径随 NTF-02 渠道接入时对齐。
 */
public class NtfDeliveryConsumer extends IdempotentConsumer<Map<String, Object>> {

    private static final Logger log = LoggerFactory.getLogger(NtfDeliveryConsumer.class);

    public static final String ERR_LABEL_DENIED = "LABEL_DENIED";
    public static final String ERR_RECIPIENT_UNMAPPED = "RECIPIENT_UNMAPPED";
    public static final String ERR_CHANNEL_NOT_REGISTERED = "CHANNEL_NOT_REGISTERED";

    /** 重试上限（与 messaging.consumer.max-attempts=3 对齐，M3-03 原文 3 次）。 */
    public static final int MAX_ATTEMPTS = 3;

    private final NtfDeliveryRepository deliveries;
    private final RecipientResolver resolver;
    private final NotificationChannelRegistry registry;
    private final SinkGuard sinkGuard;
    private final NtfDeliveryAlertHook alertHook;
    private final ObjectMapper mapper;
    private final TransactionTemplate requiresNewTx;

    /** 测试 / 离线装配（固定依赖，SinkGuard / 告警钩子可空）。 */
    public NtfDeliveryConsumer(NtfDeliveryRepository deliveries,
                               RecipientResolver resolver,
                               NotificationChannelRegistry registry,
                               SinkGuard sinkGuard,
                               NtfDeliveryAlertHook alertHook,
                               ObjectMapper mapper,
                               PlatformTransactionManager txManager) {
        this.deliveries = deliveries;
        this.resolver = resolver;
        this.registry = registry;
        this.sinkGuard = sinkGuard;
        this.alertHook = alertHook;
        this.mapper = mapper;
        this.requiresNewTx = new TransactionTemplate(txManager);
        this.requiresNewTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Spring 装配（InfraNotifyAutoConfiguration；SinkGuard / 告警钩子弱依赖——条件装配缺席不阻塞）。 */
    public NtfDeliveryConsumer(NtfDeliveryRepository deliveries,
                               RecipientResolver resolver,
                               NotificationChannelRegistry registry,
                               ObjectProvider<SinkGuard> sinkGuard,
                               ObjectProvider<NtfDeliveryAlertHook> alertHook,
                               ObjectMapper mapper,
                               PlatformTransactionManager txManager) {
        this(deliveries, resolver, registry, sinkGuard.getIfAvailable(),
                alertHook.getIfAvailable(), mapper, txManager);
    }

    @Override
    protected String consumerName() {
        return "ntf-delivery";
    }

    @Override
    protected Class<Map<String, Object>> payloadType() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<Map<String, Object>> type = (Class) Map.class;
        return type;
    }

    @Override
    protected String topic() {
        return InfraMqTopics.TOPIC;
    }

    @Override
    protected String tagExpression() {
        return InfraMqTopics.NTF_DELIVERY_REQUESTED;
    }

    @Override
    protected void handle(EventEnvelope envelope, Map<String, Object> payload) {
        String deliveryId = str(payload == null ? null : payload.get("deliveryId"));
        if (deliveryId == null || deliveryId.isBlank()) {
            log.warn("delivery_requested 缺 deliveryId eventId={}",
                    envelope == null ? null : envelope.eventId());
            return;
        }
        NtfDeliveryDO row = deliveries.findById(deliveryId);
        if (row == null) {
            log.warn("delivery_requested 找不到投递行 deliveryId={}", deliveryId);
            return;
        }
        // 幂等：仅 PENDING 才推进（SENT / FAILED / UPDATED 直接跳过，死信重放亦不重发）
        if (!NtfDeliveryRepository.STATUS_PENDING.equals(row.getStatus())) {
            return;
        }

        // ① 去向许可（ADR-040）——先于渠道查找：许可是数据维度判定，与渠道实现是否在位无关
        Notice notice = readNotice(row);
        if (sinkGuard != null && notice != null) {
            SinkCheckResult result = sinkGuard.check(
                    NtfSinkKinds.kindOf(row.getChannel()), row.getRecipient(),
                    notice.label(), notice.title());
            switch (result.verdict()) {
                case PASS, REDACTED -> {
                    if (result.verdict() == SinkCheckResult.SinkVerdict.REDACTED) {
                        log.info("ntf REDACTED 放行原文 deliveryId={} reason={}",
                                deliveryId, result.reason());
                    }
                }
                default -> {
                    log.warn("ntf 去向许可拒绝 deliveryId={} channel={} verdict={} reason={}",
                            deliveryId, row.getChannel(), result.verdict(), result.reason());
                    deliveries.markFailed(deliveryId, ERR_LABEL_DENIED);
                    return; // 确定性失败，不重试
                }
            }
        }

        // ② 收件人解析（FND-09）
        var to = resolver.resolveRecipient(row.getRecipient(), row.getChannel());
        if (to.isEmpty()) {
            log.warn("ntf 收件人未映射 deliveryId={} recipient={} channel={}",
                    deliveryId, row.getRecipient(), row.getChannel());
            deliveries.markFailed(deliveryId, ERR_RECIPIENT_UNMAPPED);
            return; // 确定性失败，不重试
        }

        // ③ 渠道查找（F35）
        NotificationChannel channel = registry.find(row.getChannel()).orElse(null);
        if (channel == null) {
            log.warn("ntf 渠道未注册 deliveryId={} channel={}", deliveryId, row.getChannel());
            deliveries.markFailed(deliveryId, ERR_CHANNEL_NOT_REGISTERED);
            return; // 配置错误非瞬态，不重试（偏差登记）
        }

        // ④ 发送（直调惯例；失败交既有重试机制重投）
        send(row, channel, to.get(), notice);
    }

    private void send(NtfDeliveryDO row, NotificationChannel channel, Recipient to, Notice notice) {
        String deliveryId = row.getDeliveryId();
        try {
            DeliveryRef ref = channel.send(to, notice);
            deliveries.markSent(deliveryId, ref == null ? null : ref.externalRef(), Instant.now());
            log.info("ntf 投递成功 deliveryId={} channel={} ref={}",
                    deliveryId, row.getChannel(), ref == null ? null : ref.externalRef());
        } catch (RuntimeException e) {
            // REQUIRES_NEW 记账：attempts / last_error 在重试回滚后仍保留
            int attempts = requiresNewTx.execute(status ->
                    deliveries.recordAttempt(deliveryId, truncate(e.toString())));
            if (attempts >= MAX_ATTEMPTS) {
                // 3 次耗尽：终态 FAILED + 告警钩子；不抛（不再进死信——状态机终态自管）
                deliveries.markFailed(deliveryId, truncate(e.toString()));
                log.error("ntf 投递最终失败 deliveryId={} channel={} attempts={}: {}",
                        deliveryId, row.getChannel(), attempts, e.toString());
                if (alertHook != null) {
                    try {
                        alertHook.onDeliveryFailed(deliveryId, row.getChannel(),
                                row.getRecipient(), truncate(e.toString()));
                    } catch (RuntimeException hookError) {
                        log.warn("ntf 告警钩子失败（忽略）deliveryId={}: {}",
                                deliveryId, hookError.toString());
                    }
                }
                return;
            }
            log.warn("ntf 投递失败待重试 deliveryId={} attempts={}/{}: {}",
                    deliveryId, attempts, MAX_ATTEMPTS, e.toString());
            throw e; // 事务回滚（含 sys_consumed 标记）→ 既有重试机制重投
        }
    }

    private Notice readNotice(NtfDeliveryDO row) {
        try {
            return mapper.readValue(row.getNoticeJson(), Notice.class);
        } catch (Exception e) {
            log.warn("ntf notice_json 反序列化失败 deliveryId={}: {}", row.getDeliveryId(), e.toString());
            return null;
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }
}

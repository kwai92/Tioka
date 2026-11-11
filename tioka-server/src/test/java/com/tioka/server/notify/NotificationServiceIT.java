package com.tioka.server.notify;

import com.tioka.framework.label.DataLabel;
import com.tioka.framework.security.Principal;
import com.tioka.framework.security.PrincipalHolder;
import com.tioka.framework.security.PrincipalType;
import com.tioka.infra.api.notify.Notice;
import com.tioka.infra.api.notify.Recipient;
import com.tioka.infra.application.notify.NotificationService;
import com.tioka.infra.application.notify.NtfDeliveryAlertHook;
import com.tioka.infra.api.notify.ChannelCapabilities;
import com.tioka.infra.api.notify.DeliveryRef;
import com.tioka.infra.api.notify.NotificationChannel;
import com.tioka.infra.messaging.OutboxRelay;
import com.tioka.infra.messaging.RelayLock;
import com.tioka.server.support.IntegrationTestSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NTF-01 端到端（M3-03 §15 验收）：dedup / retryThenFail / labelDenied /
 * CHANNEL_NOT_REGISTERED / RECIPIENT_UNMAPPED。
 *
 * <p>in-memory 模式、relay 关后台由测试手动 drain（同 OutboxEndToEndIT）；
 * Testcontainers PG + Redis；NoopTestChannel 不走装配开关，改由 TestBeans
 * 注册可计数渠道桩（noop / flaky / recover）以便断言发送次数。
 */
@SpringBootTest
@Tag("it")
@Import(NotificationServiceIT.TestBeans.class)
class NotificationServiceIT {

    private static final String TAG = "tioka.ntf.delivery_requested.v1";

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.register(registry);
        registry.add("tioka.messaging.mode", () -> "in-memory");
        registry.add("tioka.messaging.relay-enabled", () -> "false");
        registry.add("tioka.notify.enabled", () -> "true");
        registry.add("tioka.notify.noop-channel.enabled", () -> "false");
        registry.add("tioka.notify.bridge.enabled", () -> "false");
    }

    @Autowired NotificationService service;
    @Autowired PrincipalHolder principals;
    @Autowired TransactionTemplate tx;
    @Autowired RelayLock relayLock;
    @Autowired OutboxRelay relay;
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationServiceIT.TestBeans testBeans;

    // —— 验收 ①：dedup——重复触发不重复发送（M3-03 NotificationServiceIT#dedup）——
    @Test
    void dedup() {
        String key = "lease-review:" + UUID.randomUUID() + ":ready";
        String recipient = "u-dedup-" + UUID.randomUUID();
        seedIdentity("noop", recipient);

        int noopBefore = testBeans.noop.sends.get();
        List<String> first = notify(key, recipient, "noop");
        List<String> second = notify(key, recipient, "noop");

        assertEquals(1, first.size(), "首次应入队 1 条投递");
        assertEquals(0, second.size(), "重复触发应被去重（不新插入）");
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ntf_delivery WHERE dedup_key = ?",
                Integer.class, key + ":noop:" + recipient);
        assertEquals(1, rows, "ntf_delivery 仅 1 行");
        Integer events = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_outbox WHERE tag = ? AND payload_json LIKE ?",
                Integer.class, TAG, "%" + key + "%");
        assertEquals(1, events, "delivery_requested 事件仅 1 条");

        drain();
        Map<String, Object> row = rowByKey(key + ":noop:" + recipient);
        assertEquals("SENT", row.get("status"), "去重后唯一事件仍正常投递");
        assertNotNull(row.get("sent_at"));
        assertEquals("noop-" + key, row.get("external_ref"));
        assertEquals(1, testBeans.noop.sends.get() - noopBefore,
                "渠道 send 恰好 1 次（重复触发不重复发送）");
    }

    // —— 验收 ②：retryThenFail——3 次尝试后 FAILED + attempts=3 + last_error 非空 ——
    @Test
    void retryThenFail() {
        String key = "lease-review:" + UUID.randomUUID() + ":ready";
        String recipient = "u-flaky-" + UUID.randomUUID();
        seedIdentity("flaky", recipient);
        int flakyBefore = testBeans.flaky.sends.get();

        List<String> ids = notify(key, recipient, "flaky");
        assertEquals(1, ids.size());

        drain();
        Map<String, Object> row = rowByKey(key + ":flaky:" + recipient);
        assertEquals("FAILED", row.get("status"));
        assertEquals(3, row.get("attempts"), "3 次重试记账（in-memory 立即重试，无真实分钟退避）");
        String lastError = (String) row.get("last_error");
        assertNotNull(lastError);
        assertTrue(lastError.contains("flaky-down"), "last_error 非空且含渠道异常");
        assertEquals(3, testBeans.flaky.sends.get() - flakyBefore,
                "渠道 send 恰好尝试 3 次");
        assertEquals(1, testBeans.alerts.size(), "3 次耗尽应触发告警钩子");
        assertTrue(testBeans.alerts.get(0).startsWith(ids.get(0)));
    }

    // —— 验收 ②b：前 2 次失败第 3 次成功——重试记账回滚不丢、终态 SENT ——
    @Test
    void retryThenSucceed() {
        String key = "lease-review:" + UUID.randomUUID() + ":ready";
        String recipient = "u-recover-" + UUID.randomUUID();
        seedIdentity("recover", recipient);

        List<String> ids = notify(key, recipient, "recover");
        assertEquals(1, ids.size());

        drain();
        Map<String, Object> row = rowByKey(key + ":recover:" + recipient);
        assertEquals("SENT", row.get("status"));
        assertEquals(2, row.get("attempts"), "前两次失败记账保留（REQUIRES_NEW）");
        assertEquals("recover-" + key, row.get("external_ref"));
    }

    // —— 验收 ③：labelDenied——CONFIDENTIAL 通知发往邮件渠道被拒（M3-03 原文）——
    @Test
    void labelDenied() {
        String key = "lease-review:" + UUID.randomUUID() + ":ready";
        String recipient = "u-label-" + UUID.randomUUID();
        int noopBefore = testBeans.noop.sends.get();

        Notice notice = new Notice(key, "结论通知", List.of(), List.of(), DataLabel.CONFIDENTIAL);
        List<String> ids = notify(notice, recipient, "email");
        assertEquals(1, ids.size());

        drain();
        Map<String, Object> row = rowByKey(key + ":email:" + recipient);
        assertEquals("FAILED", row.get("status"));
        assertEquals("LABEL_DENIED", row.get("last_error"));
        assertEquals(0, row.get("attempts"), "许可判定是确定性的，不重试");
        assertEquals(0, testBeans.noop.sends.get() - noopBefore,
                "被拒通知不得调用渠道发送");
    }

    // —— 验收 ④：CHANNEL_NOT_REGISTERED——未注册渠道 FAILED 不重试 ——
    @Test
    void channelNotRegistered() {
        String key = "lease-review:" + UUID.randomUUID() + ":ready";
        String recipient = "u-sms-" + UUID.randomUUID();
        seedIdentity("sms", recipient);

        List<String> ids = notify(key, recipient, "sms");
        assertEquals(1, ids.size());

        drain();
        Map<String, Object> row = rowByKey(key + ":sms:" + recipient);
        assertEquals("FAILED", row.get("status"));
        assertEquals("CHANNEL_NOT_REGISTERED", row.get("last_error"));
        assertEquals(0, row.get("attempts"), "配置错误非瞬态，不重试");
    }

    // —— 验收 ⑤：RECIPIENT_UNMAPPED——外部身份映射缺失路径 ——
    @Test
    void recipientUnmapped() {
        String key = "lease-review:" + UUID.randomUUID() + ":ready";
        String recipient = "u-ghost-" + UUID.randomUUID(); // 不种子身份行

        List<String> ids = notify(key, recipient, "noop");
        assertEquals(1, ids.size());

        drain();
        Map<String, Object> row = rowByKey(key + ":noop:" + recipient);
        assertEquals("FAILED", row.get("status"));
        assertEquals("RECIPIENT_UNMAPPED", row.get("last_error"));
        assertEquals(0, row.get("attempts"), "映射缺失是确定性的，不重试");
    }

    // —— 支持 ——

    private List<String> notify(String key, String recipient, String channel) {
        return notify(new Notice(key, "标题：" + key, List.of(), List.of(), DataLabel.INTERNAL),
                recipient, channel);
    }

    private List<String> notify(Notice notice, String recipient, String channel) {
        return tx.execute(status ->
                principals.runAs(principal(), () ->
                        service.notify(notice, List.of(Recipient.ofUser(recipient)),
                                List.of(channel))));
    }

    private void seedIdentity(String source, String userId) {
        jdbc.update("INSERT INTO sys_external_identity (source, external_id, user_id, create_at, create_by) "
                        + "VALUES (?, ?, ?, now(), 'it')",
                source, "ext-" + userId, userId);
    }

    private void drain() {
        String token = "ntf-it-" + UUID.randomUUID();
        assertTrue(relayLock.tryAcquire(token), "测试应能获得 relay 锁");
        try {
            while (relay.drainOnceForTest(token, n -> {}) > 0) {
                // 投递全部未发送 outbox（含遗留），渠道按 topic+tag 过滤
            }
        } finally {
            relayLock.release(token);
        }
    }

    private Map<String, Object> rowByKey(String dedupKey) {
        return jdbc.queryForMap(
                "SELECT status, attempts, last_error, external_ref, sent_at "
                        + "FROM ntf_delivery WHERE dedup_key = ?", dedupKey);
    }

    @TestConfiguration
    static class TestBeans {
        final CountingChannel noop = new CountingChannel("noop", false, 0);
        final CountingChannel flaky = new CountingChannel("flaky", true, 0);
        final CountingChannel recover = new CountingChannel("recover", false, 2);
        final List<String> alerts = new java.util.ArrayList<>();

        @Bean
        NotificationServiceIT.TestBeans testBeans() {
            return this;
        }

        @Bean
        NotificationChannel noopTestChannel() {
            return noop;
        }

        @Bean
        NotificationChannel flakyTestChannel() {
            return flaky;
        }

        @Bean
        NotificationChannel recoverTestChannel() {
            return recover;
        }

        @Bean
        NtfDeliveryAlertHook recordingAlertHook() {
            return (deliveryId, channel, recipient, lastError) -> alerts.add(deliveryId + ":" + lastError);
        }
    }

    /** 可计数渠道桩：fail=false 且 skipSends>0 时前 N 次抛异常（重试恢复场景）。 */
    static final class CountingChannel implements NotificationChannel {
        private final String name;
        private final boolean alwaysFail;
        private final int failFirst;
        private int calls;
        final AtomicInteger sends = new AtomicInteger();

        CountingChannel(String name, boolean alwaysFail, int failFirst) {
            this.name = name;
            this.alwaysFail = alwaysFail;
            this.failFirst = failFirst;
        }

        @Override
        public String channel() {
            return name;
        }

        @Override
        public ChannelCapabilities capabilities() {
            return new ChannelCapabilities(false, false, 4096, false);
        }

        @Override
        public synchronized DeliveryRef send(Recipient to, Notice notice) {
            calls++;
            if (alwaysFail || calls <= failFirst) {
                sends.incrementAndGet();
                throw new IllegalStateException("flaky-down-" + name);
            }
            sends.incrementAndGet();
            String ref = name + "-" + notice.noticeKey();
            return new DeliveryRef(name, ref.length() > 120 ? ref.substring(0, 120) : ref);
        }
    }

    private static Principal principal() {
        return new Principal("42", "ws_ntf_it", PrincipalType.USER,
                Set.of("workspace_member"), Set.of("*"), "password");
    }
}

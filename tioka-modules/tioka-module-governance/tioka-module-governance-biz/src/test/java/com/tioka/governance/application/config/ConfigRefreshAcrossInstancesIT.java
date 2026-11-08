package com.tioka.governance.application.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.governance.api.config.ConfigVersionApi.ObjectRef;
import com.tioka.governance.api.config.ConfigVersionApi.PublishCommand;
import com.tioka.governance.api.config.ConfigVersionApi.SaveDraftCommand;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * GOV-12 验收：跨实例 Redis pub/sub 刷新 + 对账兜底（ADR-041 §2，不经 outbox）。
 */
class ConfigRefreshAcrossInstancesIT {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void refreshAcrossInstancesAfterPublish() throws Exception {
        try (ConfigVersionITSupport.Instance a = ConfigVersionITSupport.newInstance("inst-a");
             ConfigVersionITSupport.Instance b = ConfigVersionITSupport.newInstance("inst-b")) {
            com.tioka.governance.api.config.ConfigVersionApi apiA = a.api();
            ObjectRef ref = new ObjectRef("DEMO_CONFIG", "xi-" + UUID.randomUUID());

            ObjectNode v1 = mapper.createObjectNode().put("region", "cn-east");
            var draft = apiA.saveDraft(new SaveDraftCommand(ref, v1, 0L));
            apiA.publish(new PublishCommand(ref, "from-a"));

            awaitCurrent(b, ref, 1, Duration.ofSeconds(10));
            assertEquals("cn-east", b.api().getCurrent(ref).orElseThrow().spec().path("region").asText());

            // 模拟 B 丢消息：本地缓存落后，对账从 Redis 指针纠正
            ObjectNode v2 = mapper.createObjectNode().put("region", "cn-north");
            apiA.saveDraft(new SaveDraftCommand(ref, v2, draft.revision()));
            // 先让 B 订阅正常收到；再人为写脏缓存验证 reconciler
            apiA.publish(new PublishCommand(ref, "from-a-2"));
            awaitCurrent(b, ref, 2, Duration.ofSeconds(10));

            b.cache().put(ref, new ConfigProjectionCache.Entry(
                    "stale", 1, mapper.createObjectNode().put("region", "stale"), "x".repeat(64), "PUBLISHED"));
            assertEquals(1, b.cache().get(ref).orElseThrow().versionNo());
            b.reconciler().reconcile();
            assertEquals(2, b.cache().get(ref).orElseThrow().versionNo());
            assertEquals("cn-north", b.api().getCurrent(ref).orElseThrow().spec().path("region").asText());
        }
    }

    private static void awaitCurrent(
            ConfigVersionITSupport.Instance inst, ObjectRef ref, int versionNo, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (inst.cache().get(ref).map(e -> e.versionNo() == versionNo).orElse(false)
                    || inst.api().getCurrent(ref).map(v -> v.versionNo() == versionNo).orElse(false)) {
                assertTrue(inst.api().getCurrent(ref).isPresent());
                assertEquals(versionNo, inst.api().getCurrent(ref).orElseThrow().versionNo());
                return;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        fail("instance did not refresh to version " + versionNo + " within " + timeout);
    }
}

package com.tioka.connector.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.connector.support.DirectEgress;
import com.tioka.connector.support.FeishuOpenApiStub;
import com.tioka.connector.support.InMemoryImOutboxRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * im_outbox 行为契约。
 * <p>缺陷（记入 PR，另开修复）：生产代码尚无独立 outbox 发送器，无失败重试与最终失败标记字段；
 * 本用例验证「发送失败时仍已落库」以及「仅 outbox 模式可查询」。
 */
class ImOutboxIT {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void failedOpenApiStillLeavesOutboxRow() throws Exception {
        try (FeishuOpenApiStub stub = FeishuOpenApiStub.start()) {
            stub.enqueueTokenFromResource("feishu/tenant_access_token.json");
            stub.enqueueMessage(200, "{\"code\":99991400,\"msg\":\"request trigger frequency limit\"}");

            InMemoryImOutboxRepository outbox = new InMemoryImOutboxRepository();
            FeishuImChannelAdapter adapter = new FeishuImChannelAdapter(
                    outbox, new DirectEgress(),
                    "", "cli_mock", "secret", stub.baseUrl(), "chat_id", mapper);

            assertThrows(IllegalStateException.class, () -> adapter.sendText("oc_1", "retry-me"));
            assertEquals(1, outbox.findByChannel("feishu").size());
            assertTrue(outbox.findByChannel("feishu").get(0).getPayloadJson().contains("retry-me"));
        }
    }

    @Test
    void multiChannelOutboxIsolation() {
        InMemoryImOutboxRepository outbox = new InMemoryImOutboxRepository();
        FeishuImChannelAdapter feishu = new FeishuImChannelAdapter(
                outbox, new DirectEgress(), "", "", "", "https://open.feishu.cn", "chat_id", mapper);
        WeComImChannelAdapter wecom = new WeComImChannelAdapter(
                outbox, new DirectEgress(), "", mapper);
        DingTalkImChannelAdapter dingtalk = new DingTalkImChannelAdapter(
                outbox, new DirectEgress(), "", mapper);

        feishu.sendText("a", "f");
        wecom.sendText("b", "w");
        dingtalk.sendText("c", "d");

        assertEquals(1, outbox.findByChannel("feishu").size());
        assertEquals(1, outbox.findByChannel("wecom").size());
        assertEquals(1, outbox.findByChannel("dingtalk").size());
        assertEquals(3, outbox.all().size());
    }
}

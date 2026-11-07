package com.tioka.governance.application.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.governance.api.config.ConfigVersionApi.ConfigDraft;
import com.tioka.governance.api.config.ConfigVersionApi.ConfigVersion;
import com.tioka.governance.api.config.ConfigVersionApi.ObjectRef;
import com.tioka.governance.api.config.ConfigVersionApi.PublishCommand;
import com.tioka.governance.api.config.ConfigVersionApi.RollbackCommand;
import com.tioka.governance.api.config.ConfigVersionApi.SaveDraftCommand;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * GOV-12 验收：回滚不产生新版本号，投影恢复为旧版本。
 */
class ConfigRollbackIT {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void publishThenRollbackRestoresProjection() {
        try (ConfigVersionITSupport.Instance inst = ConfigVersionITSupport.newInstance("tester")) {
            com.tioka.governance.api.config.ConfigVersionApi api = inst.api();
            ObjectRef ref = new ObjectRef("DEMO_CONFIG", "rb-" + UUID.randomUUID());

            ObjectNode v1 = mapper.createObjectNode().put("endpoint", "https://a.example");
            ConfigDraft d1 = api.saveDraft(new SaveDraftCommand(ref, v1, 0L));
            ConfigVersion p1 = api.publish(new PublishCommand(ref, "v1"));
            assertEquals(1, p1.versionNo());

            ObjectNode v2 = mapper.createObjectNode().put("endpoint", "https://b.example");
            ConfigDraft d2 = api.saveDraft(new SaveDraftCommand(ref, v2, d1.revision()));
            ConfigVersion p2 = api.publish(new PublishCommand(ref, "v2"));
            assertEquals(2, p2.versionNo());
            assertEquals("https://b.example", api.getCurrent(ref).orElseThrow().spec().path("endpoint").asText());

            ConfigVersion rolled = api.rollback(new RollbackCommand(ref, 1, "revert bad endpoint"));
            assertEquals(1, rolled.versionNo());
            assertEquals(p1.versionId(), rolled.versionId());
            assertEquals("https://a.example", api.getCurrent(ref).orElseThrow().spec().path("endpoint").asText());
            assertEquals(1, api.getCurrent(ref).orElseThrow().versionNo());

            // 再发一版应为 3：回滚未消耗版本号
            ObjectNode v3 = mapper.createObjectNode().put("endpoint", "https://c.example");
            api.saveDraft(new SaveDraftCommand(ref, v3, d2.revision()));
            ConfigVersion p3 = api.publish(new PublishCommand(ref, "v3"));
            assertEquals(3, p3.versionNo());
        }
    }
}

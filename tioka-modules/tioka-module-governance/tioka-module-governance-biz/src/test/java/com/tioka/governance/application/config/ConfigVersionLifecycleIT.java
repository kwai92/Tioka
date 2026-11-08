package com.tioka.governance.application.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.framework.error.BizException;
import com.tioka.framework.error.ErrorCode;
import com.tioka.governance.api.config.ConfigVersionApi.ConfigDraft;
import com.tioka.governance.api.config.ConfigVersionApi.ConfigVersion;
import com.tioka.governance.api.config.ConfigVersionApi.ExportBundle;
import com.tioka.governance.api.config.ConfigVersionApi.ObjectRef;
import com.tioka.governance.api.config.ConfigVersionApi.PublishCommand;
import com.tioka.governance.api.config.ConfigVersionApi.SaveDraftCommand;
import com.tioka.governance.api.config.ConfigVersionApi.ValidationReport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GOV-12 验收：草稿 → 校验 → 发布 → 差异 / 影响 / 导出导入。
 */
class ConfigVersionLifecycleIT {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void saveDraftValidatePublishDiffExportImport() throws Exception {
        try (ConfigVersionITSupport.Instance inst = ConfigVersionITSupport.newInstance("tester")) {
            com.tioka.governance.api.config.ConfigVersionApi api = inst.api();
            ObjectRef ref = new ObjectRef("DEMO_CONFIG", "obj-" + UUID.randomUUID());

            ObjectNode v1 = mapper.createObjectNode().put("name", "alpha").put("rpm", 10);
            ConfigDraft draft = api.saveDraft(new SaveDraftCommand(ref, v1, 0L));
            assertEquals(1L, draft.revision());
            assertTrue(draft.spec().path("name").asText().equals("alpha"));

            BizException stale = assertThrows(BizException.class,
                    () -> api.saveDraft(new SaveDraftCommand(ref, v1, 0L)));
            assertEquals(ErrorCode.STALE_REVISION, stale.getErrorCode());

            ObjectNode v1b = mapper.createObjectNode()
                    .put("name", "alpha")
                    .put("rpm", 10)
                    .put("apiKey", "should-strip");
            draft = api.saveDraft(new SaveDraftCommand(ref, v1b, draft.revision()));
            assertFalse(draft.spec().has("apiKey"));

            ValidationReport report = api.validate(ref);
            assertTrue(report.ok());

            ConfigVersion published = api.publish(new PublishCommand(ref, "first"));
            assertEquals(1, published.versionNo());
            assertEquals("PUBLISHED", published.status());
            assertEquals(1, api.getCurrent(ref).orElseThrow().versionNo());

            ObjectNode v2 = mapper.createObjectNode().put("name", "beta").put("rpm", 20);
            draft = api.saveDraft(new SaveDraftCommand(ref, v2, draft.revision()));
            ConfigVersion p2 = api.publish(new PublishCommand(ref, "second"));
            assertEquals(2, p2.versionNo());

            var diff = api.diff(ref, 1);
            assertEquals("alpha", diff.before().path("name").asText());
            assertEquals("beta", diff.after().path("name").asText());

            var impact = api.analyzeImpact(ref);
            assertEquals(2, api.getCurrent(ref).orElseThrow().versionNo());
            assertEquals(ref, impact.ref());

            ExportBundle bundle = api.exportPublished(List.of(ref));
            assertEquals("1", bundle.formatVersion());
            assertEquals(1, bundle.versions().size());
            assertEquals(2, bundle.versions().get(0).versionNo());
            assertFalse(bundle.versions().get(0).spec().has("apiKey"));

            ObjectRef imported = new ObjectRef("DEMO_CONFIG", "imported-" + UUID.randomUUID());
            ExportBundle asImport = new ExportBundle("1", List.of(
                    new ConfigVersion(
                            "x",
                            imported,
                            2,
                            bundle.versions().get(0).spec(),
                            bundle.versions().get(0).contentHash(),
                            "PUBLISHED")));
            List<ConfigDraft> drafts = api.importAsDrafts(asImport);
            assertEquals(1, drafts.size());
            assertEquals("beta", drafts.get(0).spec().path("name").asText());
            assertTrue(api.getCurrent(imported).isEmpty());
        }
    }
}

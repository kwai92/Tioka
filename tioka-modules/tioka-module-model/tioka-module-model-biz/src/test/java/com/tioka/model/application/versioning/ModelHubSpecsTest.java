package com.tioka.model.application.versioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tioka.model.api.versioning.ModelHubSpecs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MH-08：版本 spec 构造与 sys.* 命名纯函数（V151 迁移与运行时发布共用，单一事实源）。
 */
class ModelHubSpecsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sysAliasNamingRoundtrip() {
        assertEquals("sys.chat", ModelHubSpecs.sysAliasName("CHAT"));
        assertEquals("sys.flow", ModelHubSpecs.sysAliasName("FLOW"));
        assertEquals("sys.embedding", ModelHubSpecs.sysAliasName("EMBEDDING"));
        assertEquals("sys.rerank", ModelHubSpecs.sysAliasName("RERANK"));
        assertEquals("sys.decision", ModelHubSpecs.sysAliasName("DECISION"));
        assertEquals("CHAT", ModelHubSpecs.sysAliasPurpose("sys.chat"));
        assertEquals("EMBEDDING", ModelHubSpecs.sysAliasPurpose("sys.embedding"));
        assertNull(ModelHubSpecs.sysAliasPurpose("chat.default"));
        assertNull(ModelHubSpecs.sysAliasPurpose("sys."));
    }

    @Test
    void sysAliasSeedMapsTarget() {
        ModelHubSpecs.AliasSeed viaPlan = ModelHubSpecs.sysAliasSeed("CHAT", "rp_1", null);
        assertEquals("alias_sys_chat", viaPlan.id());
        assertEquals("sys.chat", viaPlan.name());
        assertEquals("CHAT", viaPlan.purpose());
        assertEquals(ModelHubSpecs.TARGET_ROUTING_PLAN, viaPlan.targetType());
        assertEquals("rp_1", viaPlan.targetId());

        ModelHubSpecs.AliasSeed viaBinding = ModelHubSpecs.sysAliasSeed("RERANK", null, "bnd_1");
        assertEquals(ModelHubSpecs.TARGET_BINDING, viaBinding.targetType());
        assertEquals("bnd_1", viaBinding.targetId());

        assertThrows(IllegalStateException.class, () -> ModelHubSpecs.sysAliasSeed("CHAT", null, null));
    }

    @Test
    void canonicalJsonIsKeySortedAndHashStable() {
        var a = ModelHubSpecs.aliasSpec(MAPPER, "sys.chat", null, "CHAT", "binding", "b1");
        var b = ModelHubSpecs.aliasSpec(MAPPER, "sys.chat", null, "CHAT", "binding", "b1");
        assertEquals(ModelHubSpecs.canonicalJson(MAPPER, a), ModelHubSpecs.canonicalJson(MAPPER, b));
        assertEquals(ModelHubSpecs.contentHash(MAPPER, a), ModelHubSpecs.contentHash(MAPPER, b));
        // 键排序：baseUrl 在 name 之前（ASCII b < n）
        var p = ModelHubSpecs.providerSpec(MAPPER, "prov", "http://x", "ref_1");
        String json = ModelHubSpecs.canonicalJson(MAPPER, p);
        assertTrue(json.indexOf("baseUrl") < json.indexOf("name"));
        // 密钥引用进版本（只存引用），hash 覆盖它
        assertTrue(json.contains("ref_1"));
    }

    @Test
    void supportedTypes() {
        assertTrue(ModelHubSpecs.isSupportedType(ModelHubSpecs.OT_PROVIDER));
        assertTrue(ModelHubSpecs.isSupportedType(ModelHubSpecs.OT_BINDING));
        assertTrue(ModelHubSpecs.isSupportedType(ModelHubSpecs.OT_ALIAS));
        assertTrue(ModelHubSpecs.isSupportedType(ModelHubSpecs.OT_ROUTING_PLAN));
        assertFalse(ModelHubSpecs.isSupportedType("prm_template"));
    }
}

package com.tioka.knowledge.application;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** KNW-14 / ADR-056 §3：三种知识使用模式与 kb.* 工具清单。 */
class KbToolProviderModeTest {

    private final KbToolProvider provider = new KbToolProvider(null, null, null);

    @Test
    void autoModeDoesNotExposeTools() {
        assertTrue(provider.listTools("av",
                Map.of("knowledge", Map.of("mode", "AUTO"))).isEmpty());
    }

    @Test
    void toolAndBothModesExposeAllTools() {
        List<String> expected = List.of("kb.search", "kb.read", "kb.list",
                "kb.read_chunk", "kb.read_document");
        for (String mode : List.of("TOOL", "BOTH")) {
            assertEquals(expected, provider.listTools("av",
                            Map.of("knowledge", Map.of("mode", mode))).stream()
                    .map(KbToolProvider.ToolDescriptor::name).toList(),
                    mode + " 模式应开放全部 kb.* 工具");
        }
    }

    @Test
    void unknownModeFallsBackToTool() {
        assertFalse(provider.listTools("av",
                Map.of("knowledge", Map.of("mode", "WHATEVER"))).isEmpty());
        assertFalse(provider.listTools("av", Map.of()).isEmpty());
        assertFalse(provider.listTools("av").isEmpty(),
                "无 agentToolConfig 的旧调用路径保持兼容");
    }

    @Test
    void modeIsCaseInsensitive() {
        assertTrue(provider.listTools("av",
                Map.of("knowledge", Map.of("mode", "auto"))).isEmpty());
    }
}

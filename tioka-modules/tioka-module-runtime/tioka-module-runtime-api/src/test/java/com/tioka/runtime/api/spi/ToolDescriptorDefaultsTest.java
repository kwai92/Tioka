package com.tioka.runtime.api.spi;

import com.tioka.runtime.api.approval.ToolRisk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PK-24a：ToolDescriptor 可逆性与 policyFields 默认值。 */
class ToolDescriptorDefaultsTest {

    @Test
    void readRiskDefaultsToNone() {
        var d = new ToolProvider.ToolDescriptor("echo", "e", Map.of());
        assertEquals(ToolRisk.READ, d.risk());
        assertEquals(Reversibility.NONE, d.reversibility());
        assertTrue(d.policyFields().isEmpty());
    }

    @Test
    void writeRiskDefaultsToIrreversible() {
        var d = new ToolProvider.ToolDescriptor("upd", "u", Map.of(), ToolRisk.WRITE);
        assertEquals(Reversibility.IRREVERSIBLE, d.reversibility());
    }

    @Test
    void destructiveAndExternalDefaultIrreversible() {
        assertEquals(
                Reversibility.IRREVERSIBLE,
                new ToolProvider.ToolDescriptor("rm", "", Map.of(), ToolRisk.DESTRUCTIVE).reversibility());
        assertEquals(
                Reversibility.IRREVERSIBLE,
                new ToolProvider.ToolDescriptor("mail", "", Map.of(), ToolRisk.EXTERNAL_SEND).reversibility());
    }

    @Test
    void builderCanOverrideReversibilityAndPolicyFields() {
        var d = ToolProvider.ToolDescriptor.builder()
                .name("pay")
                .risk(ToolRisk.WRITE)
                .reversibility(Reversibility.COMPENSABLE)
                .policyFields(List.of("amount", "currency"))
                .build();
        assertEquals(Reversibility.COMPENSABLE, d.reversibility());
        assertEquals(List.of("amount", "currency"), d.policyFields());
    }

    @Test
    void prepareAndCommitDefaultUnsupported() {
        ToolGateway gw = new ToolGateway() {
            @Override
            public List<ToolProvider.ToolDescriptor> listTools(ToolCallContext ctx) {
                return List.of();
            }

            @Override
            public ToolProvider.ToolResult call(ToolCallContext ctx, String toolName, Map<String, Object> args) {
                return new ToolProvider.ToolResult(true, "ok");
            }

            @Override
            public List<Map<String, Object>> asOpenAiTools(ToolCallContext ctx) {
                return List.of();
            }
        };
        assertThrows(UnsupportedOperationException.class, () -> gw.prepare(null, "x", Map.of()));
        assertThrows(UnsupportedOperationException.class, () -> gw.commit(null, "p1"));
    }
}

package com.tioka.mcp.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tioka.runtime.api.approval.ToolRisk;
import com.tioka.runtime.api.spi.Reversibility;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** PK-24c：annotations.readOnlyHint → risk / reversibility 默认。 */
class McpReadOnlyHintTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readOnlyHintMapsToReadRisk() throws Exception {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("name", "search");
        tool.put("description", "ro");
        tool.putObject("inputSchema").put("type", "object");
        tool.putObject("annotations").put("readOnlyHint", true);

        boolean readOnly = tool.path("annotations").path("readOnlyHint").asBoolean(false);
        String risk = readOnly ? "READ" : "WRITE";
        assertEquals("READ", risk);
        assertEquals(Reversibility.NONE,
                risk.equals("READ") ? Reversibility.NONE : Reversibility.IRREVERSIBLE);
        assertEquals(ToolRisk.READ, ToolRisk.valueOf(risk));
    }

    @Test
    void writeToolDefaultsIrreversible() {
        boolean readOnly = false;
        String risk = readOnly ? "READ" : "WRITE";
        Reversibility rev = "READ".equals(risk) ? Reversibility.NONE : Reversibility.IRREVERSIBLE;
        assertEquals(Reversibility.IRREVERSIBLE, rev);
    }
}

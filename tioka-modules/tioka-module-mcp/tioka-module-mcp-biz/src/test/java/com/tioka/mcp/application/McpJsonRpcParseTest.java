package com.tioka.mcp.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class McpJsonRpcParseTest {
    @Test
    void extractsSseDataLine() {
        String raw = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"tools\":[]}}\n\n";
        String json = McpAdminService.extractJsonRpc(raw);
        assertTrue(json.startsWith("{"));
        assertTrue(json.contains("jsonrpc"));
    }
}

package com.tioka.runtime.application;

import com.tioka.framework.error.BizException;
import com.tioka.runtime.api.spi.ToolGateway;
import com.tioka.runtime.api.spi.ToolProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultToolGatewayTest {
    @Test
    void deniesUnknownTool() {
        ToolProvider echo = new EchoToolProvider();
        DefaultToolGateway gw = new DefaultToolGateway(new ToolRegistry(List.of(echo)));
        ToolGateway.SessionToolContext ctx = new ToolGateway.SessionToolContext("s", "av", "ws", null);
        assertTrue(gw.listTools(ctx).stream().anyMatch(t -> t.name().equals("echo")));
        assertThrows(BizException.class, () -> gw.call(ctx, "not_granted_tool", Map.of()));
    }

    @Test
    void allowsEcho() {
        DefaultToolGateway gw = new DefaultToolGateway(new ToolRegistry(List.of(new EchoToolProvider())));
        ToolGateway.SessionToolContext ctx = new ToolGateway.SessionToolContext("s", "av", "ws", null);
        var r = gw.call(ctx, "echo", Map.of("text", "hi"));
        assertTrue(r.ok());
    }

    @Test
    void rejectsNullContextBypass() {
        DefaultToolGateway gw = new DefaultToolGateway(new ToolRegistry(List.of(new EchoToolProvider())));
        assertThrows(BizException.class, () -> gw.call(null, "echo", Map.of("text", "hi")));
        assertThrows(BizException.class,
                () -> gw.call(new ToolGateway.SessionToolContext("s", "", "ws", null), "echo", Map.of()));
    }
}
